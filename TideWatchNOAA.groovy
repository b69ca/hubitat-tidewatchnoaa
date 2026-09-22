import groovy.transform.Field

/*
 * Tide Watch (NOAA)
 *
 * Monitors observed water level and tide predictions for a NOAA CO-OPS
 * station. Exposes high/low tides, trends, residuals and threshold events to
 * Hubitat without an API key or companion service.
 *
 * Author: Jon Wallace
 * Copyright 2026 Jon Wallace
 * License: MIT
 * Version: 1.0.0
 */

metadata {
  definition(
    name: "Tide Watch (NOAA)",
    namespace: "jonw",
    author: "Jon Wallace",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/b69ca/hubitat-tidewatchnoaa/main/TideWatchNOAA.groovy"
  ) {
    capability "Sensor"
    capability "Refresh"
    capability "Initialize"
    capability "PushableButton"

    command "clearHistory"
    command "testHighTideEvent"
    command "testWaterLevelEvent"
    command "testResidualEvent"

    attribute "watchStatus", "enum", ["setup", "updating", "ready", "partial", "error"]
    attribute "tideState", "enum", ["unknown", "normal", "watch", "warning", "critical", "stale"]
    attribute "tidePhase", "enum", ["rising", "falling", "nearHigh", "nearLow", "unknown"]
    attribute "lastChecked", "string"
    attribute "lastSuccessfulCheck", "string"
    attribute "lastError", "string"
    attribute "dataAgeMinutes", "number"

    attribute "stationId", "string"
    attribute "stationName", "string"
    attribute "stationState", "string"
    attribute "stationLatitude", "number"
    attribute "stationLongitude", "number"
    attribute "stationTidal", "enum", ["yes", "no", "unknown"]
    attribute "stationUrl", "string"

    attribute "waterLevel", "number"
    attribute "waterLevelUnit", "string"
    attribute "waterLevelDatum", "string"
    attribute "waterLevelTime", "string"
    attribute "waterLevelEpoch", "number"
    attribute "waterLevelQuality", "string"
    attribute "waterLevelFlags", "string"
    attribute "waterLevelTrend", "enum", ["rising", "steady", "falling", "unknown"]
    attribute "waterLevelChange", "number"
    attribute "waterLevelChangePerHour", "number"

    attribute "predictedWaterLevel", "number"
    attribute "predictionTime", "string"
    attribute "waterLevelResidual", "number"
    attribute "residualState", "enum", ["above", "near", "below", "unknown"]

    attribute "nextHighTide", "number"
    attribute "nextHighTideTime", "string"
    attribute "nextHighTideEpoch", "number"
    attribute "nextLowTide", "number"
    attribute "nextLowTideTime", "string"
    attribute "nextLowTideEpoch", "number"
    attribute "forecastHighestTide", "number"
    attribute "forecastHighestTideTime", "string"
    attribute "forecastLowestTide", "number"
    attribute "forecastLowestTideTime", "string"
    attribute "tideForecast", "string"

    attribute "conditionSummary", "string"
    attribute "notificationText", "string"
    attribute "notificationType", "enum", ["highTide", "watch", "warning", "critical", "residual", "stale", "recovery", "test"]
  }

  preferences {
    input name: "setupNotes", type: "paragraph", title: "Tide Watch 1.0.0",
      description: "Enter a NOAA CO-OPS station ID such as 9414290. Observations, predictions and public station metadata come from tidesandcurrents.noaa.gov. No API key or location is required."
    input name: "stationId", type: "text", title: "NOAA CO-OPS station ID", required: true
    input name: "units", type: "enum", title: "Water-level units", options: ["english":"Feet", "metric":"Meters"], defaultValue: "english"
    input name: "datum", type: "enum", title: "Vertical datum", options: [
      "MLLW":"Mean Lower Low Water (MLLW)", "MLW":"Mean Low Water (MLW)", "MSL":"Mean Sea Level (MSL)",
      "MTL":"Mean Tide Level (MTL)", "MHW":"Mean High Water (MHW)", "MHHW":"Mean Higher High Water (MHHW)",
      "NAVD":"North American Vertical Datum (NAVD)"], defaultValue: "MLLW"
    input name: "pollMinutes", type: "enum", title: "Automatic check interval", options: [
      "15":"15 minutes", "30":"30 minutes", "60":"1 hour"], defaultValue: "30"
    input name: "forecastHours", type: "enum", title: "High/low forecast window", options: [
      "24":"24 hours", "48":"48 hours", "72":"72 hours"], defaultValue: "48"
    input name: "staleMinutes", type: "enum", title: "Treat observations as stale after", options: [
      "30":"30 minutes", "60":"1 hour", "120":"2 hours", "180":"3 hours"], defaultValue: "60"

    input name: "levelNotes", type: "paragraph", title: "Observed water-level thresholds",
      description: "Optional local thresholds use the selected datum and unit. Confirm them with NOAA and local authorities; they are not automatically flood stages."
    input name: "watchLevel", type: "decimal", title: "Watch level (optional)"
    input name: "warningLevel", type: "decimal", title: "Warning level (optional)"
    input name: "criticalLevel", type: "decimal", title: "Critical level (optional)"
    input name: "notifyRecovery", type: "bool", title: "Notify when observed level returns below watch", defaultValue: true

    input name: "predictionNotes", type: "paragraph", title: "Prediction and residual events",
      description: "Button 1 can announce an upcoming high tide. Button 3 can report an observed-minus-predicted residual or stale observation."
    input name: "highTideThreshold", type: "decimal", title: "Upcoming high-tide threshold (optional)"
    input name: "highTideLeadHours", type: "enum", title: "Announce qualifying high tides within", options: [
      "3":"3 hours", "6":"6 hours", "12":"12 hours", "24":"24 hours"], defaultValue: "6"
    input name: "residualThreshold", type: "decimal", title: "Positive residual threshold (optional)", defaultValue: 1.0
    input name: "notifyResidual", type: "bool", title: "Notify when observed water exceeds prediction by the residual threshold", defaultValue: true
    input name: "notifyStale", type: "bool", title: "Notify when observation first becomes stale", defaultValue: true

    input name: "automationNotes", type: "paragraph", title: "Automation behavior",
      description: "Button 1: upcoming high tide or recovery. Button 2: observed watch/warning/critical threshold. Button 3: positive residual or stale data. Observed warning/critical events take precedence."
    input name: "notifyInitialState", type: "bool", title: "Notify for the most important condition on the first successful update", defaultValue: false
    input name: "notifyOnlyOnChange", type: "bool", title: "Notify only when a condition is new or worsens", defaultValue: true
    input name: "historySize", type: "number", title: "Observed samples retained for trends", range: "2..48", defaultValue: 24
    input name: "logEnable", type: "bool", title: "Debug logging (automatically disables after 30 minutes)", defaultValue: false
  }
}

