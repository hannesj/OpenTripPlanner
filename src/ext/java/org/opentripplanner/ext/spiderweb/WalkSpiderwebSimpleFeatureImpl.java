package org.opentripplanner.ext.spiderweb;

import org.locationtech.jts.geom.LineString;

public class WalkSpiderwebSimpleFeatureImpl extends SpiderwebSimpleFeature {

  private final String mode;
  private final int time;

  public WalkSpiderwebSimpleFeatureImpl(String id, LineString geometry, String mode, int time) {
    super(id, geometry);
    this.mode = mode;
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
      case "time" -> time;
      default -> null;
    };
  }
}
