package org.opentripplanner.ext.isochroneapi.resource;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.eclipse.imagen.RasterFactory;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.api.referencing.operation.TransformException;
import org.geotools.coverage.Category;
import org.geotools.coverage.GridSampleDimension;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.referencing.CRS;
import org.geotools.referencing.operation.transform.AffineTransform2D;
import org.geotools.util.NumberRange;

class IsochroneRasterizer {

  private static final int MAX_PIXELS = 40_000_000;
  private static final String SOURCE_CRS = "EPSG:4326";
  private static final int NO_DATA_VALUE = -1;

  byte[] rasterize(List<IsochronePoint> points, RasterGridRequest gridRequest)
    throws FactoryException, TransformException, IOException {
    if (points.isEmpty()) {
      throw new IllegalArgumentException("No points to rasterize");
    }

    CoordinateReferenceSystem sourceCrs = CRS.decode(SOURCE_CRS, true);
    CoordinateReferenceSystem targetCrs = CRS.decode(gridRequest.crsCode(), true);
    MathTransform transform = CRS.findMathTransform(sourceCrs, targetCrs, true);

    double[] sourceCoords = new double[points.size() * 2];
    for (int i = 0; i < points.size(); i++) {
      IsochronePoint point = points.get(i);
      sourceCoords[i * 2] = point.longitude();
      sourceCoords[i * 2 + 1] = point.latitude();
    }

    double[] targetCoords = new double[sourceCoords.length];
    transform.transform(sourceCoords, 0, targetCoords, 0, points.size());

    var bbox = gridRequest.bbox() != null
      ? gridRequest.bbox()
      : deriveBoundingBox(targetCoords, gridRequest.resolutionX(), gridRequest.resolutionY());

    int width = (int) Math.ceil(bbox.width() / gridRequest.resolutionX());
    int height = (int) Math.ceil(bbox.height() / gridRequest.resolutionY());

    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("Bounding box or resolution yields empty raster");
    }

    if ((long) width * (long) height > MAX_PIXELS) {
      throw new IllegalArgumentException(
        "Requested raster is too large (" + width + "x" + height + " pixels)"
      );
    }

    WritableRaster raster = RasterFactory.createBandedRaster(
      DataBuffer.TYPE_INT,
      width,
      height,
      1,
      null
    );

    // Fill with NaN to represent no data.
    int[] noDataParams = new int[] { NO_DATA_VALUE };
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        raster.setPixel(x, y, noDataParams);
      }
    }

    for (int i = 0; i < points.size(); i++) {
      int value = points.get(i).time();
      double xCoord = targetCoords[i * 2];
      double yCoord = targetCoords[i * 2 + 1];

      int x = (int) Math.floor((xCoord - bbox.minX()) / gridRequest.resolutionX());
      int y = (int) Math.floor((bbox.maxY() - yCoord) / gridRequest.resolutionY());

      if (x < 0 || x >= width || y < 0 || y >= height) {
        continue;
      }

      int existing = raster.getSample(x, y, 0);
      if (existing == NO_DATA_VALUE || value < existing) {
        raster.setSample(x, y, 0, value);
      }
    }

    // Define that -1 is "No Data" and everything else is generic data
    Category noDataCategory = new Category(
      Category.NODATA.getName(),
      new Color[] { new Color(0, 0, 0, 0) }, // Transparent color
      NumberRange.create(NO_DATA_VALUE, NO_DATA_VALUE)
    );

    // Define the valid range (e.g., 0 to Max Integer)
    Category valuesCategory = new Category(
      "Time",
      (Color[]) null, // Let the client handle coloring
      NumberRange.create(0, Integer.MAX_VALUE)
    );

    GridSampleDimension[] dimensions = new GridSampleDimension[] {
      new GridSampleDimension("isochrone", new Category[] { noDataCategory, valuesCategory }, null),
    };

    // Use a grid with its origin at the upper-left corner so the GeoTIFF is not flipped vertically.
    AffineTransform gridToCrs = new AffineTransform(
      gridRequest.resolutionX(),
      0,
      0,
      -gridRequest.resolutionY(),
      bbox.minX() + gridRequest.resolutionX() / 2.0,
      bbox.maxY() - gridRequest.resolutionY() / 2.0
    );

    GridCoverageFactory factory = new GridCoverageFactory();
    GridCoverage2D coverage = factory.create(
      "isochrone",
      raster,
      targetCrs,
      new AffineTransform2D(gridToCrs),
      dimensions
    );

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    GeoTiffWriter writer = new GeoTiffWriter(output);
    try {
      writer.write(coverage);
    } finally {
      writer.dispose();
    }

    return output.toByteArray();
  }

  private RasterGridRequest.BoundingBox deriveBoundingBox(
    double[] coords,
    double resolutionX,
    double resolutionY
  ) {
    double minX = Double.POSITIVE_INFINITY;
    double minY = Double.POSITIVE_INFINITY;
    double maxX = Double.NEGATIVE_INFINITY;
    double maxY = Double.NEGATIVE_INFINITY;

    for (int i = 0; i < coords.length; i += 2) {
      double x = coords[i];
      double y = coords[i + 1];
      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      maxX = Math.max(maxX, x);
      maxY = Math.max(maxY, y);
    }

    double padX = resolutionX / 2.0;
    double padY = resolutionY / 2.0;

    return new RasterGridRequest.BoundingBox(minX - padX, minY - padY, maxX + padX, maxY + padY);
  }

  record RasterGridRequest(
    String crsCode,
    double resolutionX,
    double resolutionY,
    BoundingBox bbox,
    Format format,
    String mediaType
  ) {
    enum Format {
      GEOTIFF,
    }

    record BoundingBox(double minX, double minY, double maxX, double maxY) {
      double width() {
        return maxX - minX;
      }

      double height() {
        return maxY - minY;
      }
    }
  }
}
