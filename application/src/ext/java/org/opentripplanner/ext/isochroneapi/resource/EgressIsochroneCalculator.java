package org.opentripplanner.ext.isochroneapi.resource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.opentripplanner.astar.model.ShortestPathTree;
import org.opentripplanner.framework.application.OTPRequestTimeoutException;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.routing.api.request.StreetMode;
import org.opentripplanner.routing.api.request.request.StreetRequest;
import org.opentripplanner.standalone.api.OtpServerRequestContext;
import org.opentripplanner.street.model.edge.Edge;
import org.opentripplanner.street.model.vertex.StreetVertex;
import org.opentripplanner.street.model.vertex.TransitStopVertex;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.search.StreetSearchBuilder;
import org.opentripplanner.street.search.request.StreetSearchRequestMapper;
import org.opentripplanner.street.search.state.State;
import org.opentripplanner.street.search.state.StateData;
import org.opentripplanner.street.search.strategy.DominanceFunctions;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a single A* egress search seeded from all reachable stops and collects the best travel
 * duration per street vertex.
 */
class EgressIsochroneCalculator {

  private static final Logger LOG = LoggerFactory.getLogger(EgressIsochroneCalculator.class);

  private final OtpServerRequestContext serverContext;

  EgressIsochroneCalculator(OtpServerRequestContext serverContext) {
    this.serverContext = serverContext;
  }

  List<IsochronePoint> calculate(IsochroneResult stopIsochrone) {
    var routeRequest = stopIsochrone.routeRequest();
    var egressRequest = routeRequest.journey().egress();

    LOG.debug(
      "Calculating egress isochrone with {} stops using mode {}",
      stopIsochrone.stops().size(),
      egressRequest.mode()
    );

    if (egressRequest.mode() == StreetMode.NOT_SET) {
      LOG.debug("Skipping egress isochrone because egress mode is NOT_SET");
      return List.of();
    }

    var streetSearchRequest = StreetSearchRequestMapper
      .mapInternal(routeRequest)
      .withMode(egressRequest.mode())
      .withArriveBy(false)
      .build();

    Map<String, IsochronePoint> bestArrivals = new HashMap<>();
    addStatesToBestArrivals(stopIsochrone.accessStates(), bestArrivals);

    LOG.debug(
      "Seeded egress best arrivals with {} access states",
      stopIsochrone.accessStates().size()
    );

    StateData baseCaseStateData = StateData.getBaseCaseStateData(streetSearchRequest);
    var startVertices = new HashSet<TransitStopVertex>();
    var initialStates = new ArrayList<State>();

    for (StopArrival stopArrival : stopIsochrone.stops()) {
      int durationFromOrigin = stopArrival.time();
      TransitStopVertex startVertex = findStopVertex(stopArrival.stopId()).orElse(null);

      if (startVertex == null) {
        LOG.debug("No transit stop vertex found for id {}, skipping", stopArrival.stopId());
        continue;
      }

      startVertices.add(startVertex);
      initialStates.add(
        new State(
          startVertex,
          routeRequest.dateTime().plusSeconds(durationFromOrigin),
          baseCaseStateData,
          streetSearchRequest
        )
      );
    }

    LOG.debug(
      "Prepared {} initial egress states from {} stops ({} missing stop vertices)",
      initialStates.size(),
      stopIsochrone.stops().size(),
      stopIsochrone.stops().size() - startVertices.size()
    );

    if (initialStates.isEmpty()) {
      LOG.debug("No initial egress states, returning seeded arrivals only");
      return List.copyOf(bestArrivals.values());
    }

    ShortestPathTree<State, Edge, Vertex> spt = streetSearch(
      routeRequest,
      egressRequest,
      startVertices,
      initialStates
    );

    if (spt == null) {
      LOG.debug("Egress street search returned no shortest path tree");
      return List.copyOf(bestArrivals.values());
    }

    int beforeMerge = bestArrivals.size();
    addStatesToBestArrivals(spt.getAllStates(), bestArrivals);

    LOG.debug(
      "Merged {} egress states into best arrivals ({} -> {} points)",
      spt.getAllStates().size(),
      beforeMerge,
      bestArrivals.size()
    );

    return List.copyOf(bestArrivals.values());
  }

  private Optional<TransitStopVertex> findStopVertex(String stopId) {
    try {
      return Optional.ofNullable(serverContext.graph().getStopVertex(FeedScopedId.parse(stopId)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private void addStatesToBestArrivals(
    Collection<State> states,
    Map<String, IsochronePoint> bestArrivals
  ) {
    for (State state : states) {
      if (state == null || !(state.getVertex() instanceof StreetVertex streetVertex)) {
        continue;
      }

      int outputTime = (int) state.getElapsedTimeSeconds();

      bestArrivals.compute(
        streetVertex.getLabel().toString(),
        (label, existing) -> {
          if (existing == null || outputTime < existing.time()) {
            var coord = streetVertex.getCoordinate();
            return new IsochronePoint(
              streetVertex.getLabel().toString(),
              coord.getY(),
              coord.getX(),
              outputTime,
              IsochronePoint.Type.EGRESS
            );
          }
          return existing;
        }
      );
    }
  }

  private ShortestPathTree<State, Edge, Vertex> streetSearch(
    RouteRequest routeRequest,
    StreetRequest egressRequest,
    Set<TransitStopVertex> startVertices,
    List<State> initialStates
  ) {
    return StreetSearchBuilder
      .of()
      .withPreStartHook(OTPRequestTimeoutException::checkForTimeout)
      .withSkipEdgeStrategy((current, edge) -> current.getWalkDistance() > 6_000)
      .withDominanceFunction(new DominanceFunctions.EarliestArrival())
      .withRequest(routeRequest)
      .withArriveBy(false)
      .withStreetRequest(egressRequest)
      .withFrom(new HashSet<>(startVertices))
      .withInitialStates(initialStates)
      .getShortestPathTree();
  }
}
