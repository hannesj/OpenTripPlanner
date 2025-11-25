# Isochrone API

## Contact Info

- Sandbox extension for OpenTripPlanner
- Created: 2025-11-21
- Maintainer: Community contribution

## Documentation

This sandbox extension provides a REST API for calculating isochrones using the RAPTOR algorithm. An isochrone represents all locations reachable from an origin within a given time or cost budget.

### Features

- **Time Isochrones**: Calculate the best arrival time at each stop within a time budget
- **Duration Isochrones**: Calculate the minimum travel duration to each stop within a time window
- **RAPTOR-based**: Uses the high-performance RAPTOR transit routing algorithm
- **One-to-many routing**: Efficiently finds reachable stops without requiring a specific destination

### Configuration

To enable the Isochrone API, add the following to your `otp-config.json`:

```json
{
  "otpFeatures": {
    "IsochroneAPI": true
  }
}
```

### API Endpoints

#### GET `/otp/routers/{router}/isochrone/time`

Calculate a time-based isochrone: find all stops reachable within a given time budget.

**Query Parameters:**
- `stationId` (required): Station ID in the format "feedId:stopId" (e.g., "60:12345")
- `departureTime` (required): Departure time in seconds since midnight
- `timeWindow` (optional): Maximum travel time in seconds (default: 3600 = 1 hour)

**Response:**
```json
[
  {"stopId": "60:12345", "time": 28800},
  {"stopId": "60:12346", "time": 29100},
  ...
]
```

Where `time` is the arrival time in seconds since midnight.

**Example:**
```
GET /otp/routers/default/isochrone/time?stationId=60:12345&departureTime=28800&timeWindow=3600
```

#### GET `/otp/routers/{router}/isochrone/duration`

Calculate a duration-based isochrone: find the minimum travel duration to each stop within a time window.

**Query Parameters:**
- `stationId` (required): Station ID in the format "feedId:stopId" (e.g., "60:12345")
- `departureTime` (required): Earliest departure time in seconds since midnight
- `timeWindow` (optional): Duration of the search window in seconds (default: 7200 = 2 hours)

**Response:**
```json
[
  {"stopId": "60:12345", "time": 300},
  {"stopId": "60:12346", "time": 600},
  ...
]
```

Where `time` is the minimum travel duration in seconds.

**Example:**
```
GET /otp/routers/default/isochrone/duration?stationId=60:12345&departureTime=25200&timeWindow=7200
```

This answers: "What is the shortest possible travel time to each stop if I can depart anytime between 7:00 AM and 9:00 AM?"

### Use Cases

1. **Accessibility Analysis**: Determine which areas are accessible within a given time budget
2. **Transit Planning**: Analyze service coverage and identify underserved areas
3. **Travel Time Maps**: Generate visualizations showing travel times from a location
4. **Schedule Optimization**: Find the best departure times to minimize travel duration

### Technical Details

- Uses the RAPTOR (Round-based Public Transit Optimized Router) algorithm
- Supports both absolute time isochrones and duration-based isochrones
- Efficiently handles one-to-many routing scenarios
- Returns raw stop-level data that can be post-processed for visualization

### Future Enhancements

- Support for multi-modal access (walk, bike, car to transit)
- GeoJSON polygon output for visualization
- Cost-based isochrones using generalized cost
- Multiple origin points

## Changelog

### 2025-11-21
- Initial implementation
- Time isochrone endpoint
- Duration isochrone endpoint
- Basic REST API with JSON responses
