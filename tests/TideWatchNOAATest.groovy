// Run from the repository root with Groovy 2.4.21.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def state = [:]
def settings = [
  stationId:'9414290', units:'english', datum:'MLLW', pollMinutes:'30', forecastHours:'48', staleMinutes:'60',
  watchLevel:5.5d, warningLevel:6.5d, criticalLevel:7.5d, notifyRecovery:true,
  highTideThreshold:6.0d, highTideLeadHours:'6', residualThreshold:1.0d,
  notifyResidual:true, notifyStale:true, notifyInitialState:false, notifyOnlyOnChange:true, historySize:24
]
def readings = [:]
def events = []
def requests = []
def jobs = [:]
def warnings = []
long clock = Date.parse('yyyy-MM-dd HH:mm', '2026-09-22 18:00', TimeZone.getTimeZone('UTC')).time
def location = [timeZone:TimeZone.getTimeZone('America/Los_Angeles')]

def binding = new Binding([
  state:state, settings:settings, location:location,
  metadata:{ Closure ignored -> }, now:{ -> clock },
  device:[currentValue:{ String name -> readings[name] }, updateSetting:{ String name, Map value -> settings[name] = value.value }],
  log:[warn:{ message -> warnings << message.toString() }, debug:{ ignored -> }, info:{ ignored -> }],
  sendEvent:{ Map event -> events << event; readings[event.name] = event.value },
  runIn:{ seconds, handler, Map options = [:] -> jobs[handler] = [seconds:seconds, options:options] },
  unschedule:{ String handler = null -> if (handler) jobs.remove(handler) else jobs.clear() },
  runEvery1Hour:{ handler -> jobs[handler] = [every:3600] },
  schedule:{ expression, handler -> jobs[handler] = [cron:expression] },
  asynchttpGet:{ callback, params, data -> requests << [callback:callback, params:params, data:data] }
])

def driver = new GroovyShell(binding).parse(new File('TideWatchNOAA.groovy'))
driver.run()

def ok = { body -> [hasError:{ -> false }, status:200, getJson:{ -> body }] }
def failed = { int status -> [hasError:{ -> true }, status:status, getJson:{ -> null }] }
def noaaTime = { long time -> new Date(time).format('yyyy-MM-dd HH:mm', TimeZone.getTimeZone('UTC')) }
def metadataBody = { -> [stations:[[id:'9414290', name:'San Francisco', state:'CA', lat:37.8063d, lng:-122.4659d, tidal:true]]] }
def observedBody = { double value, long time = clock, String quality = 'p' -> [data:[[t:noaaTime(time), v:value.toString(), s:'0.02', f:'0,0,0,0', q:quality]]] }
def currentBody = { double value, long time = clock -> [predictions:[[t:noaaTime(time), v:value.toString()]]] }
def tidesBody = { double high = 5.0d, long highTime = clock + 4 * 3600000L, double low = 0.4d, long lowTime = clock + 10 * 3600000L ->
  [predictions:[[t:noaaTime(highTime), v:high.toString(), type:'H'], [t:noaaTime(lowTime), v:low.toString(), type:'L'],
                [t:noaaTime(highTime + 12 * 3600000L), v:(high - 0.2d).toString(), type:'H']]]
}
def complete = { double observed, double predicted, Map tides = tidesBody(), Map options = [:] ->
  def first = requests[-1]
  if (first.callback == 'metadataResponse') {
    driver.metadataResponse(ok(metadataBody()), first.data)
    first = requests[-1]
  }
  assert first.callback == 'observedResponse'
  driver.observedResponse(ok(observedBody(observed, options.observedTime ?: clock)), first.data)
  def currentRequest = requests[-1]
  assert currentRequest.callback == 'currentPredictionResponse'
  driver.currentPredictionResponse(options.currentFailure ? failed(options.currentFailure as int) : ok(currentBody(predicted)), currentRequest.data)
  def tidesRequest = requests[-1]
  assert tidesRequest.callback == 'highLowResponse'
  driver.highLowResponse(ok(tides), tidesRequest.data)
}