@Field static final String DATA_URL = "https://api.tidesandcurrents.noaa.gov/api/prod/datagetter"
@Field static final String META_ROOT = "https://api.tidesandcurrents.noaa.gov/mdapi/prod/webapi/stations"

def installed() {
  unschedule()
  state.generation = (state.generation ?: 0L) + 1L
  state.inFlight = null
  state.configured = false
  sendEvent(name:"numberOfButtons", value:3)
  sendChanged("watchStatus", "setup")
}

def updated() {
  state.configured = true
  initialize()
}

def uninstalled() {
  unschedule()
}

def initialize() {
  unschedule()
  state.configured = true
  state.generation = (state.generation ?: 0L) + 1L
  state.inFlight = null
  sendEvent(name:"numberOfButtons", value:3)
  sendChanged("watchStatus", "updating")
  configureSchedule()
  if (settings?.logEnable) runIn(1800, "logsOff")
  refresh()
}

def configureSchedule() {
  int minutes = settingNumber("pollMinutes", 30d, 15d, 60d).intValue()
  if (minutes == 60) runEvery1Hour("refresh")
  else schedule("0 */${minutes} * ? * *", "refresh")
}

def logsOff() {
  device.updateSetting("logEnable", [value:"false", type:"bool"])
}

def refresh() {
  if (state.configured != true) {
    sendChanged("watchStatus", "setup")
    return
  }
  if (state.inFlight) {
    if (now() - ((state.requestStarted ?: 0L) as Long) < 150000L) return
    state.inFlight = null
    recordError("The previous NOAA request did not complete")
  }
  try {
    String station = normalizedStation()
    String signature = "${station}|${selectedUnits()}|${selectedDatum()}"
    if (state.signature != signature) {
      state.signature = signature
      state.stationId = station
      state.stationMetadata = null
      state.samples = []
      state.previous = [:]
      state.hasBaseline = false
    }
    validateThresholds()
    long started = now()
    String token = "${started}-${state.generation}"
    state.inFlight = token
    state.requestStarted = started
    state.batch = [token:token, observed:null, currentPrediction:null, tides:[], errors:[]]
    sendChanged("lastChecked", localText(started))
    sendChanged("watchStatus", "updating")
    if (settings?.logEnable) log.debug "Checking NOAA CO-OPS station ${station}"
    if (state.stationMetadata instanceof Map) requestObserved(token)
    else requestMetadata(token)
  } catch (Exception e) {
    state.inFlight = null
    recordError("Configuration/request: ${e.message}")
  }
}

