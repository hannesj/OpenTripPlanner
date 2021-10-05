package org.opentripplanner.ext.spiderweb;

import org.apache.commons.lang3.ArrayUtils;
import org.opentripplanner.model.GenericLocation;
import org.opentripplanner.model.TransitMode;
import org.opentripplanner.routing.algorithm.raptor.router.street.AccessEgressRouter;
import org.opentripplanner.routing.algorithm.raptor.transit.AccessEgress;
import org.opentripplanner.routing.algorithm.raptor.transit.Transfer;
import org.opentripplanner.routing.algorithm.raptor.transit.TransitLayer;
import org.opentripplanner.routing.algorithm.raptor.transit.TripSchedule;
import org.opentripplanner.routing.algorithm.raptor.transit.mappers.AccessEgressMapper;
import org.opentripplanner.routing.algorithm.raptor.transit.mappers.DateMapper;
import org.opentripplanner.routing.algorithm.raptor.transit.request.RaptorRoutingRequestTransitData;
import org.opentripplanner.routing.algorithm.raptor.transit.request.RoutingRequestTransitDataProviderFilter;
import org.opentripplanner.routing.algorithm.raptor.transit.request.TripPatternForDates;
import org.opentripplanner.routing.api.request.RoutingRequest;
import org.opentripplanner.routing.api.request.StreetMode;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.standalone.server.OTPServer;
import org.opentripplanner.standalone.server.Router;
import org.opentripplanner.transit.raptor.api.request.RaptorProfile;
import org.opentripplanner.transit.raptor.api.request.RaptorRequest;
import org.opentripplanner.transit.raptor.api.request.RaptorRequestBuilder;
import org.opentripplanner.transit.raptor.rangeraptor.RangeRaptorWorker;
import org.opentripplanner.transit.raptor.rangeraptor.multicriteria.McRangeRaptorWorkerState;
import org.opentripplanner.transit.raptor.rangeraptor.multicriteria.McTransitWorker;
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
import java.util.stream.Collectors;

@Path("/routers/{ignoreRouterId}/spiderweb")
@Produces(MediaType.APPLICATION_JSON)
public class SpiderwebResource {

    private static final Logger LOG = LoggerFactory.getLogger(SpiderwebResource.class);

    private final Router router;
    private final RoutingRequest routingRequest;
    private final TransitLayer transitLayer;
    private final Instant now;
    private final RaptorRoutingRequestTransitData requestTransitDataProvider;
    private final ZonedDateTime startOfTime;
    private final long start;
    private final AccessEgressMapper accessEgressMapper;

    public SpiderwebResource(@Context OTPServer otpServer) {
        start = System.currentTimeMillis();
        router = otpServer.getRouter();
        routingRequest = router.defaultRoutingRequest.clone();
        routingRequest.modes.transitModes.remove(TransitMode.AIRPLANE);

        final RoutingRequest transferRoutingRequest = Transfer.prepareTransferRoutingRequest(routingRequest);
        transferRoutingRequest.setRoutingContext(router.graph, (Vertex) null, null);

        now = Instant.now();

        transitLayer = router.graph.getRealtimeTransitLayer();
        requestTransitDataProvider = new RaptorRoutingRequestTransitData(
                transitLayer,
                now,
                2,
                new RoutingRequestTransitDataProviderFilter(routingRequest, router.graph.index),
                transferRoutingRequest
        );
        startOfTime = requestTransitDataProvider.getStartOfTime();
        accessEgressMapper = new AccessEgressMapper(transitLayer.getStopIndex());
    }

