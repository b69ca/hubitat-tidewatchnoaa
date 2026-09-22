# Tide Watch (NOAA)

A Hubitat driver that monitors observed water level and astronomical tide predictions for one NOAA CO-OPS station. It publishes the next high and low tides, water-level trend, observed-minus-predicted residual, configurable thresholds and button events for Rule Machine.

There is no account, API key, subscription, home-location request, companion server or extra Hubitat library. NOAA's public CO-OPS services provide the data.

## Features

- Uses any compatible NOAA CO-OPS water-level station ID, such as `9414290` for San Francisco.
- Reports the latest observed level with timestamp, datum, units, quality and flags.
- Finds the next high and low tides plus forecast-window extrema.
- Calculates water-level trend and change per hour from local history.
- Compares the observation with the harmonic prediction to expose the residual.
- Supports watch, warning and critical observed-level thresholds.
- Supports advance notice for a qualifying predicted high tide.
- Detects stale observations and optional recovery to normal conditions.
- Keeps observed data usable if metadata or prediction retrieval temporarily fails.
- Emits three pushable-button events with readable notification text.

Tide Watch is for home automation and situational awareness. Its configurable levels are local values relative to the chosen datum; they are not automatically flood stages, warnings or evacuation criteria. Use official NOAA/NWS products and local authorities for safety decisions.

## Install