private void requestMetadata(String token) {
  request("metadataResponse", "${META_ROOT}/${state.stationId}.json", [:], token)
}

private void requestObserved(String token) {
  request("observedResponse", DATA_URL, baseQuery() + [product:"water_level", date:"latest"], token)
}

private void requestCurrentPrediction(String token) {
  request("currentPredictionResponse", DATA_URL, baseQuery() + [product:"predictions", date:"latest"], token)
}

private void requestHighLow(String token) {
  request("highLowResponse", DATA_URL, baseQuery() + [product:"predictions", date:"today",
    range:settingNumber("forecastHours", 48d, 24d, 72d).intValue().toString(), interval:"hilo"], token)
}

private Map baseQuery() {
  return [application:"Hubitat_TideWatch", station:state.stationId, time_zone:"gmt", units:selectedUnits(),
          datum:selectedDatum(), format:"json"]
}

private void request(String callback, String uri, Map query, String token) {
  try {
    Map params = [uri:uri, contentType:"application/json", timeout:20,
      headers:["User-Agent":"Hubitat-TideWatch/1.0 (github.com/b69ca/hubitat-tidewatchnoaa)"]]
    if (query) params.query = query
    asynchttpGet(callback, params, [token:token, station:state.stationId])
  } catch (Exception e) {
    state.inFlight = null
    recordError("NOAA request: ${e.message}")
  }
}

def metadataResponse(resp, data) {
  if (!validCallback(data)) return
  try {
    Map body = responseMap(resp, "station metadata")
    List stations = body.stations instanceof List ? body.stations : []
    if (!stations) throw new IllegalArgumentException("Station ${data.station} was not found")
    Map station = stations[0] instanceof Map ? stations[0] : [:]
    state.stationMetadata = [name:safeText(station.name, 160), state:safeText(station.state, 40),
      latitude:finiteNumber(station.lat), longitude:finiteNumber(station.lng), tidal:station.tidal]
  } catch (Exception e) {
    state.batch.errors << "Station metadata: ${safeText(e.message, 300)}"
  }
  requestObserved(data.token)
}

def observedResponse(resp, data) {
  if (!validCallback(data)) return
  try {
    Map body = responseMap(resp, "water level")
    if (body.error instanceof Map) throw new IllegalArgumentException(safeText(body.error.message, 300))
    List rows = body.data instanceof List ? body.data : []
    List parsed = rows.collect { parseObserved(it) }.findAll { it }
    if (!parsed) throw new IllegalArgumentException("No current water-level observation was returned")
    state.batch.observed = parsed.max { it.time }
  } catch (Exception e) {
    state.batch.errors << "Water level: ${safeText(e.message, 300)}"
  }
  requestCurrentPrediction(data.token)
}

def currentPredictionResponse(resp, data) {
  if (!validCallback(data)) return
  try {
    Map body = responseMap(resp, "current prediction")
    if (body.error instanceof Map) throw new IllegalArgumentException(safeText(body.error.message, 300))
    List rows = body.predictions instanceof List ? body.predictions : []
    List parsed = rows.collect { parsePrediction(it) }.findAll { it }
    if (parsed) state.batch.currentPrediction = parsed.max { it.time }
  } catch (Exception e) {
    state.batch.errors << "Current prediction: ${safeText(e.message, 300)}"
  }
  requestHighLow(data.token)
}

