package org.opentripplanner.ext.spiderweb;

import org.locationtech.jts.geom.LineString;
import org.opentripplanner.model.TransitMode;

public class RouteSpiderwebSimpleFeatureImpl extends SpiderwebSimpleFeature {

  private final String mode;
  private final String color;
  private final int time;

  public RouteSpiderwebSimpleFeatureImpl(
    String id,
    LineString geometry,
    TransitMode mode,
    String color,
    int time
  ) {
    super(id, geometry);
    this.mode = mode.name();
    this.color = color != null ? '#' + color : null;
    this.time = time;
  }

  @Override
  public boolean hasUserData() {
    return super.hasUserData();
  }

  @Override
  public Object getAttribute(String s) {
    return switch (s) {
      case "mode" -> mode;
      case "color" -> color;
      case "time" -> time;
      default -> null;
    };
  }
}
