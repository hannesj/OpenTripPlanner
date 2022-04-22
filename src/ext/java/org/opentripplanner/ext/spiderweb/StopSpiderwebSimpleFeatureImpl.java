package org.opentripplanner.ext.spiderweb;

import org.locationtech.jts.geom.GeometryFactory;
import org.opentripplanner.common.geometry.GeometryUtils;
import org.opentripplanner.model.StopLocation;

public class StopSpiderwebSimpleFeatureImpl extends SpiderwebSimpleFeature {

  private static final GeometryFactory gf = GeometryUtils.getGeometryFactory();

  private final String mode;
  private final String time;
  private final Integer parent;
  private final String name;
  private final String trip;
  private final String route;
  private final String color;
  private final String departureTime;
  private final Boolean staySeated;

  public StopSpiderwebSimpleFeatureImpl(
    String id,
    StopLocation stop,
    String mode,
    String time,
    Integer parent
  ) {
    super(id, gf.createPoint(stop.getCoordinate().asJtsCoordinate()));
    this.mode = mode;
    this.time = time;
    this.parent = parent;
    this.name = stop.getName().toString();
    this.trip = null;
    this.route = null;
    this.color = null;
    this.departureTime = null;
    this.staySeated = null;
  }

  public StopSpiderwebSimpleFeatureImpl(
    String id,
    StopLocation stop,
    String mode,
    String time,
    Integer parent,
    Boolean staySeated,
    String trip,
    String route,
    String color,
    String departureTime
  ) {
    super(id, gf.createPoint(stop.getCoordinate().asJtsCoordinate()));
    this.mode = mode;
    this.time = time;
    this.parent = parent;
    this.staySeated = staySeated;
    this.name = stop.getName().toString();
    this.trip = trip;
    this.route = route;
    this.color = color != null ? '#' + color : null;
    this.departureTime = departureTime;
  }

  @Override
  public boolean hasUserData() {
    return super.hasUserData();
  }

  @Override
  public Object getAttribute(String s) {
    return switch (s) {
      case "mode" -> mode;
      case "time" -> time;
      case "parent" -> parent;
      case "name" -> name;
      case "trip" -> trip;
      case "route" -> route;
      case "color" -> color;
      case "departureTime" -> departureTime;
      case "staySeated" -> staySeated;
      default -> null;
    };
  }
}