    @GET
    @Path("/")
    public Response getNetwork(@QueryParam("lat") String lat, @QueryParam("lon") String lon) {

        routingRequest.from = new GenericLocation(Double.parseDouble(lat), Double.parseDouble(lon));

        long preAccess = System.currentTimeMillis();

        final RoutingRequest accessRequest = routingRequest.clone();

        accessRequest.setRoutingContext(router.graph);

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
                .earliestDepartureTime(DateMapper.secondsSinceStartOfTime(startOfTime, now))
                .addAccessPaths(accessList)
                .searchOneIterationOnly()
                .build();

        final SearchContext<TripSchedule> ctx = router.raptorConfig.context(requestTransitDataProvider, request);

        final DestinationArrivalPaths<TripSchedule> destArrivalPaths = new PathConfig<>(ctx).createDestArrivalPaths(false);

        final Stops<TripSchedule> stops = new Stops<> (
                ctx.nStops(),
                ctx.egressPaths(),
                destArrivalPaths,
                ctx.debugFactory(),
                ctx.debugLogger()
        );

        final McRangeRaptorWorkerState<TripSchedule> workerState = new McRangeRaptorWorkerState<TripSchedule>(
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

        Map<Integer, AbstractStopArrival<TripSchedule>> accesses = new HashMap<>();

        Map<AbstractStopArrival<TripSchedule>, List<AbstractStopArrival<TripSchedule>>> children = new HashMap<>();

//        for (int stop = 0; stop < ctx.nStops(); stop++) {
//            StopArrivalParetoSet<TripSchedule> state = stops.stops[stop];
//            if (state != null) {
//                for (AbstractStopArrival<TripSchedule> arrival : state) {
//                    addArrival(arrival, accesses, children);
//                }
//            }
//        }

        final List<Map<String, Object>> res = accesses.values().stream()
                .filter(children::containsKey)
                .map((AbstractStopArrival<TripSchedule> arrival) -> mapArrival(arrival, children))
                .collect(Collectors.toList());

        long postProcess = System.currentTimeMillis();

        LOG.warn("pre-access {}ms", preAccess- start);
        LOG.warn("access {}ms", postAccess-preAccess);
        LOG.warn("setup {}ms", preRoute-postAccess);
        LOG.warn("routing {}ms", postRoute-preRoute);
        LOG.warn("mapping {}ms", postProcess-postRoute);

        return Response.ok().entity(res).build();
    }

    private void addArrival(
            AbstractStopArrival<TripSchedule> arrival,
            Map<Integer, AbstractStopArrival<TripSchedule>> accesses,
            Map<AbstractStopArrival<TripSchedule>, List<AbstractStopArrival<TripSchedule>>> children) {
        if (arrival.arrivedByAccess()) {
            accesses.put(arrival.stop(), arrival);
        } else {
            AbstractStopArrival<TripSchedule> previous = arrival.previous();
            List<AbstractStopArrival<TripSchedule>> prevousList = children.computeIfAbsent(previous, k -> new LinkedList<>());
            if (!prevousList.contains(arrival)) {
                prevousList.add(arrival);
                addArrival(arrival.previous(), accesses, children);
            }
        }
    }

    private Map<String, Object> mapArrival(
            AbstractStopArrival<TripSchedule> arrival,
            Map<AbstractStopArrival<TripSchedule>, List<AbstractStopArrival<TripSchedule>>> childMap
    ) {
        final List<AbstractStopArrival<TripSchedule>> children = childMap.get(arrival);

        List<Map<String, Object>> childOutput = new LinkedList<>();

        if (children != null) {
            Map<TripSchedule, List<AbstractStopArrival<TripSchedule>>> groups = new HashMap<>();
            for (var child : children) {
                if (child.arrivedByTransit()) {
                    List<AbstractStopArrival<TripSchedule>> c = groups.computeIfAbsent(child.transitPath().trip(), k -> new LinkedList<>());
                    c.add(child);
                } else {
                    childOutput.add(mapArrival(child, childMap));
                }
            }
            for (Map.Entry<TripSchedule, List<AbstractStopArrival<TripSchedule>>> group : groups.entrySet()) {
                TripSchedule trip = group.getKey();
                final int[] stopIndexes = ((TripPatternForDates) trip.pattern()).getTripPattern().getStopIndexes();
                List<AbstractStopArrival<TripSchedule>> stops = group.getValue().stream()
                        .sorted(Comparator.comparing(a -> ArrayUtils.indexOf(stopIndexes, a.stop())))
                        .collect(Collectors.toList());

                childOutput.add(Map.of(
                    "mode", trip.getOriginalTripPattern().getMode().name(),
                    "trip", trip.getOriginalTripTimes().getTrip().getId(),
                    "route", trip.getOriginalTripTimes().getTrip().getRoute().getId(),
                    "time", formatTime(trip.departure(trip.findDepartureStopPosition(arrival.arrivalTime(), arrival.stop()))),
                    "children", stops.stream().map(s ->  mapArrival(s, childMap)).collect(Collectors.toList())
                ));
            }
        }

        if (arrival.arrivedByAccess()) {
            return Map.of(
                    "mode", "access",
                    "stop", arrival.stop(),
                    "children", childOutput
            );
        } else if (arrival.arrivedByTransfer()) {
            return Map.of(
                    "mode", "walk",
                    "stop", arrival.stop(),
                    "time", formatTime(arrival.arrivalTime()),
                    "children", childOutput
            );
        } else {
            return Map.of(
                    "stop", arrival.stop(),
                    "time", formatTime(arrival.arrivalTime()),
                    "children", childOutput
            );
        }
    }

    private String formatTime(int time) {
        return startOfTime.plusSeconds(time).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    @GET
    @Path("/stops")
    public Response getStops() {
        List<Map<String, Object>> stops = router.graph
                .getRealtimeTransitLayer()
                .getStopIndex()
                .stopsByIndex
                .stream()
                .map(s -> Map.<String, Object>of(
                        "id", s.getId(),
                        "name", s.getName(),
                        "lat", s.getLat(),
                        "lon", s.getLon(),
                        "parent", s.isPartOfStation() ? s.getParentStation().getId() : "null"

                ))
                .collect(Collectors.toList());

        return Response.ok().entity(stops).build();
    }
}