def highLowResponse(resp, data) {
  if (!validCallback(data)) return
  try {
    Map body = responseMap(resp, "high/low predictions")
    if (body.error instanceof Map) throw new IllegalArgumentException(safeText(body.error.message, 300))
    List rows = body.predictions instanceof List ? body.predictions : []
    state.batch.tides = rows.collect { parsePrediction(it) }.findAll { it && it.type in ["H", "L"] }
  } catch (Exception e) {
    state.batch.errors << "High/low predictions: ${safeText(e.message, 300)}"
  }
  finishBatch(data.token)
}

private void finishBatch(String token) {
  if (token != state.inFlight || token != state.batch?.token) return
  Map batch = state.batch
  Map observed = batch.observed instanceof Map ? batch.observed : null
  Map predicted = batch.currentPrediction instanceof Map ? batch.currentPrediction : null
  List tides = batch.tides instanceof List ? batch.tides : []
  List errors = batch.errors instanceof List ? batch.errors : []
  state.inFlight = null
  state.batch = null
  if (!observed) {
    recordError(errors ? errors.join("; ") : "NOAA returned no usable water-level observation")
    return
  }

  boolean first = state.hasBaseline != true
  Map details = evaluate(observed, predicted, tides)
  Map event = chooseEvent(details, state.previous instanceof Map ? state.previous : [:], first)
  publishStation()
  publishObserved(observed, details)
  publishPredictions(predicted, tides, details)
  state.previous = [levelState:details.levelState, residualAlert:details.residualAlert, stale:details.stale,
                    highEventId:details.highEventId, anyAlert:details.anyAlert]
  state.hasBaseline = true
  if (event) emitAutomationEvent(event)
  sendChanged("lastSuccessfulCheck", localText(now()))
  sendChanged("lastError", errors ? errors.join("; ").take(500) : "")
  sendChanged("watchStatus", errors ? "partial" : "ready")
}

private Map evaluate(Map observed, Map predicted, List tides) {
  long ageMinutes = Math.max(0L, now() - (observed.time as Long)).intdiv(60000L)
  boolean stale = ageMinutes > settingNumber("staleMinutes", 60d, 15d, 720d)
  String levelState = stale ? "stale" : classifyLevel(observed.value)
  Double residual = predicted ? (observed.value as Double) - (predicted.value as Double) : null
  Double residualThreshold = optionalNumber(settings?.residualThreshold)
  boolean residualAlert = settings?.notifyResidual != false && residual != null && residualThreshold != null && residual >= residualThreshold

  List future = tides.findAll { it.time >= now() }.sort { it.time }
  Map nextHigh = future.find { it.type == "H" }
  Map nextLow = future.find { it.type == "L" }
  List highs = future.findAll { it.type == "H" }
  List lows = future.findAll { it.type == "L" }
  Map highest = highs ? highs.max { it.value } : null
  Map lowest = lows ? lows.min { it.value } : null
  Double highThreshold = optionalNumber(settings?.highTideThreshold)
  long lead = settingNumber("highTideLeadHours", 6d, 1d, 24d).longValue() * 3600000L
  boolean highAlert = nextHigh && highThreshold != null && nextHigh.value >= highThreshold && nextHigh.time <= now() + lead
  String highId = nextHigh ? "${nextHigh.time}|${nextHigh.value}" : ""

  List samples = state.samples instanceof List ? state.samples : []
  Map previousSample = samples ? samples[-1] : [:]
  Map trend = trend(observed.value, observed.time, previousSample.value, previousSample.time)
  int maximum = settingNumber("historySize", 24d, 2d, 48d).intValue()
  state.samples = (samples + [[value:observed.value, time:observed.time, captured:now()]]).takeRight(maximum)

  String phase = tidePhase(future, observed, trend)
  boolean anyAlert = stale || stateRank(levelState) >= stateRank("watch") || residualAlert || highAlert
  return [observedValue:observed.value, ageMinutes:ageMinutes, stale:stale, levelState:levelState, residual:residual, residualAlert:residualAlert,
          nextHigh:nextHigh, nextLow:nextLow, highest:highest, lowest:lowest, highAlert:highAlert,
          highEventId:highId, trend:trend, phase:phase, anyAlert:anyAlert]
}

