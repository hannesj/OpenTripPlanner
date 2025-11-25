package org.opentripplanner.ext.isochroneapi.resource;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.opentripplanner.framework.application.OTPRequestTimeoutException;
import org.opentripplanner.model.GenericLocation;
import org.opentripplanner.routing.error.RoutingValidationException;
import org.opentripplanner.standalone.api.OtpServerRequestContext;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST API endpoint for calculating isochrones using RAPTOR.
 * <p>
 * An isochrone represents all locations reachable from an origin within a given time or cost
 * budget. This API provides two types of isochrones:
 * <ul>
 *   <li><b>Time Isochrone</b>: Returns the travel duration from the origin to each stop for a single departure</li>
 *   <li><b>Duration Isochrone</b>: Returns the minimum travel duration to each stop within a time window</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>
 * GET /otp/routers/default/isochrone/time?origin=60:12345&departureTime=28800
 * GET /otp/routers/default/isochrone/duration?origin=60.123,24.456&departureTime=25200&timeWindow=7200
 * </pre>
 */
@Path("/isochrone")
@Produces("text/csv")
public class IsochroneResource {

  private static final Logger LOG = LoggerFactory.getLogger(IsochroneResource.class);

  @QueryParam("origin")
  private String origin;

  @QueryParam("departureTime")
  private String departureTime;

  @QueryParam("timeWindow")
  @DefaultValue("PT2H")
  private String timeWindow;

  @QueryParam("includeEgress")
  @DefaultValue("false")
  private boolean includeEgress;

  @QueryParam("crs")
  @DefaultValue("EPSG:4326")
  private String crs;

  @QueryParam("resolutionX")
  private Double resolutionX;

  @QueryParam("resolutionY")
  private Double resolutionY;

  @QueryParam("bbox")
  private String bbox;

  @QueryParam("format")
  @DefaultValue("geotiff")
  private String format;

  private final IsochroneCalculator isochroneCalculator;
  private final EgressIsochroneCalculator egressIsochroneCalculator;
  private final IsochroneRasterizer rasterizer;

  public IsochroneResource(@Context OtpServerRequestContext requestContext) {
    this.isochroneCalculator = new IsochroneCalculator(requestContext);
    this.egressIsochroneCalculator = new EgressIsochroneCalculator(requestContext);
    this.rasterizer = new IsochroneRasterizer();
  }

  /**
   * Calculate a time-based isochrone: find the travel duration to each stop for a single departure.
   * <p>
   * Query parameters:
   * - origin: stop ID (e.g., "60:12345") or coordinates (e.g., "60.123,24.456")
   * - departureTime: ISO-8601 format (e.g., "2025-11-21T08:00:00+02:00")
   * - includeEgress: optional, include egress legs in result
   * @return CSV file with columns: stopId,latitude,longitude,time (time = duration in seconds)
   */
  @GET
  @Path("/time")
  public Response getTimeIsochrone() {
    try {
      var parsedParameters = parseCommonParameters(true);
      if (parsedParameters.hasError()) {
        return parsedParameters.error();
      }

      var request = parsedParameters.value();

      var stopIsochrone = isochroneCalculator.runIsochroneSearch(
        request.originLocation(),
        request.departureDateTime(),
        request.timeWindow(),
        isochroneCalculator::calculateTimeIsochrone
      );

      if (!request.includeEgress()) {
        return Response.ok(IsochroneCsvWriter.toCsv(stopIsochrone.stops())).build();
      }

      List<IsochronePoint> points = new java.util.ArrayList<>(
        toPoints(stopIsochrone.stops(), IsochronePoint.Type.STOP)
      );

      points.addAll(egressIsochroneCalculator.calculate(stopIsochrone));

      return Response.ok(IsochroneCsvWriter.toExtendedCsv(points)).build();
    } catch (RoutingValidationException e) {
      LOG.warn("Invalid origin {} for time isochrone: {}", origin, e.getMessage());
      return invalidOriginResponse(origin);
    } catch (OTPRequestTimeoutException e) {
      LOG.warn("Request timeout for time isochrone from {}", origin, e);
      return Response
        .status(Response.Status.REQUEST_TIMEOUT)
        .entity("Error: Request timeout")
        .type(MediaType.TEXT_PLAIN)
        .build();
    } catch (Exception e) {
      LOG.error("Error calculating time isochrone from {}", origin, e);
      return Response
        .status(Response.Status.INTERNAL_SERVER_ERROR)
        .entity("Error: " + e.getMessage())
        .type(MediaType.TEXT_PLAIN)
        .build();
    }
  }