driver.installed()
assert requests.empty
assert readings.watchStatus == 'setup'
assert readings.numberOfButtons == 3
driver.initialize()
assert jobs.refresh.cron == '0 */30 * ? * *'
assert requests[-1].callback == 'metadataResponse'
assert requests[-1].params.uri.endsWith('/9414290.json')
assert !requests[-1].params.toString().toLowerCase().contains('apikey')
complete(4.0d, 3.5d)
assert readings.watchStatus == 'ready'
assert readings.stationName == 'San Francisco'
assert readings.stationTidal == 'yes'
assert readings.waterLevel.toString() == '4.000'
assert readings.waterLevelDatum == 'MLLW'
assert readings.waterLevelUnit == 'ft'
assert readings.waterLevelResidual.toString() == '0.500'
assert readings.nextHighTide.toString() == '5.000'
assert readings.tideState == 'normal'
assert readings.tidePhase == 'rising'
assert !events.any { it.name == 'pushed' }
assert state.hasBaseline
println 'PASS: setup gate, no-key NOAA pipeline, station metadata, baseline suppression and attributes'

clock += 1800000L
driver.refresh()
complete(7.0d, 6.6d, tidesBody(5.1d))
assert readings.tideState == 'warning'
assert readings.waterLevelTrend == 'rising'
assert events.findAll { it.name == 'pushed' }[-1].value == 2
assert readings.notificationType == 'warning'
assert readings.notificationText.contains('7 ft MLLW')

clock += 1800000L
driver.refresh()
complete(8.0d, 7.8d, tidesBody(5.2d))
assert readings.tideState == 'critical'
assert events.findAll { it.name == 'pushed' }[-1].value == 2
assert readings.notificationType == 'critical'

clock += 1800000L
driver.refresh()
complete(4.2d, 4.0d, tidesBody(5.0d))
assert readings.tideState == 'normal'
assert readings.waterLevelTrend == 'falling'
assert events.findAll { it.name == 'pushed' }[-1].value == 1
assert readings.notificationType == 'recovery'
println 'PASS: watch hierarchy, rising/falling trends, worsening alerts and recovery'

clock += 1800000L
driver.refresh()
complete(5.0d, 3.4d, tidesBody(5.2d))
assert readings.residualState == 'above'
assert events.findAll { it.name == 'pushed' }[-1].value == 3
assert readings.notificationType == 'residual'

clock += 1800000L
driver.refresh()
complete(5.1d, 5.0d, tidesBody(6.3d, clock + 3 * 3600000L))
assert events.findAll { it.name == 'pushed' }[-1].value == 1
assert readings.notificationType == 'highTide'
assert readings.forecastHighestTide.toString() == '6.300'
assert readings.tideForecast.contains('High 6.3 ft')
println 'PASS: observed-minus-predicted residual and upcoming high-tide events'

clock += 3 * 3600000L
driver.refresh()
complete(4.0d, 3.8d, tidesBody(5.0d), [observedTime:clock - 2 * 3600000L])
assert readings.tideState == 'stale'
assert readings.dataAgeMinutes == 120L
assert events.findAll { it.name == 'pushed' }[-1].value == 3
assert readings.notificationType == 'stale'
println 'PASS: stale observation age and precedence'

driver.testHighTideEvent()
assert events.findAll { it.name == 'pushed' }[-1].value == 1
driver.testWaterLevelEvent()
assert events.findAll { it.name == 'pushed' }[-1].value == 2
driver.testResidualEvent()
assert events.findAll { it.name == 'pushed' }[-1].value == 3
assert readings.notificationText.startsWith('TEST:')

clock += 1800000L
driver.refresh()
complete(4.1d, 4.0d, tidesBody(5.0d), [currentFailure:503])
assert readings.watchStatus == 'partial'
assert readings.lastError.contains('HTTP 503')
assert readings.predictedWaterLevel == 0

int requestCount = requests.size()
def staleCallback = requests[-1].data
driver.highLowResponse(ok(tidesBody()), staleCallback)
assert requests.size() == requestCount

settings.watchLevel = 7d
settings.warningLevel = 6d
clock += 1800000L
driver.initialize()
assert readings.watchStatus == 'error'
assert readings.lastError.contains('must increase')
settings.watchLevel = 5.5d
settings.warningLevel = 6.5d
settings.stationId = 'bad station!'
driver.initialize()
assert readings.watchStatus == 'error'
assert readings.lastError.contains('5 to 12')

def restored = new JsonSlurper().parseText(JsonOutput.toJson(state))
assert restored.previous instanceof Map
assert JsonOutput.toJson(state).length() < 20000
println 'PASS: test events, partial prediction failure, stale callbacks, validation and JSON-safe state'
println 'ALL CHECKS PASSED'
