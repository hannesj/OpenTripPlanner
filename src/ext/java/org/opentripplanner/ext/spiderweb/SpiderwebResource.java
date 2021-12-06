package org.opentripplanner.ext.spiderweb;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import javax.ws.rs.DefaultValue;
import org.geojson.Feature;
import org.geojson.FeatureCollection;
import org.geojson.LineString;
import org.geojson.LngLatAlt;
import org.geojson.Point;
import org.opentripplanner.model.GenericLocation;
import org.opentripplanner.model.Route;
import org.opentripplanner.model.Stop;
import org.opentripplanner.model.TransitMode;
import org.opentripplanner.model.Trip;
import org.opentripplanner.model.transfer.ConstrainedTransfer;
import org.opentripplanner.routing.algorithm.raptor.router.street.AccessEgressRouter;
import org.opentripplanner.routing.algorithm.raptor.transit.AccessEgress;
import org.opentripplanner.routing.algorithm.raptor.transit.Transfer;
import org.opentripplanner.routing.algorithm.raptor.transit.TransitLayer;
import org.opentripplanner.routing.algorithm.raptor.transit.TripSchedule;
import org.opentripplanner.routing.algorithm.raptor.transit.cost.RaptorCostConverter;
import org.opentripplanner.routing.algorithm.raptor.transit.mappers.AccessEgressMapper;
import org.opentripplanner.routing.algorithm.raptor.transit.mappers.DateMapper;
import org.opentripplanner.routing.algorithm.raptor.transit.request.RaptorRoutingRequestTransitData;
import org.opentripplanner.routing.algorithm.raptor.transit.request.RoutingRequestTransitDataProviderFilter;
import org.opentripplanner.routing.algorithm.raptor.transit.request.TransferWithDuration;
import org.opentripplanner.routing.api.request.RoutingRequest;
import org.opentripplanner.routing.api.request.StreetMode;
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
import org.opentripplanner.transit.raptor.rangeraptor.multicriteria.McTransitWorker;
import org.opentripplanner.transit.raptor.rangeraptor.multicriteria.StopArrivalParetoSet;
import org.opentripplanner.transit.raptor.rangeraptor.multicriteria.Stops;
import org.opentripplanner.transit.raptor.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import org.opentripplanner.transit.raptor.rangeraptor.multicriteria.heuristic.HeuristicsProvider;
import org.opentripplanner.transit.raptor.rangeraptor.path.DestinationArrivalPaths;
import org.opentripplanner.transit.raptor.rangeraptor.path.configure.PathConfig;
import org.opentripplanner.transit.raptor.rangeraptor.transit.SearchContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Path("/routers/{ignoreRouterId}/spiderweb")
@Produces(MediaType.APPLICATION_JSON)
public class SpiderwebResource {

    private static final Logger LOG = LoggerFactory.getLogger(SpiderwebResource.class);
    private static final LoadingCache<CacheKey, RaptorRoutingRequestTransitData> transitDataCache =
            CacheBuilder.newBuilder().maximumSize(10).build(cacheBuilder());

    private final Router router;
    private final RoutingRequest routingRequest;
    private final TransitLayer transitLayer;
    private final RaptorRoutingRequestTransitData requestTransitDataProvider;
    private final ZonedDateTime startOfTime;
    private final long start;
    private final AccessEgressMapper accessEgressMapper;
    private final int earliestDepartureTime;
    private final int latestArrivalTime;

    public SpiderwebResource(
            @Context OTPServer otpServer,
            @QueryParam("lat") String lat,
            @QueryParam("lon") String lon,
            @QueryParam("time") String time,
            @QueryParam("maxMinutes") @DefaultValue("60") Integer maxMinutes
    ) {
        start = System.currentTimeMillis();
        router = otpServer.getRouter();
        routingRequest = router.defaultRoutingRequest.clone();
        routingRequest.modes.transitModes.remove(TransitMode.AIRPLANE);
        routingRequest.from = new GenericLocation(Double.parseDouble(lat), Double.parseDouble(lon));

        Instant now;
        if (time != null) {
            now = Instant.parse(time);
        }
        else {
            now = Instant.now();
        }


        // Make sure we have current transit layer in cache
        transitLayer = router.graph.getRealtimeTransitLayer();
        ZoneId zoneId = transitLayer.getTransitDataZoneId();
        LocalDate startDate = LocalDate.ofInstant(now, zoneId);
        LocalDate endDate = LocalDate.ofInstant(now.plus(maxMinutes, ChronoUnit.MINUTES), zoneId);
        Instant startOfDay = startDate.atStartOfDay(zoneId).toInstant();
        int additionalDays = (int) Period.between(startDate, endDate).get(ChronoUnit.DAYS);
        requestTransitDataProvider = transitDataCache.getUnchecked(new CacheKey(
                router, transitLayer, startOfDay, additionalDays));
        startOfTime = requestTransitDataProvider.getStartOfTime();
        earliestDepartureTime = DateMapper.secondsSinceStartOfTime(startOfTime, now);
        latestArrivalTime = DateMapper.secondsSinceStartOfTime(
                startOfTime,
                now.plus(maxMinutes, ChronoUnit.MINUTES)
        );
        accessEgressMapper = new AccessEgressMapper(transitLayer.getStopIndex());
    }

