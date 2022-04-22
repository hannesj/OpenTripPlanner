package org.opentripplanner.ext.spiderweb;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.geobuf.GeobufFeature;
import org.geotools.data.geobuf.GeobufFeatureCollection;
import org.geotools.data.geobuf.GeobufGeometry;
import org.opentripplanner.common.geometry.GeometryUtils;
import org.opentripplanner.model.GenericLocation;
import org.opentripplanner.model.Route;
import org.opentripplanner.model.StopLocation;
import org.opentripplanner.model.TransitMode;
import org.opentripplanner.model.Trip;
import org.opentripplanner.model.TripPattern;
import org.opentripplanner.model.modes.AllowedTransitMode;
import org.opentripplanner.model.transfer.ConstrainedTransfer;
import org.opentripplanner.routing.algorithm.raptoradapter.router.street.AccessEgressRouter;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.AccessEgress;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.Transfer;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.TransitLayer;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.TripSchedule;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.cost.RaptorCostConverter;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.mappers.AccessEgressMapper;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.mappers.DateMapper;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.request.RaptorRoutingRequestTransitData;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.request.RoutingRequestTransitDataProviderFilter;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.request.TransferWithDuration;
import org.opentripplanner.routing.api.request.RoutingRequest;
import org.opentripplanner.routing.api.request.StreetMode;
import org.opentripplanner.routing.core.RoutingContext;
import org.opentripplanner.routing.core.TemporaryVerticesContainer;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.spt.GraphPath;
import org.opentripplanner.routing.trippattern.TripTimes;
import org.opentripplanner.standalone.server.OTPServer;
import org.opentripplanner.standalone.server.Router;
import org.opentripplanner.transit.raptor.api.request.RaptorProfile;
import org.opentripplanner.transit.raptor.api.request.RaptorRequest;
import org.opentripplanner.transit.raptor.api.request.RaptorRequestBuilder;
import org.opentripplanner.transit.raptor.api.transit.TransitArrival;
import org.opentripplanner.transit.raptor.rangeraptor.RangeRaptorWorker;
import org.opentripplanner.transit.raptor.rangeraptor.multicriteria.McRangeRaptorWorkerState;
import org.opentripplanner.transit.raptor.rangeraptor.multicriteria.MultiCriteriaRoutingStrategy;
import org.opentripplanner.transit.raptor.rangeraptor.multicriteria.StopArrivalParetoSet;
import org.opentripplanner.transit.raptor.rangeraptor.multicriteria.StopArrivals;
import org.opentripplanner.transit.raptor.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import org.opentripplanner.transit.raptor.rangeraptor.multicriteria.heuristic.HeuristicsProvider;
import org.opentripplanner.transit.raptor.rangeraptor.path.DestinationArrivalPaths;
import org.opentripplanner.transit.raptor.rangeraptor.path.configure.PathConfig;
import org.opentripplanner.transit.raptor.rangeraptor.transit.SearchContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/routers/{ignoreRouterId}/spiderweb")
@Produces("application/protobuf")
public class SpiderwebResource {

  private static final Logger LOG = LoggerFactory.getLogger(SpiderwebResource.class);
  private static final LoadingCache<CacheKey, RaptorRoutingRequestTransitData> transitDataCache = CacheBuilder
    .newBuilder()
    .maximumSize(10)
    .build(cacheBuilder());

  private final Router router;
  private final RoutingRequest routingRequest;
  private final TransitLayer transitLayer;
  private final RaptorRoutingRequestTransitData requestTransitDataProvider;
  private final ZonedDateTime startOfTime;
  private final long start;
  private final AccessEgressMapper accessEgressMapper;
  private final int earliestDepartureTime;
  private final int latestArrivalTime;

  @PathParam("ignoreRouterId")
  private String ignoreRouterId;