1. In Hubitat, open **Drivers Code → New Driver**.
2. Import the [raw driver](https://raw.githubusercontent.com/b69ca/hubitat-tidewatchnoaa/main/TideWatchNOAA.groovy) and click **Save**.
3. Open **Devices → Add Device → Virtual**.
4. Select **Tide Watch (NOAA)** and name the device.
5. Enter a NOAA CO-OPS station ID and choose the datum and units.
6. Review thresholds, then click **Save Preferences**.

The device stays in `setup` and sends no network request until preferences are saved or **Initialize** is pressed. By default, its first successful update establishes a quiet baseline.

Find a station with NOAA's [Tides & Currents station map](https://tidesandcurrents.noaa.gov/map/). Select a station that provides both water-level observations and tide predictions for the full feature set.

## Preferences

| Preference | Default | Purpose |
| --- | --- | --- |
| Station ID | Required | NOAA CO-OPS station identifier. |
| Units | Feet | Feet or meters for observations, predictions and thresholds. |
| Vertical datum | MLLW | Reference surface for all water-level values. |
| Check interval | 30 minutes | 15, 30 or 60 minutes. |
| Forecast window | 48 hours | 24, 48 or 72 hours of high/low predictions. |
| Stale after | 60 minutes | Marks an old observation as stale. |
| Watch / warning / critical | Empty | Optional increasing observed-level thresholds. |
| Notify recovery | On | Button 1 when all monitored conditions clear. |
| High-tide threshold | Empty | Optional predicted high-tide level for button 1. |
| High-tide lead | 6 hours | How far ahead a qualifying high tide may trigger. |
| Residual threshold | 1.0 | Observed minus predicted amount for button 3. |
| Notify residual / stale | On | Enables those button 3 conditions. |
| Notify initial state | Off | Optionally announces a qualifying first update. |
| Notify only on change | On | Suppresses repeats until a condition is new or worse. |
| History size | 24 | Samples retained in hub state for trend calculations. |

### Choosing a datum

The datum defines zero. `MLLW` is common for tide predictions in the United States, while other applications may use `MHHW`, `MSL` or `NAVD`. Thresholds copied from another source are meaningful only when the source uses the same station, datum and units. Not every datum is supported at every station; NOAA will return an error if a selected combination is unavailable.

## Automation buttons

| Button | Event |
| --- | --- |
| 1 | Qualifying upcoming high tide, or optional recovery. |
| 2 | Observed watch, warning or critical threshold. |
| 3 | Positive residual threshold, or stale observation. |

One button event is emitted per update. Warning and critical water levels take precedence, followed by stale data, residual, watch, high tide and recovery. `notificationText` contains a ready-to-send message and `notificationType` identifies the event.

In Rule Machine, trigger on **Tide Watch button pushed** and use the device's `notificationText` attribute in a notification action. The three test commands emit clearly marked `TEST:` events without changing measured conditions or trend history.

**Clear History** discards trend history, invalidates any outstanding request and makes the next successful update a new baseline.

## Key attributes

| Attribute | Meaning |
| --- | --- |
| `watchStatus` | `setup`, `updating`, `ready`, `partial` or `error`. |
| `tideState` | `normal`, `watch`, `warning`, `critical`, `stale` or `unknown`. |
| `tidePhase` | `rising`, `falling`, `nearHigh`, `nearLow` or `unknown`. |
| `waterLevel`, `waterLevelUnit`, `waterLevelDatum` | Latest observation and its reference. |
| `waterLevelTime`, `dataAgeMinutes`, `waterLevelQuality`, `waterLevelFlags` | Timing and NOAA quality information. |
| `waterLevelTrend`, `waterLevelChange`, `waterLevelChangePerHour` | Change since the preceding retained observation. |
| `predictedWaterLevel`, `waterLevelResidual`, `residualState` | Harmonic prediction and observed-minus-predicted difference. |
| `nextHighTide`, `nextHighTideTime` | Next forecast high. |
| `nextLowTide`, `nextLowTideTime` | Next forecast low. |
| `forecastHighestTide`, `forecastLowestTide` | Extrema in the selected forecast window. |
| `tideForecast` | Up to eight future high/low entries, one per line. |
| `stationName`, `stationState`, `stationLatitude`, `stationLongitude` | Public NOAA station metadata. |
| `conditionSummary` | Compact current condition and next-high summary. |

Epoch attributes are also provided for water-level time and the next high/low tide, which simplifies comparisons in Rule Machine.

## How calculations work

NOAA observations and predictions are requested in GMT and converted to the hub's configured time zone for display. The driver chooses the newest returned observation and current prediction. The residual is:

```text
observed water level - harmonic predicted water level
```

A positive residual means the measured water is above the astronomical prediction. Weather, river flow, atmospheric pressure and measurement timing can affect it. A residual threshold is independent of the observed-level watch/warning/critical thresholds.

Trend uses the two most recent observations retained by the driver. A change smaller than `0.01` selected units is reported as steady. When there is no usable prior sample, the next predicted high or low provides a best-effort tide phase.

## Network and privacy

The driver makes HTTPS requests to NOAA's public CO-OPS Metadata API and Data API. Requests contain the public station ID, selected datum, units, time zone and requested product. They do not contain a home address, hub identifier, Hubitat device name, API key or automation data. NOAA receives the hub's public IP address and driver user-agent during normal HTTPS access.

A full update uses station metadata once per configuration, then water level, current harmonic prediction and high/low predictions. Metadata is cached until the station, units or datum changes.

## Troubleshooting

- `watchStatus = error`: the current observation could not be used. Confirm the station ID, datum support and hub internet access.
- `watchStatus = partial`: the observation succeeded but station metadata or one of the prediction requests failed.
- No tide predictions: some stations are water-level-only. Choose a tide prediction station for high/low and residual features.
- No first automation event: the first successful update is a silent baseline unless **Notify initial state** is enabled.
- Values differ from another source: verify station, datum, units, time zone and observation time.
- Threshold error: configured watch, warning and critical values must increase in that order.

## Development

Run the simulated Hubitat suite with Groovy 2.4.21:

```sh
groovy tests/TideWatchNOAATest.groovy
```

Tests cover setup gating, request construction, station metadata, observations, predictions, trend calculations, threshold precedence, residual/high-tide/stale/recovery events, partial failures, validation, stale callbacks and JSON-safe state. GitHub Actions runs the same suite.

Data courtesy of the [NOAA CO-OPS Data API](https://api.tidesandcurrents.noaa.gov/api/prod/) and [Metadata API](https://api.tidesandcurrents.noaa.gov/mdapi/prod/). Source code is MIT licensed. Tide Watch is not affiliated with or endorsed by NOAA, NWS or Hubitat.
