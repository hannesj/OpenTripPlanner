package org.opentripplanner.ext.isochrone;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import org.geojson.MultiPolygon;
import org.geotools.data.geojson.GeoJSONWriter;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opentripplanner.model.GenericLocation;
import org.opentripplanner.model.Stop;
import org.opentripplanner.model.StopLocation;
import org.opentripplanner.routing.algorithm.astar.AStarBuilder;
import org.opentripplanner.routing.algorithm.raptoradapter.router.street.AccessEgressRouter;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.AccessEgress;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.Transfer;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.TransitLayer;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.TripSchedule;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.mappers.AccessEgressMapper;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.mappers.DateMapper;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.request.RaptorRoutingRequestTransitData;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.request.RoutingRequestTransitDataProviderFilter;
import org.opentripplanner.routing.api.request.RoutingRequest;
import org.opentripplanner.routing.api.request.StreetMode;
import org.opentripplanner.routing.core.RoutingContext;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.StateData;
import org.opentripplanner.routing.core.TemporaryVerticesContainer;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.spt.DominanceFunction;
import org.opentripplanner.standalone.server.OTPServer;
import org.opentripplanner.standalone.server.Router;
import org.opentripplanner.transit.raptor.api.request.RaptorProfile;
import org.opentripplanner.transit.raptor.api.request.RaptorRequest;
import org.opentripplanner.transit.raptor.api.request.RaptorRequestBuilder;
import org.opentripplanner.transit.raptor.api.transit.RaptorTransfer;
import org.opentripplanner.transit.raptor.api.view.Worker;
import org.opentripplanner.transit.raptor.rangeraptor.RangeRaptorWorker;
import org.opentripplanner.transit.raptor.rangeraptor.standard.ArrivalTimeRoutingStrategy;
import org.opentripplanner.transit.raptor.rangeraptor.standard.StdRangeRaptorWorkerState;
import org.opentripplanner.transit.raptor.rangeraptor.standard.besttimes.BestTimes;
import org.opentripplanner.transit.raptor.rangeraptor.standard.besttimes.BestTimesOnlyStopArrivalsState;
import org.opentripplanner.transit.raptor.rangeraptor.standard.besttimes.SimpleBestNumberOfTransfers;
import org.opentripplanner.transit.raptor.rangeraptor.transit.SearchContext;
import org.opentripplanner.util.time.DurationUtils;

@Path("/isochrone")
@Produces(MediaType.APPLICATION_JSON)
public class IsochroneResource {

  private static final SimpleFeatureType contourSchema = makeContourSchema();

  private final Router router;
  private final RoutingRequest routingRequest;
  private final TransitLayer transitLayer;
  private final RaptorRoutingRequestTransitData requestTransitDataProvider;
  private final Instant startTime;
  private final Instant endTime;
  private final ZonedDateTime startOfTime;
  private final IsochroneRequest isochroneRequest;
  private final long start;
  private BestTimes bestTimes;
  private SearchContext<TripSchedule> raptorContext;

  public IsochroneResource(
    @Context OTPServer otpServer,
    @QueryParam("lat") String lat,
    @QueryParam("lon") String lon,
    @QueryParam("time") String time,
    @QueryParam("cutoff") @DefaultValue("60m") List<String> cutoffs
  ) {
    start = System.currentTimeMillis();
    router = otpServer.getRouter();
    transitLayer = router.graph.getRealtimeTransitLayer();
    ZoneId zoneId = transitLayer.getTransitDataZoneId();
    routingRequest = router.copyDefaultRoutingRequest();
    routingRequest.from = new GenericLocation(Double.parseDouble(lat), Double.parseDouble(lon));
    isochroneRequest = new IsochroneRequest(cutoffs.stream().map(DurationUtils::duration).toList());

    if (time != null) {
      startTime = Instant.parse(time);
    } else {
      startTime = Instant.now();
    }

    endTime = startTime.plus(isochroneRequest.maxCutoff);

    LocalDate startDate = LocalDate.ofInstant(startTime, zoneId);
    LocalDate endDate = LocalDate.ofInstant(endTime, zoneId);
    startOfTime = startDate.atStartOfDay(zoneId).toInstant().atZone(zoneId);

    RoutingRequest transferRoutingRequest = Transfer.prepareTransferRoutingRequest(routingRequest);

    requestTransitDataProvider =
      new RaptorRoutingRequestTransitData(
        router.graph.getTransferService(),
        transitLayer,
        startOfTime,
        0,
        (int) Period.between(startDate, endDate).get(ChronoUnit.DAYS),
        new RoutingRequestTransitDataProviderFilter(routingRequest, router.graph.index),
        new RoutingContext(transferRoutingRequest, router.graph, (Vertex) null, null)
      );
  }