  public SpiderwebResource(
    @Context OTPServer otpServer,
    @QueryParam("lat") String lat,
    @QueryParam("lon") String lon,
    @QueryParam("time") String time,
    @QueryParam("maxMinutes") @DefaultValue("60") Integer maxMinutes
  ) {
    start = System.currentTimeMillis();
    router = otpServer.getRouter();
    routingRequest = router.copyDefaultRoutingRequest();
    routingRequest.modes.transitModes = AllowedTransitMode.getAllTransitModesExceptAirplane();
    routingRequest.from = new GenericLocation(Double.parseDouble(lat), Double.parseDouble(lon));

    Instant now;
    if (time != null) {
      now = Instant.parse(time);
    } else {
      now = Instant.now();
    }

    // Make sure we have current transit layer in cache
    transitLayer = router.graph.getRealtimeTransitLayer();
    ZoneId zoneId = transitLayer.getTransitDataZoneId();
    LocalDate startDate = LocalDate.ofInstant(now, zoneId);
    LocalDate endDate = LocalDate.ofInstant(now.plus(maxMinutes, ChronoUnit.MINUTES), zoneId);
    startOfTime = startDate.atStartOfDay(zoneId).toInstant().atZone(zoneId);
    int additionalDays = (int) Period.between(startDate, endDate).get(ChronoUnit.DAYS);
    requestTransitDataProvider =
      transitDataCache.getUnchecked(
        new CacheKey(router, transitLayer, startOfTime, additionalDays)
      );
    earliestDepartureTime = DateMapper.secondsSinceStartOfTime(startOfTime, now);
    latestArrivalTime =
      DateMapper.secondsSinceStartOfTime(startOfTime, now.plus(maxMinutes, ChronoUnit.MINUTES));
    accessEgressMapper = new AccessEgressMapper(transitLayer.getStopIndex());
  }

  @GET
  @Path("/")
  public Response getNetwork() {
    long preAccess = System.currentTimeMillis();

    final RoutingRequest accessRequest = routingRequest.clone();

    accessRequest.maxAccessEgressDurationSeconds = Duration.ofMinutes(45).toSeconds();

    try (var temporaryVertices = new TemporaryVerticesContainer(router.graph, accessRequest)) {
      final Collection<AccessEgress> accessList = accessEgressMapper.mapNearbyStops(
        AccessEgressRouter.streetSearch(
          new RoutingContext(accessRequest, router.graph, temporaryVertices),
          StreetMode.WALK,
          false
        ),
        false
      );

      final long postAccess = System.currentTimeMillis();

      final RaptorRequest<TripSchedule> request = new RaptorRequestBuilder<TripSchedule>()
        .profile(RaptorProfile.MULTI_CRITERIA)
        .searchParams()
        .earliestDepartureTime(earliestDepartureTime)
        .latestArrivalTime(latestArrivalTime)
        .addAccessPaths(accessList)
        .searchOneIterationOnly()
        .timetableEnabled(false)
        .constrainedTransfersEnabled(true)
        .build();

      final SearchContext<TripSchedule> ctx = router.raptorConfig.context(
        requestTransitDataProvider,
        request
      );

      final DestinationArrivalPaths<TripSchedule> destArrivalPaths = new PathConfig<>(ctx)
        .createDestArrivalPaths(false);

      final StopArrivals<TripSchedule> stops = new StopArrivals<>(
        ctx.nStops(),
        ctx.egressPaths(),
        destArrivalPaths,
        ctx.debugFactory()
      );

      final McRangeRaptorWorkerState<TripSchedule> workerState = new McRangeRaptorWorkerState<>(
        stops,
        destArrivalPaths,
        new HeuristicsProvider<>(),
        ctx.costCalculator(),
        ctx.calculator(),
        ctx.lifeCycle()
      );

      final MultiCriteriaRoutingStrategy<TripSchedule> transitWorker = new MultiCriteriaRoutingStrategy<>(
        workerState,
        ctx.slackProvider(),
        ctx.costCalculator(),
        ctx.debugFactory()
      );

      final RangeRaptorWorker<TripSchedule> search = new RangeRaptorWorker<>(
        workerState,
        transitWorker,
        ctx.transit(),
        ctx.slackProvider(),
        ctx.accessPaths(),
        ctx.roundProvider(),
        ctx.calculator(),
        ctx.createLifeCyclePublisher(),
        ctx.timers(),
        ctx.enableConstrainedTransfers()
      );

      long preRoute = System.currentTimeMillis();

      search.route();

      long postRoute = System.currentTimeMillis();

      Multimap<Integer, AbstractStopArrival<TripSchedule>> accesses = HashMultimap.create();

      Map<AbstractStopArrival<TripSchedule>, List<AbstractStopArrival<TripSchedule>>> children = new HashMap<>();

      int visited = 0;

      for (int stop = 0; stop < ctx.nStops(); stop++) {
        StopArrivalParetoSet<TripSchedule> state = stops.arrivals[stop];
        if (state != null) {
          visited++;
          state
            .stream()
            .min(Comparator.comparing(this::getCombinedArrivalTimeCost))
            .ifPresent(arrival -> addArrival(arrival, accesses, children));
          // for (AbstractStopArrival<TripSchedule> arrival : state) {
          //     addArrival(arrival, accesses, children);
          // }
        }
      }

      ListFeatureCollection res = new ListFeatureCollection(SpiderwebSimpleFeature.type);

      List<StopLocation> stopsByIndex = transitLayer.getStopIndex().stopsByIndex;

      for (var access : accesses.values()) {
        if (children.containsKey(access)) {
          mapArrival(access, res, stopsByIndex, children);
        }
      }

      GeobufFeatureCollection geobufFeatureCollection = new GeobufFeatureCollection(
        new GeobufFeature(new GeobufGeometry(5, 2))
      );
      StreamingOutput out = outputStream -> geobufFeatureCollection.encode(res, outputStream);

      long postProcess = System.currentTimeMillis();

      LOG.warn("pre-access {}ms", preAccess - start);
      LOG.warn("access {}ms", postAccess - preAccess);
      LOG.warn("setup {}ms", preRoute - postAccess);
      LOG.warn("routing {}ms", postRoute - preRoute);
      LOG.warn("mapping {}ms", postProcess - postRoute);

      LOG.warn("number of stops total {}", stopsByIndex.size());
      LOG.warn("number of stops visited {}", visited);
      LOG.warn("number of stops with children {}", children.size());
      LOG.warn("number of features {}", res.size());

      return Response.ok().entity(out).build();
    }
  }