  /**
   * Calculate a duration-based isochrone: find the minimum travel duration to each stop
   * within a time window.
   * <p>
   * This answers the question: "What is the shortest possible travel time to each stop
   * if I can depart anytime within this window?"
   *
   * Query parameters:
   * - origin: stop ID (e.g., "60:12345") or coordinates (e.g., "60.123,24.456")
   * - departureTime: earliest departure time in ISO-8601 format (e.g., "2025-11-21T07:00:00+02:00")
   * - timeWindow: ISO-8601 duration (e.g., "PT2H" for 2 hours, default: "PT2H")
   * - includeEgress: optional, include egress legs in result
   * @return CSV file with columns: stopId,latitude,longitude,time (time = duration in seconds)
   */
  @GET
  @Path("/duration")
  public Response getDurationIsochrone() {
    try {
      var parsedParameters = parseCommonParameters(false);
      if (parsedParameters.hasError()) {
        return parsedParameters.error();
      }

      var request = parsedParameters.value();

      var stopIsochrone = isochroneCalculator.runIsochroneSearch(
        request.originLocation(),
        request.departureDateTime(),
        request.timeWindow(),
        isochroneCalculator::calculateDurationIsochrone
      );

      if (!request.includeEgress()) {
        return Response.ok(IsochroneCsvWriter.toCsv(stopIsochrone.stops())).build();
      }

      List<IsochronePoint> points = new java.util.ArrayList<>(
        toPoints(stopIsochrone.stops(), IsochronePoint.Type.STOP)
      );

      points.addAll(egressIsochroneCalculator.calculate(stopIsochrone));

      return Response.ok(IsochroneCsvWriter.toExtendedCsv(points)).build();
    } catch (RoutingValidationException e) {
      LOG.warn("Invalid origin {} for duration isochrone: {}", origin, e.getMessage());
      return invalidOriginResponse(origin);
    } catch (OTPRequestTimeoutException e) {
      LOG.warn("Request timeout for duration isochrone from {}", origin, e);
      return Response
        .status(Response.Status.REQUEST_TIMEOUT)
        .entity("Error: Request timeout")
        .type(MediaType.TEXT_PLAIN)
        .build();
    } catch (Exception e) {
      LOG.error("Error calculating duration isochrone from {}", origin, e);
      return Response
        .status(Response.Status.INTERNAL_SERVER_ERROR)
        .entity("Error: " + e.getMessage())
        .type(MediaType.TEXT_PLAIN)
        .build();
    }
  }

  @GET
  @Path("/time/raster")
  @Produces("image/tiff")
  public Response getTimeIsochroneRaster() {
    return rasterIsochrone(true);
  }

  @GET
  @Path("/duration/raster")
  @Produces("image/tiff")
  public Response getDurationIsochroneRaster() {
    return rasterIsochrone(false);
  }

  private Response validateRequiredParameter(String value, String name) {
    if (value == null || value.isBlank()) {
      return Response
        .status(Response.Status.BAD_REQUEST)
        .entity("Error: " + name + " parameter is required")
        .type(MediaType.TEXT_PLAIN)
        .build();
    }
    return null;
  }

  private Optional<ZonedDateTime> parseDepartureTime(String departureTime) {
    try {
      return Optional.of(ZonedDateTime.parse(departureTime));
    } catch (Exception e) {
      LOG.debug("Invalid departureTime {}", departureTime, e);
      return Optional.empty();
    }
  }

