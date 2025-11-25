package org.opentripplanner.ext.isochroneapi.resource;

import java.util.List;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.street.search.state.State;

/**
 * Result bundle from computing a stop-level isochrone. It is reused to seed an optional egress
 * street search and now also contains the states generated during the access search so they can be
 * included when building a pure walking isochrone.
 */
public record IsochroneResult(
  List<StopArrival> stops,
  List<State> accessStates,
  RouteRequest routeRequest,
  int departureTimeSeconds
) {}