  private int getCombinedArrivalTimeCost(AbstractStopArrival<TripSchedule> arrival) {
    return (
      arrival.arrivalTime() -
      earliestDepartureTime +
      RaptorCostConverter.toOtpDomainCost(arrival.cost())
    );
  }

  private void addArrival(
    AbstractStopArrival<TripSchedule> arrival,
    Multimap<Integer, AbstractStopArrival<TripSchedule>> accesses,
    Map<AbstractStopArrival<TripSchedule>, List<AbstractStopArrival<TripSchedule>>> children
  ) {
    if (arrival.arrivedByAccess()) {
      accesses.put(arrival.stop(), arrival);
    } else {
      AbstractStopArrival<TripSchedule> previous = arrival.previous();
      List<AbstractStopArrival<TripSchedule>> previousList = children.computeIfAbsent(
        previous,
        k -> new LinkedList<>()
      );
      if (!previousList.contains(arrival)) {
        previousList.add(arrival);
        addArrival(arrival.previous(), accesses, children);
      }
    }
  }

  private void mapArrival(
    AbstractStopArrival<TripSchedule> arrival,
    ListFeatureCollection res,
    List<StopLocation> stopsByIndex,
    Map<AbstractStopArrival<TripSchedule>, List<AbstractStopArrival<TripSchedule>>> childMap
  ) {
    final StopLocation stop = stopsByIndex.get(arrival.stop());
    final AbstractStopArrival<TripSchedule> previous = arrival.previous();
    final int arrivalTime = arrival.arrivalTime();
    if (arrival.arrivedByAccess()) {
      if (arrivalTime <= latestArrivalTime) {
        final AccessEgress access = (AccessEgress) arrival.accessPath().access();
        res.add(
          new WalkSpiderwebSimpleFeatureImpl(
            String.valueOf(access.hashCode()),
            new GraphPath(access.getLastState()).getGeometry(),
            "access",
            arrivalTime - earliestDepartureTime
          )
        );
      }
      res.add(
        new StopSpiderwebSimpleFeatureImpl(
          String.valueOf(arrival.hashCode()),
          stop,
          "access",
          formatTime(arrivalTime),
          null
        )
      );
    } else if (arrival.arrivedByTransfer()) {
      if (arrivalTime <= latestArrivalTime) {
        final TransferWithDuration transfer = (TransferWithDuration) arrival
          .transferPath()
          .transfer();
        res.add(
          new WalkSpiderwebSimpleFeatureImpl(
            String.valueOf(transfer.transfer().hashCode()),
            GeometryUtils.makeLineString(transfer.transfer().getCoordinates()),
            "walk",
            arrivalTime - earliestDepartureTime
          )
        );
      }

      res.add(
        new StopSpiderwebSimpleFeatureImpl(
          String.valueOf(arrival.hashCode()),
          stop,
          "walk",
          formatTime(arrivalTime),
          previous.hashCode()
        )
      );
    } else if (arrival.arrivedByTransit()) {
      TripSchedule trip = arrival.transitPath().trip();
      final TripTimes tripTimes = trip.getOriginalTripTimes();
      final Trip otpTrip = tripTimes.getTrip();
      final Route route = otpTrip.getRoute();
      var previousTransit = previous.mostRecentTransitArrival();

      int previousArrivalStopIndex = -1;

      if (previousTransit != null) {
        final TripSchedule previousTrip = previousTransit.trip();
        previousArrivalStopIndex =
          previousTrip.findArrivalStopPosition(
            previousTransit.arrivalTime(),
            previousTransit.stop()
          );
      }
      ConstrainedTransfer tx = null;
      int stopPosition;
      if (previousArrivalStopIndex != -1) {
        stopPosition =
          trip.findDepartureStopPosition(previousTransit.arrivalTime(), previous.stop());
        tx =
          router.graph
            .getTransferService()
            .findTransfer(
              previousTransit.trip().getOriginalTripTimes().getTrip(),
              previousArrivalStopIndex,
              null, //TODO
              otpTrip,
              stopPosition,
              null //TODO
            );
      } else {
        stopPosition = trip.findDepartureStopPosition(previous.arrivalTime(), previous.stop());
      }

      int departureTime = tripTimes.getDepartureTime(stopPosition);

      boolean staySeated;
      if (tx != null) {
        staySeated = tx.getTransferConstraint().isStaySeated();
      } else {
        staySeated = false;
      }

      res.add(
        new StopSpiderwebSimpleFeatureImpl(
          String.valueOf(arrival.hashCode()),
          stop,
          trip.getOriginalTripPattern().getMode().name(),
          formatTime(arrivalTime),
          (staySeated ? previousTransit : previous).hashCode(),
          staySeated,
          otpTrip.getTripHeadsign(),
          getRouteName(route),
          route.getColor(),
          formatTime(departureTime)
        )
      );
    } else {
      LOG.warn("unknown arrival {}", arrival);

      res.add(
        new StopSpiderwebSimpleFeatureImpl(
          String.valueOf(arrival.hashCode()),
          stop,
          null,
          formatTime(arrivalTime),
          previous.hashCode()
        )
      );
    }

    mapChildren(arrival, res, stopsByIndex, childMap, arrivalTime);
  }

