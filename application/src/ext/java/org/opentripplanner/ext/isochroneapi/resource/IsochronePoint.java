package org.opentripplanner.ext.isochroneapi.resource;

/**
 * A location included in an isochrone response.
 *
 * <ul>
 *   <li>{@code id} is the stop id for transit stops or a vertex label for egress street vertices.</li>
 *   <li>{@code time} is the travel duration from the origin in seconds.</li>
 * </ul>
 */
public record IsochronePoint(String id, double latitude, double longitude, int time, Type type) {
  enum Type {
    STOP,
    EGRESS,
  }
}