  private Optional<Duration> parseTimeWindow(String timeWindow) {
    try {
      return Optional.of(Duration.parse(timeWindow));
    } catch (Exception e) {
      LOG.debug("Invalid timeWindow {}", timeWindow, e);
      return Optional.empty();
    }
  }

  private ParseResult<CommonIsochroneRequest> parseCommonParameters(boolean timeIsochrone) {
    var missingParamResponse = validateRequiredParameter(origin, "origin");
    if (missingParamResponse != null) {
      return ParseResult.error(missingParamResponse);
    }
    missingParamResponse = validateRequiredParameter(departureTime, "departureTime");
    if (missingParamResponse != null) {
      return ParseResult.error(missingParamResponse);
    }

    Optional<ZonedDateTime> departureDateTime = parseDepartureTime(departureTime);
    if (departureDateTime.isEmpty()) {
      String example = timeIsochrone ? "2025-11-21T08:00:00+02:00" : "2025-11-21T07:00:00+02:00";
      return ParseResult.error(
        invalidParameterResponse("departureTime", "ISO-8601 format (e.g., " + example + ")")
      );
    }

    Optional<Duration> timeWindowDuration = timeIsochrone
      ? Optional.empty()
      : parseTimeWindow(timeWindow);
    if (!timeIsochrone && timeWindowDuration.isEmpty()) {
      return ParseResult.error(
        invalidParameterResponse("timeWindow", "ISO-8601 duration format (e.g., PT2H, PT30M)")
      );
    }

    GenericLocation originLocation = parseOrigin(origin);
    if (originLocation == null) {
      return ParseResult.error(invalidOriginResponse(origin));
    }

    return ParseResult.success(
      new CommonIsochroneRequest(
        originLocation,
        departureDateTime.get(),
        timeWindowDuration,
        includeEgress
      )
    );
  }

  private record ParseResult<T>(T value, Response error) {
    static <T> ParseResult<T> success(T value) {
      return new ParseResult<>(value, null);
    }

    static <T> ParseResult<T> error(Response error) {
      return new ParseResult<>(null, error);
    }

    boolean hasError() {
      return error != null;
    }
  }

  private record CommonIsochroneRequest(
    GenericLocation originLocation,
    ZonedDateTime departureDateTime,
    Optional<Duration> timeWindow,
    boolean includeEgress
  ) {}

  private Response rasterIsochrone(boolean timeIsochrone) {
    try {
      var parsedParameters = parseCommonParameters(timeIsochrone);
      if (parsedParameters.hasError()) {
        return parsedParameters.error();
      }

      var commonRequest = parsedParameters.value();

      var gridRequest = parseRasterGridRequest();

      if (gridRequest.hasError()) {
        return gridRequest.error();
      }

      IsochroneResult isochrone = isochroneCalculator.runIsochroneSearch(
        commonRequest.originLocation(),
        commonRequest.departureDateTime(),
        commonRequest.timeWindow(),
        timeIsochrone
          ? isochroneCalculator::calculateTimeIsochrone
          : isochroneCalculator::calculateDurationIsochrone
      );

      List<IsochronePoint> points = new java.util.ArrayList<>(
        toPoints(isochrone.stops(), IsochronePoint.Type.STOP)
      );

      if (commonRequest.includeEgress()) {
        points.addAll(egressIsochroneCalculator.calculate(isochrone));
      }

      if (points.isEmpty()) {
        return invalidOriginResponse(origin);
      }

      byte[] raster = rasterizer.rasterize(points, gridRequest.value());

      return Response
        .ok(raster, gridRequest.value().mediaType())
        .header("Content-Disposition", "attachment; filename=isochrone.tif")
        .build();
    } catch (IllegalArgumentException e) {
      return Response
        .status(Response.Status.BAD_REQUEST)
        .entity("Error: " + e.getMessage())
        .type(MediaType.TEXT_PLAIN)
        .build();
    } catch (RoutingValidationException e) {
      return invalidOriginResponse(origin);
    } catch (Exception e) {
      LOG.error("Error generating isochrone raster from {}", origin, e);
      return Response
        .status(Response.Status.INTERNAL_SERVER_ERROR)
        .entity("Error: " + e.getMessage())
        .type(MediaType.TEXT_PLAIN)
        .build();
    }
  }