  private void mapChildren(
    AbstractStopArrival<TripSchedule> arrival,
    ListFeatureCollection res,
    List<StopLocation> stopsByIndex,
    Map<AbstractStopArrival<TripSchedule>, List<AbstractStopArrival<TripSchedule>>> childMap,
    int arrivalTime
  ) {
    final List<AbstractStopArrival<TripSchedule>> children = childMap.get(arrival);

    if (children == null) return;

    Map<TripSchedule, List<AbstractStopArrival<TripSchedule>>> groups = new HashMap<>();
    for (var child : children) {
      if (child.arrivedByTransit()) {
        List<AbstractStopArrival<TripSchedule>> c = groups.computeIfAbsent(
          child.transitPath().trip(),
          k -> new LinkedList<>()
        );
        c.add(child);
      }
      mapArrival(child, res, stopsByIndex, childMap);
    }

    final TransitArrival<TripSchedule> previousTransit = arrival.mostRecentTransitArrival();
    int previousArrivalStopIndex = -1;

    if (previousTransit != null) {
      final TripSchedule previousTrip = previousTransit.trip();
      previousArrivalStopIndex =
        previousTrip.findArrivalStopPosition(previousTransit.arrivalTime(), previousTransit.stop());
    }

    for (Map.Entry<TripSchedule, List<AbstractStopArrival<TripSchedule>>> group : groups.entrySet()) {
      TripSchedule trip = group.getKey();

      final TripPattern originalTripPattern = trip.getOriginalTripPattern();
      final Trip originalTrip = trip.getOriginalTripTimes().getTrip();
      final Route route = originalTrip.getRoute();
      final TransitMode mode = route.getMode();
      final String color = route.getColor();

      ConstrainedTransfer tx = null;
      int stopPosition = -1;
      if (previousArrivalStopIndex != -1) {
        stopPosition =
          trip.findDepartureStopPosition(previousTransit.arrivalTime(), arrival.stop());
        tx =
          router.graph
            .getTransferService()
            .findTransfer(
              previousTransit.trip().getOriginalTripTimes().getTrip(),
              previousArrivalStopIndex,
              null, //TODO
              originalTrip,
              stopPosition,
              null //TODO
            );
      }

      final int departureStopIndex = tx == null
        ? trip.findDepartureStopPosition(arrivalTime, arrival.stop())
        : stopPosition;

      final int arrivalStopIndex = group
        .getValue()
        .stream()
        .mapToInt(a -> trip.findArrivalStopPosition(a.arrivalTime(), a.stop()))
        .max()
        .orElseThrow();

      for (int i = departureStopIndex; i < arrivalStopIndex; i++) {
        final int arrivalDuration = trip.arrival(i + 1) - earliestDepartureTime;

        res.add(
          new RouteSpiderwebSimpleFeatureImpl(
            originalTripPattern.getFeedId() + "_" + i,
            originalTripPattern.getHopGeometry(i),
            mode,
            color,
            arrivalDuration
          )
        );
      }
    }
  }

