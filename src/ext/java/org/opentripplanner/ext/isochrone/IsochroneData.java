package org.opentripplanner.ext.isochrone;

import org.locationtech.jts.geom.Geometry;

/**
 * A conveyor for an isochrone.
 *
 * @author laurent
 */
public record IsochroneData(long cutoffSec, Geometry geometry, Geometry debugGeometry) {}
