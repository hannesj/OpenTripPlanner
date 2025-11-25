package org.opentripplanner.ext.isochroneapi.resource;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.opentripplanner.astar.model.ShortestPathTree;
import org.opentripplanner.astar.strategy.DurationSkipEdgeStrategy;
import org.opentripplanner.graph_builder.module.nearbystops.TransitServiceResolver;
import org.opentripplanner.model.GenericLocation;
import org.opentripplanner.raptor.RaptorService;
import org.opentripplanner.raptor.api.request.RaptorProfile;
import org.opentripplanner.raptor.api.request.RaptorRequest;
import org.opentripplanner.raptor.api.request.RaptorRequestBuilder;
import org.opentripplanner.raptor.api.response.RaptorResponse;
import org.opentripplanner.raptor.api.response.StopArrivals;
import org.opentripplanner.routing.algorithm.raptoradapter.router.street.AccessEgressType;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.RaptorTransitData;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.RoutingAccessEgress;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.TripSchedule;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.mappers.AccessEgressMapper;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.request.DefaultTransitDataProviderFilter;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.request.RaptorRoutingRequestTransitData;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.routing.api.request.request.filter.SelectRequest;
import org.opentripplanner.routing.graphfinder.NearbyStop;
import org.opentripplanner.routing.graphfinder.NearbyStopFactory;
import org.opentripplanner.routing.linking.LinkingContext;
import org.opentripplanner.routing.linking.LinkingContextFactory;
import org.opentripplanner.routing.linking.TemporaryVerticesContainer;
import org.opentripplanner.routing.linking.mapping.LinkingContextRequestMapper;
import org.opentripplanner.standalone.api.OtpServerRequestContext;
import org.opentripplanner.street.model.edge.Edge;
import org.opentripplanner.street.model.vertex.TransitStopVertex;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.search.StreetSearchBuilder;
import org.opentripplanner.street.search.state.State;
import org.opentripplanner.street.search.strategy.DominanceFunctions;
import org.opentripplanner.transit.model.basic.MainAndSubMode;
import org.opentripplanner.transit.model.basic.TransitMode;
import org.opentripplanner.transit.model.network.grouppriority.TransitGroupPriorityService;
import org.opentripplanner.transit.service.TransitService;

class IsochroneCalculator {

  private final TransitService transitService;
  private final RaptorTransitData raptorTransitData;
  private final RaptorService<TripSchedule> raptorService;
  private final RouteRequest defaultRouteRequest;
  private final LinkingContextFactory linkingContextFactory;

  IsochroneCalculator(OtpServerRequestContext requestContext) {
    this.transitService = requestContext.transitService();
    this.raptorTransitData = requestContext.transitService().getRaptorTransitData();
    this.raptorService = new RaptorService<>(requestContext.raptorConfig());
    this.defaultRouteRequest = requestContext.defaultRouteRequest();
    this.linkingContextFactory = requestContext.linkingContextFactory();
  }

  IsochroneResult calculateTimeIsochrone(IsochroneRoutingContext ctx) {
    var requestBuilder = new RaptorRequestBuilder<TripSchedule>();
    RaptorRequest<TripSchedule> request = requestBuilder
      .profile(RaptorProfile.STANDARD)
      .searchParams()
      .earliestDepartureTime(ctx.departureTimeSeconds())
      .searchOneIterationOnly()
      .addAccessPaths(ctx.accessPaths())
      .oneToMany(true)
      .build();

    RaptorResponse<TripSchedule> response = raptorService.route(request, ctx.transitData());

    List<StopArrival> arrivals = mapBestArrivalTimes(
      response.getArrivals(),
      ctx.transitData().numberOfStops(),
      ctx.departureTimeSeconds()
    );

    return new IsochroneResult(
      arrivals,
      ctx.accessStates(),
      ctx.routeRequest(),
      ctx.departureTimeSeconds()
    );
  }