private Map chooseEvent(Map current, Map previous, boolean first) {
  if (first && settings?.notifyInitialState != true) return null
  boolean changedOnly = settings?.notifyOnlyOnChange != false
  int rank = stateRank(current.levelState)
  int oldRank = stateRank(previous.levelState ?: "unknown")
  if (current.levelState in ["warning", "critical"] && (!changedOnly || rank > oldRank)) {
    return [button:2, type:current.levelState, text:"NOAA water-level ${current.levelState} at ${stationLabel()}: ${levelText(current.observedValue)}."]
  }
  if (current.stale && settings?.notifyStale != false && (!changedOnly || previous.stale != true)) {
    return [button:3, type:"stale", text:"NOAA water-level observation is stale at ${stationLabel()} (${current.ageMinutes} minutes old)."]
  }
  if (current.residualAlert && (!changedOnly || previous.residualAlert != true)) {
    return [button:3, type:"residual", text:"Observed water at ${stationLabel()} is ${formatNumber(current.residual)} ${unitAbbreviation()} above the harmonic prediction."]
  }
  if (current.levelState == "watch" && (!changedOnly || oldRank < stateRank("watch"))) {
    return [button:2, type:"watch", text:"NOAA water-level watch threshold reached at ${stationLabel()}."]
  }
  if (current.highAlert && (!changedOnly || previous.highEventId != current.highEventId)) {
    return [button:1, type:"highTide", text:"High tide of ${levelText(current.nextHigh.value)} predicted at ${stationLabel()} for ${localText(current.nextHigh.time as Long)}."]
  }
  if (settings?.notifyRecovery != false && current.anyAlert == false && previous.anyAlert == true) {
    return [button:1, type:"recovery", text:"Monitored tide conditions returned below configured thresholds at ${stationLabel()}."]
  }
  return null
}

private void publishStation() {
  Map station = state.stationMetadata instanceof Map ? state.stationMetadata : [:]
  sendChanged("stationId", state.stationId)
  sendChanged("stationName", station.name ?: "NOAA station ${state.stationId}")
  sendChanged("stationState", station.state ?: "")
  sendChanged("stationLatitude", station.latitude == null ? 0 : rounded(station.latitude as Double, 4))
  sendChanged("stationLongitude", station.longitude == null ? 0 : rounded(station.longitude as Double, 4))
  sendChanged("stationTidal", station.tidal == null ? "unknown" : station.tidal == true ? "yes" : "no")
  sendChanged("stationUrl", "https://tidesandcurrents.noaa.gov/stationhome.html?id=${state.stationId}")
}

private void publishObserved(Map observed, Map details) {
  sendChanged("waterLevel", rounded(observed.value as Double, 3))
  sendChanged("waterLevelUnit", unitAbbreviation())
  sendChanged("waterLevelDatum", selectedDatum())
  sendChanged("waterLevelTime", localText(observed.time as Long))
  sendChanged("waterLevelEpoch", (observed.time as Long).intdiv(1000L))
  sendChanged("waterLevelQuality", observed.quality)
  sendChanged("waterLevelFlags", observed.flags)
  sendChanged("waterLevelTrend", details.trend.direction)
  sendChanged("waterLevelChange", details.trend.delta == null ? 0 : rounded(details.trend.delta as Double, 3))
  sendChanged("waterLevelChangePerHour", details.trend.perHour == null ? 0 : rounded(details.trend.perHour as Double, 3))
  sendChanged("dataAgeMinutes", details.ageMinutes)
  sendChanged("tideState", details.levelState)
  sendChanged("tidePhase", details.phase)
}

private void publishPredictions(Map predicted, List tides, Map details) {
  sendChanged("predictedWaterLevel", predicted ? rounded(predicted.value as Double, 3) : 0)
  sendChanged("predictionTime", predicted ? localText(predicted.time as Long) : "")
  sendChanged("waterLevelResidual", details.residual == null ? 0 : rounded(details.residual as Double, 3))
  String residualState = details.residual == null ? "unknown" : Math.abs(details.residual as Double) < 0.1d ? "near" : details.residual > 0d ? "above" : "below"
  sendChanged("residualState", residualState)
  publishTide("High", details.nextHigh)
  publishTide("Low", details.nextLow)
  sendChanged("forecastHighestTide", details.highest ? rounded(details.highest.value as Double, 3) : 0)
  sendChanged("forecastHighestTideTime", details.highest ? localText(details.highest.time as Long) : "")
  sendChanged("forecastLowestTide", details.lowest ? rounded(details.lowest.value as Double, 3) : 0)
  sendChanged("forecastLowestTideTime", details.lowest ? localText(details.lowest.time as Long) : "")

  List future = tides.findAll { it.time >= now() }.sort { it.time }.take(8)
  String forecast = future.collect { "${it.type == 'H' ? 'High' : 'Low'} ${formatNumber(it.value)} ${unitAbbreviation()} ${localText(it.time as Long)}" }.join("\n")
  sendChanged("tideForecast", forecast.take(1000))
  String summary = "${stationLabel()}: ${formatNumber(details.observedValue)} ${unitAbbreviation()} ${details.phase}"
  if (details.nextHigh) summary += "; next high ${formatNumber(details.nextHigh.value)} at ${localText(details.nextHigh.time as Long)}"
  if (details.residual != null) summary += "; residual ${formatNumber(details.residual)}"
  sendChanged("conditionSummary", summary.take(500))
}

