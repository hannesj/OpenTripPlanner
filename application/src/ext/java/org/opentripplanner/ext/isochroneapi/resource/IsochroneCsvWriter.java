package org.opentripplanner.ext.isochroneapi.resource;

import java.util.List;

class IsochroneCsvWriter {

  private IsochroneCsvWriter() {}

  static String toCsv(List<StopArrival> arrivals) {
    StringBuilder csv = new StringBuilder();
    csv.append("stopId,latitude,longitude,time\n");

    for (StopArrival arrival : arrivals) {
      csv
        .append(arrival.stopId())
        .append(",")
        .append(arrival.latitude())
        .append(",")
        .append(arrival.longitude())
        .append(",")
        .append(arrival.time())
        .append("\n");
    }

    return csv.toString();
  }

  static String toExtendedCsv(List<IsochronePoint> points) {
    StringBuilder csv = new StringBuilder();
    csv.append("id,latitude,longitude,time,type\n");

    for (IsochronePoint point : points) {
      csv
        .append(point.id())
        .append(",")
        .append(point.latitude())
        .append(",")
        .append(point.longitude())
        .append(",")
        .append(point.time())
        .append(",")
        .append(point.type())
        .append("\n");
    }

    return csv.toString();
  }
}