  private String getRouteName(Route route) {
    StringBuilder builder = new StringBuilder();
    if (route.getMode().equals(TransitMode.BUS)) {
      builder.append(route.getAgency().getName());
      builder.append(" ");
    }
    if (route.getShortName() != null) {
      builder.append(route.getShortName());
      if (route.getLongName() != null) {
        builder.append(" — ");
      }
    }
    if (route.getLongName() != null) {
      builder.append(route.getLongName());
    }
    return builder.toString();
  }

  private String formatTime(int time) {
    return startOfTime.plusSeconds(time).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }

  private record CacheKey(
    Router router,
    TransitLayer transitLayer,
    ZonedDateTime startOfDay,
    int additionalDays
  ) {}

  private static CacheLoader<CacheKey, RaptorRoutingRequestTransitData> cacheBuilder() {
    return new CacheLoader<>() {
      @Override
      @Nonnull
      public RaptorRoutingRequestTransitData load(@Nonnull CacheKey key) {
        RoutingRequest routingRequest = key.router.copyDefaultRoutingRequest();
        routingRequest.modes.transitModes = AllowedTransitMode.getAllTransitModesExceptAirplane();

        final RoutingRequest transferRoutingRequest = Transfer.prepareTransferRoutingRequest(
          routingRequest
        );

        return new RaptorRoutingRequestTransitData(
          key.router.graph.getTransferService(),
          key.transitLayer,
          key.startOfDay,
          0,
          key.additionalDays,
          new RoutingRequestTransitDataProviderFilter(routingRequest, key.router.graph.index),
          new RoutingContext(transferRoutingRequest, key.router.graph, (Vertex) null, null)
        );
      }
    };
  }
}