private void publishTide(String kind, Map tide) {
  String prefix = "next${kind}Tide"
  sendChanged(prefix, tide ? rounded(tide.value as Double, 3) : 0)
  sendChanged("${prefix}Time", tide ? localText(tide.time as Long) : "")
  sendChanged("${prefix}Epoch", tide ? (tide.time as Long).intdiv(1000L) : 0)
}

private Map parseObserved(def raw) {
  if (!(raw instanceof Map)) return null
  Double value = finiteNumber(raw.v)
  Long time = parseNoaaTime(raw.t)
  if (value == null || time == null) return null
  return [value:value, time:time, quality:safeText(raw.q, 30), flags:safeText(raw.f, 80)]
}

private Map parsePrediction(def raw) {
  if (!(raw instanceof Map)) return null
  Double value = finiteNumber(raw.v)
  Long time = parseNoaaTime(raw.t)
  if (value == null || time == null) return null
  return [value:value, time:time, type:safeText(raw.type, 2).toUpperCase()]
}

private Map trend(def currentRaw, def currentTimeRaw, def oldRaw, def oldTimeRaw) {
  Double current = finiteNumber(currentRaw)
  Double old = finiteNumber(oldRaw)
  Long currentTime = currentTimeRaw == null ? null : (currentTimeRaw as Long)
  Long oldTime = oldTimeRaw == null ? null : (oldTimeRaw as Long)
  if (current == null || old == null || currentTime == null || oldTime == null || currentTime <= oldTime) {
    return [direction:"unknown", delta:null, perHour:null]
  }
  double delta = current - old
  double hours = (currentTime - oldTime) / 3600000d
  String direction = Math.abs(delta) < 0.01d ? "steady" : delta > 0d ? "rising" : "falling"
  return [direction:direction, delta:delta, perHour:delta / hours]
}

private String tidePhase(List future, Map observed, Map trend) {
  Map next = future ? future[0] : null
  if (next && next.time - now() <= 1800000L) return next.type == "H" ? "nearHigh" : "nearLow"
  if (trend.direction in ["rising", "falling"]) return trend.direction
  if (next) return next.type == "H" ? "rising" : "falling"
  return "unknown"
}

private String classifyLevel(def raw) {
  Double value = finiteNumber(raw)
  if (value == null) return "unknown"
  Double critical = optionalNumber(settings?.criticalLevel)
  Double warning = optionalNumber(settings?.warningLevel)
  Double watch = optionalNumber(settings?.watchLevel)
  if (critical != null && value >= critical) return "critical"
  if (warning != null && value >= warning) return "warning"
  if (watch != null && value >= watch) return "watch"
  return "normal"
}

private void validateThresholds() {
  List values = [optionalNumber(settings?.watchLevel), optionalNumber(settings?.warningLevel), optionalNumber(settings?.criticalLevel)].findAll { it != null }
  for (int i = 1; i < values.size(); i++) if (values[i] <= values[i - 1]) {
    throw new IllegalArgumentException("Water-level thresholds must increase from watch to warning to critical")
  }
  Double residual = optionalNumber(settings?.residualThreshold)
  if (residual != null && residual <= 0d) throw new IllegalArgumentException("Residual threshold must be greater than zero")
}

private String normalizedStation() {
  String value = settings?.stationId?.toString()?.trim()?.toUpperCase()
  if (!(value ==~ /[A-Z0-9]{5,12}/)) throw new IllegalArgumentException("NOAA station ID must contain 5 to 12 letters or digits")
  return value
}

