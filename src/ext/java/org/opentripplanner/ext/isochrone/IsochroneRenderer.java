package org.opentripplanner.ext.isochrone;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Geometry;
import org.opentripplanner.ext.isochrone.geometry.DelaunayIsolineBuilder;
import org.opentripplanner.ext.isochrone.geometry.ZMetric;
import org.opentripplanner.ext.isochrone.geometry.ZSampleGrid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IsochroneRenderer {

  private static final Logger LOG = LoggerFactory.getLogger(IsochroneRenderer.class);

  static List<IsochroneData> renderIsochrones(
    ZSampleGrid<WTWD> sampleGrid,
    IsochroneRequest isochroneRequest
  ) {
    long t0 = System.currentTimeMillis();
    ZMetric<WTWD> zMetric = new IsolineMetric();
    DelaunayIsolineBuilder<WTWD> isolineBuilder = new DelaunayIsolineBuilder<>(
      sampleGrid.delaunayTriangulate(),
      zMetric
    );
    isolineBuilder.setDebug(isochroneRequest.includeDebugGeometry);

    List<IsochroneData> isochrones = new ArrayList<>();
    for (Duration cutoff : isochroneRequest.cutoffs) {
      long cutoffSec = cutoff.toSeconds();
      WTWD z0 = new WTWD();
      z0.w = 1.0;
      z0.wTime = cutoffSec;
      z0.d = isochroneRequest.offRoadDistanceMeters;
      Geometry geometry = isolineBuilder.computeIsoline(z0);
      Geometry debugGeometry = null;
      if (isochroneRequest.includeDebugGeometry) {
        debugGeometry = isolineBuilder.getDebugGeometry();
      }

      isochrones.add(new IsochroneData(cutoffSec, geometry, debugGeometry));
    }

    long t1 = System.currentTimeMillis();
    LOG.info("Computed {} isochrones in {}msec", isochrones.size(), (int) (t1 - t0));

    return isochrones;
  }
}