  IsochroneResult calculateDurationIsochrone(IsochroneRoutingContext ctx) {
    Duration timeWindowDuration = ctx
      .timeWindowDuration()
      .orElseThrow(() -> new IllegalStateException("Missing timeWindowDuration for request"));
    var accumulator = new DurationAccumulator(ctx.transitData().numberOfStops());
    var requestBuilder = new RaptorRequestBuilder<TripSchedule>();

    requestBuilder
      .profile(RaptorProfile.STANDARD)
      .searchParams()
      .earliestDepartureTime(ctx.departureTimeSeconds())
      .searchWindow(timeWindowDuration)
      .addAccessPaths(ctx.accessPaths())
      .oneToMany(true);

    requestBuilder
      .debug()
      .withStopArrivalListener(accumulator::stopArrival)
      .withStops(IntStream.range(0, ctx.transitData().numberOfStops()).boxed().toList());

    RaptorRequest<TripSchedule> request = requestBuilder.build();

    raptorService.route(request, ctx.transitData());

    List<StopArrival> stops = convertToStopArrivals(accumulator.toMap());

    return new IsochroneResult(
      stops,
      ctx.accessStates(),
      ctx.routeRequest(),
      ctx.departureTimeSeconds()
    );
  }

  IsochroneResult runIsochroneSearch(
    GenericLocation originLocation,
    ZonedDateTime departureDateTime,
    Optional<Duration> timeWindowDuration,
    Function<IsochroneRoutingContext, IsochroneResult> runner
  ) {
    var routeRequest = createRouteRequest(originLocation, departureDateTime);

    try (var temporaryVerticesContainer = new TemporaryVerticesContainer()) {
      var linkingRequest = LinkingContextRequestMapper.map(routeRequest, true);
      var linkingContext = linkingContextFactory.create(temporaryVerticesContainer, linkingRequest);
      var streetRequest = routeRequest.journey().access();
      var accessPreferences = routeRequest.preferences().street().accessEgress();

      Duration durationLimit = accessPreferences.maxDuration().valueOf(streetRequest.mode());

      var stopResolver = new TransitServiceResolver(transitService);

      var zeroDistanceAccessEgress = new NearbyStopFactory(stopResolver::getRegularStop)
        .nearbyStopsForTransitStopVerticesFiltered(
          linkingContext.fromStopVertices(),
          false,
          routeRequest,
          streetRequest
        );

      // When looking for street accesses/egresses we ignore the already found direct accesses/egresses
      var ignoreVertices = zeroDistanceAccessEgress
        .stream()
        .map(nearbyStop -> nearbyStop.state.getVertex())
        .collect(Collectors.toSet());

      var originVertices = linkingContext.findVertices(routeRequest.from());

      var states = new ArrayList<State>();
      var results = new ArrayList<>(zeroDistanceAccessEgress);

      var streetSearch = StreetSearchBuilder
        .of()
        .withSkipEdgeStrategy(new DurationSkipEdgeStrategy<>(durationLimit))
        .withDominanceFunction(new DominanceFunctions.EarliestArrival())
        .withRequest(routeRequest)
        .withArriveBy(false)
        .withStreetRequest(streetRequest)
        .withFrom(originVertices);

      ShortestPathTree<State, Edge, Vertex> spt = streetSearch.getShortestPathTree();

      if (spt != null) {
        states.addAll(spt.getAllStates());
        // TODO use GenericAStar and a traverseVisitor?
        for (State state : spt.getAllStates()) {
          Vertex targetVertex = state.getVertex();
          if (originVertices.contains(targetVertex) || ignoreVertices.contains(targetVertex)) {
            continue;
          }
          if (targetVertex instanceof TransitStopVertex tsv && state.isFinal()) {
            var stop = requireNonNull(stopResolver.getRegularStop(tsv.getId()));
            results.add(NearbyStop.nearbyStopForState(state, stop));
          }
        }
      }

      List<RoutingAccessEgress> access = AccessEgressMapper.mapNearbyStops(
        results,
        AccessEgressType.ACCESS
      );

      int departureTimeSeconds = getSecondsFromMidnight(departureDateTime);

      if (access.isEmpty()) {
        return new IsochroneResult(List.of(), states, routeRequest, departureTimeSeconds);
      }

      var transitDataProvider = createTransitDataProvider(departureDateTime, routeRequest);

      var res = runner.apply(
        new IsochroneRoutingContext(
          temporaryVerticesContainer,
          linkingContext,
          transitDataProvider,
          access,
          states,
          departureTimeSeconds,
          routeRequest,
          timeWindowDuration
        )
      );
      // Run EgressIsochroneCalculator here

      return res;
    }
  }