private String selectedUnits() {
  return settings?.units == "metric" ? "metric" : "english"
}

private String selectedDatum() {
  String value = settings?.datum?.toString()?.toUpperCase()
  return value in ["MLLW", "MLW", "MSL", "MTL", "MHW", "MHHW", "NAVD"] ? value : "MLLW"
}

private String unitAbbreviation() {
  return selectedUnits() == "metric" ? "m" : "ft"
}

private Map responseMap(resp, String label) {
  if (resp == null || resp.hasError() || resp.status != 200) throw new IllegalArgumentException("${label} request failed (HTTP ${resp?.status ?: 'unknown'})")
  def body = resp.getJson()
  if (!(body instanceof Map)) throw new IllegalArgumentException("Unexpected ${label} response")
  return body as Map
}

private boolean validCallback(def data) {
  return data?.token && data.token == state.inFlight && data.token == state.batch?.token && data.station == state.stationId
}

private int stateRank(String value) {
  return [unknown:0, normal:1, stale:2, watch:3, warning:4, critical:5][value] ?: 0
}

private String stationLabel() {
  return state.stationMetadata?.name ?: "NOAA station ${state.stationId}"
}

private String levelText(def value) {
  return "${formatNumber(value)} ${unitAbbreviation()} ${selectedDatum()}"
}

private Long parseNoaaTime(def raw) {
  String value = raw?.toString()?.trim()
  if (!value) return null
  try { return Date.parse("yyyy-MM-dd HH:mm", value, TimeZone.getTimeZone("UTC")).time }
  catch (Exception ignored) { return null }
}

private void emitAutomationEvent(Map event) {
  String text = safeText(event.text, 500)
  sendEvent(name:"pushed", value:event.button, isStateChange:true, descriptionText:text)
  sendEvent(name:"notificationText", value:text, isStateChange:true)
  sendEvent(name:"notificationType", value:event.type, isStateChange:true)
}

def clearHistory() {
  state.generation = (state.generation ?: 0L) + 1L
  state.inFlight = null
  state.batch = null
  state.samples = []
  state.previous = [:]
  state.hasBaseline = false
  refresh()
}

def testHighTideEvent() {
  emitAutomationEvent([button:1, type:"test", text:"TEST: Upcoming high-tide event. No real prediction is implied."])
}

def testWaterLevelEvent() {
  emitAutomationEvent([button:2, type:"test", text:"TEST: Observed water-level threshold event. No real condition is implied."])
}

def testResidualEvent() {
  emitAutomationEvent([button:3, type:"test", text:"TEST: Residual or stale-observation event. No real condition is implied."])
}

private Double settingNumber(String name, double fallback, double minimum, double maximum) {
  Double value = finiteNumber(settings?.get(name))
  if (value == null) value = fallback
  return Math.max(minimum, Math.min(maximum, value))
}

private Double optionalNumber(def raw) {
  return finiteNumber(raw)
}

private Double finiteNumber(def raw) {
  try {
    if (raw == null || raw.toString().trim() == "") return null
    double value = raw as Double
    return Double.isNaN(value) || Double.isInfinite(value) ? null : value
  } catch (Exception ignored) { return null }
}

private String safeText(def raw, int maximum) {
  if (raw == null) return ""
  return raw.toString().replaceAll(/[\u0000-\u001F\u007F]/, " ").replaceAll(/\s+/, " ").trim().take(maximum)
}

private String formatNumber(def raw) {
  Double value = finiteNumber(raw)
  return value == null ? "unavailable" : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

private def rounded(double value, int places) {
  return new BigDecimal(Double.toString(value)).setScale(places, BigDecimal.ROUND_HALF_UP)
}

private TimeZone hubTimezone() {
  return location?.timeZone ?: TimeZone.getTimeZone("UTC")
}

private String localText(long time) {
  return new Date(time).format("yyyy-MM-dd HH:mm:ss z", hubTimezone())
}

private def sendChanged(String name, value) {
  if (device.currentValue(name)?.toString() != value?.toString()) sendEvent(name:name, value:value)
}

private void recordError(String message) {
  sendChanged("watchStatus", "error")
  sendEvent(name:"lastError", value:safeText(message, 500))
  log.warn "Tide Watch: ${message}"
}