    @GET
    @Path("/")
    public Response getNetwork() {
        long preAccess = System.currentTimeMillis();

        final RoutingRequest accessRequest = routingRequest.clone();

        accessRequest.setRoutingContext(router.graph);
        accessRequest.maxAccessEgressDurationSeconds = Duration.ofMinutes(45).toSeconds();

        final Collection<AccessEgress> accessList = accessEgressMapper
                .mapNearbyStops(AccessEgressRouter.streetSearch(
                        accessRequest,
                        StreetMode.WALK,
                        false
                ), false);

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

        final SearchContext<TripSchedule> ctx =
                router.raptorConfig.context(requestTransitDataProvider, request);

        final DestinationArrivalPaths<TripSchedule> destArrivalPaths =
                new PathConfig<>(ctx).createDestArrivalPaths(false);

        final Stops<TripSchedule> stops = new Stops<>(
                ctx.nStops(),
                ctx.egressPaths(),
                destArrivalPaths,
                ctx.debugFactory()
        );

        final McRangeRaptorWorkerState<TripSchedule> workerState =
                new McRangeRaptorWorkerState<TripSchedule>(
                        stops,
                        destArrivalPaths,
                        new HeuristicsProvider<>(),
                        ctx.costCalculator(),
                        ctx.calculator(),
                        ctx.lifeCycle()
                );

        final McTransitWorker<TripSchedule> transitWorker = new McTransitWorker<>(
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

        Map<AbstractStopArrival<TripSchedule>, List<AbstractStopArrival<TripSchedule>>> children =
                new HashMap<>();

        int visited = 0;

        for (int stop = 0; stop < ctx.nStops(); stop++) {
            StopArrivalParetoSet<TripSchedule> state = stops.stops[stop];
            if (state != null) {
                visited++;
                state.stream()
                        .min(Comparator.comparing(this::getCombinedArrivalTimeCost))
                        .ifPresent(arrival -> addArrival(arrival, accesses, children));
                // for (AbstractStopArrival<TripSchedule> arrival : state) {
                //     addArrival(arrival, accesses, children);
                // }
            }
        }

        FeatureCollection res = new FeatureCollection();
        List<Stop> stopsByIndex = transitLayer.getStopIndex().stopsByIndex;

        for (var access : accesses.values()) {
            if (children.containsKey(access)) {
                mapArrival(access, res, stopsByIndex, children);
            }
        }

        long postProcess = System.currentTimeMillis();

        LOG.warn("pre-access {}ms", preAccess - start);
        LOG.warn("access {}ms", postAccess - preAccess);
        LOG.warn("setup {}ms", preRoute - postAccess);
        LOG.warn("routing {}ms", postRoute - preRoute);
        LOG.warn("mapping {}ms", postProcess - postRoute);

        LOG.warn("number of stops total {}", stopsByIndex.size());
        LOG.warn("number of stops visited {}", visited);
        LOG.warn("number of stops with children {}", children.size());
        LOG.warn("number of features {}", res.getFeatures().size());

        return Response.ok().entity(res).build();
    }

    private int getCombinedArrivalTimeCost(AbstractStopArrival<TripSchedule> arrival) {
        return arrival.arrivalTime() - earliestDepartureTime
                + RaptorCostConverter.toOtpDomainCost(arrival.cost());
    }

    private void addArrival(
            AbstractStopArrival<TripSchedule> arrival,
            Multimap<Integer, AbstractStopArrival<TripSchedule>> accesses,
            Map<AbstractStopArrival<TripSchedule>, List<AbstractStopArrival<TripSchedule>>> children
    ) {
        if (arrival.arrivedByAccess()) {
            accesses.put(arrival.stop(), arrival);
        }
        else {
            AbstractStopArrival<TripSchedule> previous = arrival.previous();
            List<AbstractStopArrival<TripSchedule>> prevousList =
                    children.computeIfAbsent(previous, k -> new LinkedList<>());
            if (!prevousList.contains(arrival)) {
                prevousList.add(arrival);
                addArrival(arrival.previous(), accesses, children);
            }
        }
    }

    private void mapArrival(
            AbstractStopArrival<TripSchedule> arrival,
            FeatureCollection res,
            List<Stop> stopsByIndex,
            Map<AbstractStopArrival<TripSchedule>, List<AbstractStopArrival<TripSchedule>>> childMap
    ) {
        final Stop stop = stopsByIndex.get(arrival.stop());
        final AbstractStopArrival<TripSchedule> previous = arrival.previous();
        final int arrivalTime = arrival.arrivalTime();
        final Feature feature = new Feature();
        feature.setId(String.valueOf(arrival.hashCode()));
        feature.setGeometry(new Point(stop.getLon(), stop.getLat()));
        if (arrival.arrivedByAccess()) {
            if (arrivalTime <= latestArrivalTime) {
                final Feature lineFeature = new Feature();

                final LineString line = new LineString();
                for (var edge : new GraphPath(
                        ((AccessEgress) arrival.accessPath().access()).getLastState()).edges) {
                    var geometry = edge.getGeometry();
                    if (geometry != null) {
                        for (var point : geometry.getCoordinates()) {
                            line.add(new LngLatAlt(point.x, point.y));
                        }
                    }
                }
                lineFeature.setGeometry(line);
                lineFeature.setProperties(Map.of(
                        "mode", "access",
                        "color", "#888",
                        "time", arrivalTime - earliestDepartureTime
                ));
                res.add(lineFeature);
            }
            feature.setProperties(Map.of(
                    "mode", "access",
                    "name", stop.getName(),
                    "color", "#888",
                    "time", formatTime(arrivalTime)
            ));
        }
        else if (arrival.arrivedByTransfer()) {
            if (arrivalTime <= latestArrivalTime) {
                final Feature lineFeature = new Feature();

                final LineString line = new LineString();
                for (var edge : (
                        (TransferWithDuration) arrival.transferPath()
                                .transfer()
                ).transfer().getEdges()) {
                    var geometry = edge.getGeometry();
                    if (geometry != null) {
                        for (var point : geometry.getCoordinates()) {
                            line.add(new LngLatAlt(point.x, point.y));
                        }
                    }
                }
                lineFeature.setGeometry(line);
                lineFeature.setProperties(Map.of(
                        "mode", "walk",
                        "color", "#888",
                        "time", arrivalTime - earliestDepartureTime
                ));
                res.add(lineFeature);
            }
            feature.setProperties(Map.of(
                    "mode", "walk",
                    "name", stop.getName(),
                    "time", formatTime(arrivalTime),
                    "color", "#888",
                    "parent", previous.hashCode()
            ));
        }
        else if (arrival.arrivedByTransit()) {
            TripSchedule trip = arrival.transitPath().trip();
            final TripTimes tripTimes = trip.getOriginalTripTimes();
            final Trip otpTrip = tripTimes.getTrip();
            final Route route = otpTrip.getRoute();
            var previousTransit = previous.mostResentTransitArrival();

            int previousArrivalStopIndex = -1;

            if (previousTransit != null) {
                final TripSchedule previousTrip = previousTransit.trip();
                previousArrivalStopIndex = previousTrip.findArrivalStopPosition(
                        previousTransit.arrivalTime(),
                        previousTransit.stop()
                );
            }
            ConstrainedTransfer tx = null;
            int stopPosition;
            if (previousArrivalStopIndex != -1) {
                stopPosition = trip.findDepartureStopPosition(
                        previousTransit.arrivalTime(),
                        previous.stop()
                );
                tx = router.graph.getTransferService().findTransfer(
                        null,
                        null,
                        previousTransit.trip().getOriginalTripTimes().getTrip(),
                        otpTrip,
                        previousArrivalStopIndex,
                        stopPosition
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


            feature.setProperties(Map.of(
                    "mode", trip.getOriginalTripPattern().getMode().name(),
                    "trip", otpTrip.getTripHeadsign() != null ? otpTrip.getTripHeadsign() : "",
                    "route", getRouteName(route),
                    "name", stop.getName(),
                    "time", formatTime(arrivalTime),
                    "departureTime", formatTime(departureTime),
                    "color", route.getColor() != null ? "#" + route.getColor() : "#888",
                    "parent", (staySeated ? previousTransit : previous).hashCode(),
                    "staySeated", staySeated
            ));
        }
        else {
            LOG.warn("unknown arrival {}", arrival);
            feature.setProperties(Map.of(
                    "name", stop.getName(),
                    "time", formatTime(arrivalTime),
                    "parent", previous.hashCode()
            ));
        }
        res.add(feature);

        final List<AbstractStopArrival<TripSchedule>> children = childMap.get(arrival);

        if (children != null) {
            Map<TripSchedule, List<AbstractStopArrival<TripSchedule>>> groups = new HashMap<>();
            for (var child : children) {
                if (child.arrivedByTransit()) {
                    List<AbstractStopArrival<TripSchedule>> c =
                            groups.computeIfAbsent(
                                    child.transitPath().trip(),
                                    k -> new LinkedList<>()
                            );
                    c.add(child);
                }
                mapArrival(child, res, stopsByIndex, childMap);
            }

            final TransitArrival<TripSchedule> previousTransit = arrival.mostResentTransitArrival();
            int previousArrivalStopIndex = -1;

            if (previousTransit != null) {
                final TripSchedule previousTrip = previousTransit.trip();
                previousArrivalStopIndex = previousTrip.findArrivalStopPosition(
                        previousTransit.arrivalTime(),
                        previousTransit.stop()
                );
            }

            for (Map.Entry<TripSchedule, List<AbstractStopArrival<TripSchedule>>> group : groups.entrySet()) {
                TripSchedule trip = group.getKey();

                final Trip originalTrip = trip.getOriginalTripTimes().getTrip();

                ConstrainedTransfer tx = null;
                int stopPosition = -1;
                if (previousArrivalStopIndex != -1) {
                    stopPosition = trip.findDepartureStopPosition(
                            previousTransit.arrivalTime(),
                            arrival.stop()
                    );
                    tx = router.graph.getTransferService().findTransfer(
                            null,
                            null,
                            previousTransit.trip().getOriginalTripTimes().getTrip(),
                            originalTrip,
                            previousArrivalStopIndex,
                            stopPosition
                    );
                }

                final int departureStopIndex = tx == null ? trip.findDepartureStopPosition(
                        arrivalTime,
                        arrival.stop()
                ) : stopPosition;

                final int arrivalStopIndex = group.getValue().stream()
                        .mapToInt(a -> trip.findArrivalStopPosition(a.arrivalTime(), a.stop()))
                        .max()
                        .getAsInt();

                for (int i = departureStopIndex; i < arrivalStopIndex; i++) {
                    final int arrivalDuration = trip.arrival(i + 1) - earliestDepartureTime;
                    // if (arrivalDuration > maxMinutes * 60) {break;}
                    final Feature lineFeature = new Feature();

                    final LineString line = new LineString();

                    var geom = trip.getOriginalTripPattern().getHopGeometry(i);
                    for (var coordinate : geom.getCoordinates()) {
                        line.add(new LngLatAlt(coordinate.x, coordinate.y));
                    }
                    lineFeature.setGeometry(line);
                    final Route route = originalTrip.getRoute();
                    lineFeature.setProperties(Map.of(
                            "mode", route.getMode().name(),
                            "color", route.getColor() != null ? "#" + route.getColor() : "#888",
                            "time", arrivalDuration
                    ));
                    res.add(lineFeature);
                }

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
    
    private static class CacheKey {
        private final Router router;
        private final TransitLayer transitLayer;
        private final Instant startOfDay;
        private final int additionalDays;

        private CacheKey(
                Router router,
                TransitLayer transitLayer,
                Instant startOfDay,
                int additionalDays
        ) {
            this.router = router;
            this.transitLayer = transitLayer;
            this.startOfDay = startOfDay;
            this.additionalDays = additionalDays;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {return true;}
            if (o == null || getClass() != o.getClass()) {return false;}

            final CacheKey cacheKey = (CacheKey) o;

            if (additionalDays != cacheKey.additionalDays) {return false;}
            if (!router.equals(cacheKey.router)) {return false;}
            if (!transitLayer.equals(cacheKey.transitLayer)) {return false;}
            return startOfDay.equals(cacheKey.startOfDay);
        }

        @Override
        public int hashCode() {
            int result = transitLayer.hashCode();
            result = 31 * result + startOfDay.hashCode();
            result = 31 * result + additionalDays;
            return result;
        }
    }


    private static CacheLoader<CacheKey, RaptorRoutingRequestTransitData> cacheBuilder() {
        return new CacheLoader<>() {
            @Override
            public RaptorRoutingRequestTransitData load(CacheKey key) {
                RoutingRequest routingRequest = key.router.defaultRoutingRequest.clone();
                routingRequest.modes.transitModes.remove(TransitMode.AIRPLANE);

                final RoutingRequest transferRoutingRequest =
                        Transfer.prepareTransferRoutingRequest(routingRequest);
                transferRoutingRequest.setRoutingContext(key.router.graph, (Vertex) null, null);

                return new RaptorRoutingRequestTransitData(
                        key.router.graph.getTransferService(),
                        key.transitLayer,
                        key.startOfDay,
                        0,
                        key.additionalDays,
                        new RoutingRequestTransitDataProviderFilter(routingRequest, key.router.graph.index),
                        transferRoutingRequest
                );
            }
        };
    }
}