  private RouteRequest createRouteRequest(
    GenericLocation originLocation,
    ZonedDateTime departureDateTime
  ) {
    return defaultRouteRequest
      .copyOf()
      .withFrom(originLocation)
      .withDateTime(departureDateTime.toInstant())
      .withArriveBy(false)
      .withOneToMany(true)
      .withJourney(journey ->
        journey.withTransit(transit ->
          transit.withFilter(filter ->
            filter.addSelect(
              SelectRequest
                .of()
                .withTransportModes(
                  MainAndSubMode.notMainModes(
                    List.of(new MainAndSubMode(TransitMode.AIRPLANE, null))
                  )
                )
                .build()
            )
          )
        )
      )
      .withPreferences(preferences ->
        preferences.withStreet(street -> street.withRoutingTimeout(Duration.ofDays(1)))
      )
      .buildRequest();
  }

  private RaptorRoutingRequestTransitData createTransitDataProvider(
    ZonedDateTime departureTime,
    RouteRequest routeRequest
  ) {
    return new RaptorRoutingRequestTransitData(
      raptorTransitData,
      TransitGroupPriorityService.empty(),
      departureTime,
      0,
      4,
      DefaultTransitDataProviderFilter.ofRequest(routeRequest),
      routeRequest
    );
  }

  private int getSecondsFromMidnight(ZonedDateTime dateTime) {
    ZonedDateTime inTransitZone = dateTime.withZoneSameInstant(transitService.getTimeZone());
    return inTransitZone.toLocalTime().toSecondOfDay();
  }

  private List<StopArrival> mapBestArrivalTimes(
    StopArrivals arrivals,
    int numberOfStops,
    int departureTimeSeconds
  ) {
    Map<Integer, Integer> bestArrivalTimes = new HashMap<>();

    for (int stopIndex = 0; stopIndex < numberOfStops; stopIndex++) {
      if (arrivals.reached(stopIndex)) {
        int arrivalTime = arrivals.bestArrivalTime(stopIndex);
        bestArrivalTimes.put(stopIndex, arrivalTime - departureTimeSeconds);
      }
    }

    return convertToStopArrivals(bestArrivalTimes);
  }

  private List<StopArrival> convertToStopArrivals(Map<Integer, Integer> results) {
    return results
      .entrySet()
      .stream()
      .map(entry -> toStopArrival(entry.getKey(), entry.getValue()))
      .filter(Optional::isPresent)
      .map(Optional::get)
      .toList();
  }

  private Optional<StopArrival> toStopArrival(int stopIndex, int value) {
    var stopLocation = raptorTransitData.getStopByIndex(stopIndex);
    if (stopLocation == null) {
      return Optional.empty();
    }

    return Optional.of(
      new StopArrival(
        stopLocation.getId().toString(),
        stopLocation.getCoordinate().latitude(),
        stopLocation.getCoordinate().longitude(),
        value
      )
    );
  }

  record IsochroneRoutingContext(
    TemporaryVerticesContainer temporaryVerticesContainer,
    LinkingContext linkingContext,
    RaptorRoutingRequestTransitData transitData,
    List<RoutingAccessEgress> accessPaths,
    List<State> accessStates,
    int departureTimeSeconds,
    RouteRequest routeRequest,
    Optional<Duration> timeWindowDuration
  ) {}
}
