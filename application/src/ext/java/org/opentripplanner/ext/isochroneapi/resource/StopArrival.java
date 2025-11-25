package org.opentripplanner.ext.isochroneapi.resource;

/**
 * Represents a stop arrival in an isochrone response.
 * The {@code time} field contains the travel duration from the origin in seconds.
 */
public record StopArrival(String stopId, double latitude, double longitude, int time) {
  @Override
  public String toString() {
    return "StopArrival{stopId='" + stopId + "', time=" + time + "}";
  }
}