  private ParseResult<IsochroneRasterizer.RasterGridRequest> parseRasterGridRequest() {
    if (resolutionX == null || resolutionY == null || resolutionX <= 0 || resolutionY <= 0) {
      return ParseResult.error(
        invalidParameterResponse("resolution", "positive resolutionX and resolutionY")
      );
    }

    IsochroneRasterizer.RasterGridRequest.Format rasterFormat;
    String mediaType;
    if ("geotiff".equalsIgnoreCase(format)) {
      rasterFormat = IsochroneRasterizer.RasterGridRequest.Format.GEOTIFF;
      mediaType = "image/tiff";
    } else {
      return ParseResult.error(invalidParameterResponse("format", "\"geotiff\""));
    }

    IsochroneRasterizer.RasterGridRequest.BoundingBox parsedBbox = null;
    if (bbox != null && !bbox.isBlank()) {
      var parts = bbox.split(",");
      if (parts.length == 4) {
        try {
          double minX = Double.parseDouble(parts[0]);
          double minY = Double.parseDouble(parts[1]);
          double maxX = Double.parseDouble(parts[2]);
          double maxY = Double.parseDouble(parts[3]);
          if (maxX <= minX || maxY <= minY) {
            return ParseResult.error(
              invalidParameterResponse("bbox", "minX,minY,maxX,maxY where max values are greater")
            );
          }
          parsedBbox =
            new IsochroneRasterizer.RasterGridRequest.BoundingBox(minX, minY, maxX, maxY);
        } catch (NumberFormatException e) {
          return ParseResult.error(
            invalidParameterResponse("bbox", "numeric minX,minY,maxX,maxY values")
          );
        }
      } else {
        return ParseResult.error(invalidParameterResponse("bbox", "minX,minY,maxX,maxY"));
      }
    }

    return ParseResult.success(
      new IsochroneRasterizer.RasterGridRequest(
        crs,
        resolutionX,
        resolutionY,
        parsedBbox,
        rasterFormat,
        mediaType
      )
    );
  }

  private List<IsochronePoint> toPoints(List<StopArrival> arrivals, IsochronePoint.Type type) {
    return arrivals
      .stream()
      .map(arrival ->
        new IsochronePoint(
          arrival.stopId(),
          arrival.latitude(),
          arrival.longitude(),
          arrival.time(),
          type
        )
      )
      .toList();
  }

  private GenericLocation parseOrigin(String origin) {
    try {
      return new GenericLocation(null, FeedScopedId.parse(origin), null, null);
    } catch (IllegalArgumentException e) {
      LOG.debug("Origin {} is not a valid stop ID, trying as coordinate", origin);
    }

    try {
      String[] parts = origin.split(",");
      if (parts.length == 2) {
        double lat = Double.parseDouble(parts[0].trim());
        double lon = Double.parseDouble(parts[1].trim());
        return GenericLocation.fromCoordinate(lat, lon);
      }
    } catch (Exception e) {
      LOG.debug("Origin {} is not a valid coordinate", origin, e);
    }

    return null;
  }

  private Response invalidOriginResponse(String origin) {
    return Response
      .status(Response.Status.NOT_FOUND)
      .entity("Error: Invalid origin: " + origin)
      .type(MediaType.TEXT_PLAIN)
      .build();
  }

  private Response invalidParameterResponse(String name, String expected) {
    return Response
      .status(Response.Status.BAD_REQUEST)
      .entity("Error: Invalid " + name + " format. Expected " + expected)
      .type(MediaType.TEXT_PLAIN)
      .build();
  }
}