  @GET
  @Path("/")
  public Response getIsochrones() {
    long preAccess = System.currentTimeMillis();

    final RoutingRequest accessRequest = routingRequest.clone();

    accessRequest.maxAccessEgressDurationSeconds = isochroneRequest.maxAccessDuration.toSeconds();

    try (var temporaryVertices = new TemporaryVerticesContainer(router.graph, accessRequest)) {
      AccessEgressMapper accessEgressMapper = new AccessEgressMapper(transitLayer.getStopIndex());
      final Collection<AccessEgress> accessList = accessEgressMapper.mapNearbyStops(
        AccessEgressRouter.streetSearch(
          new RoutingContext(accessRequest, router.graph, temporaryVertices),
          StreetMode.WALK,
          false
        ),
        false
      );

      final long postAccess = System.currentTimeMillis();

      getRaptorWorker(accessList).route();

      final long postRaptor = System.currentTimeMillis();

      StateData stateData = StateData.getInitialStateData(routingRequest);

      List<State> initialStates = new ArrayList<>();

      RoutingContext routingContext = new RoutingContext(
        routingRequest,
        router.graph,
        temporaryVertices
      );

      for (var vertex : temporaryVertices.getFromVertices()) {
        initialStates.add(new State(vertex, startTime, routingContext, stateData));
      }

      final int unreachedTime = raptorContext.calculator().unreachedTime();

      List<StopLocation> stopsByIndex = transitLayer.getStopIndex().stopsByIndex;
      for (int i = 0; i < stopsByIndex.size(); i++) {
        final int onBoardTime = bestTimes.onBoardTime(i);
        if (onBoardTime != unreachedTime) {
          StopLocation stopLocation = stopsByIndex.get(i);
          if (stopLocation instanceof Stop stop) {
            Vertex v = router.graph.index.getStopVertexForStop().get(stop);
            if (v != null) {
              Instant time = startOfTime.plusSeconds(onBoardTime).toInstant();
              State s = new State(v, time, routingContext, stateData.clone());
              s.weight = startTime.until(time, ChronoUnit.SECONDS);
              // TODO: This shouldn't be overridden in state initialization
              s.stateData.startTime = stateData.startTime;
              initialStates.add(s);
            }
          }
        }
      }

      var spt = AStarBuilder
        .allDirectionsMaxDuration(isochroneRequest.maxCutoff)
        .setContext(routingContext)
        .setDominanceFunction(new DominanceFunction.EarliestArrival())
        .setInitialStates(initialStates)
        .getShortestPathTree();

      var sampleGrid = SampleGridRenderer.getSampleGrid(spt, isochroneRequest);

      var isochrones = IsochroneRenderer.renderIsochrones(sampleGrid, isochroneRequest);

      var features = makeContourFeatures(isochrones);

      StreamingOutput out = outputStream -> {
        try (final GeoJSONWriter geoJSONWriter = new GeoJSONWriter(outputStream)) {
          geoJSONWriter.writeFeatureCollection(features);
        }
      };

      return Response.ok().entity(out).build();
    }
  }

  private Worker<TripSchedule> getRaptorWorker(Collection<? extends RaptorTransfer> accessList) {
    final RaptorRequest<TripSchedule> request = new RaptorRequestBuilder<TripSchedule>()
      .profile(RaptorProfile.BEST_TIME)
      .searchParams()
      .earliestDepartureTime(DateMapper.secondsSinceStartOfTime(startOfTime, startTime))
      .latestArrivalTime(DateMapper.secondsSinceStartOfTime(startOfTime, endTime))
      .addAccessPaths(accessList)
      .searchOneIterationOnly()
      .timetableEnabled(false)
      .constrainedTransfersEnabled(false) // TODO: Not compatible with best times
      .build();

    raptorContext = router.raptorConfig.context(requestTransitDataProvider, request);

    bestTimes =
      new BestTimes(raptorContext.nStops(), raptorContext.calculator(), raptorContext.lifeCycle());

    final SimpleBestNumberOfTransfers simpleBestNumberOfTransfers = new SimpleBestNumberOfTransfers(
      raptorContext.nStops(),
      raptorContext.roundProvider()
    );

    final BestTimesOnlyStopArrivalsState<TripSchedule> stopArrivalsState = new BestTimesOnlyStopArrivalsState<>(
      bestTimes,
      simpleBestNumberOfTransfers
    );

    final StdRangeRaptorWorkerState<TripSchedule> workerState = new StdRangeRaptorWorkerState<>(
      raptorContext.calculator(),
      bestTimes,
      stopArrivalsState,
      () -> false
    );

    final ArrivalTimeRoutingStrategy<TripSchedule> transitWorker = new ArrivalTimeRoutingStrategy<>(
      raptorContext.calculator(),
      workerState
    );

    return new RangeRaptorWorker<>(
      workerState,
      transitWorker,
      raptorContext.transit(),
      raptorContext.slackProvider(),
      raptorContext.accessPaths(),
      raptorContext.roundProvider(),
      raptorContext.calculator(),
      raptorContext.createLifeCyclePublisher(),
      raptorContext.timers(),
      raptorContext.enableConstrainedTransfers()
    );
  }

  static SimpleFeatureType makeContourSchema() {
    /* Create the output feature schema. */
    SimpleFeatureTypeBuilder tbuilder = new SimpleFeatureTypeBuilder();
    tbuilder.setName("contours");
    tbuilder.setCRS(DefaultGeographicCRS.WGS84);
    tbuilder.setDefaultGeometry("the_geom");
    // Do not use "geom" or "geometry" below, it seems to broke shapefile generation
    tbuilder.add("the_geom", MultiPolygon.class);
    tbuilder.add("time", Long.class); // TODO change to something more descriptive and lowercase
    return tbuilder.buildFeatureType();
  }

  /**
   * Create a geotools feature collection from a list of isochrones in the OTPA internal format.
   * Once in a FeatureCollection, they can for example be exported as GeoJSON.
   */
  private static SimpleFeatureCollection makeContourFeatures(List<IsochroneData> isochrones) {
    DefaultFeatureCollection featureCollection = new DefaultFeatureCollection(null, contourSchema);
    SimpleFeatureBuilder fbuilder = new SimpleFeatureBuilder(contourSchema);
    for (IsochroneData isochrone : isochrones) {
      fbuilder.add(isochrone.geometry());
      fbuilder.add(isochrone.cutoffSec());
      featureCollection.add(fbuilder.buildFeature(null));
    }
    return featureCollection;
  }
}
