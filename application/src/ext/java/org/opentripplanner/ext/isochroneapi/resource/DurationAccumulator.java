package org.opentripplanner.ext.isochroneapi.resource;

import java.util.HashMap;
import java.util.Map;
import org.opentripplanner.raptor.api.debug.DebugEvent;
import org.opentripplanner.raptor.api.view.ArrivalView;

class DurationAccumulator {

  private final int[] bestDurations;

  DurationAccumulator(int nStops) {
    this.bestDurations = new int[nStops];
    for (int i = 0; i < nStops; ++i) {
      bestDurations[i] = Integer.MAX_VALUE;
    }
  }

  void stopArrival(DebugEvent<ArrivalView<?>> event) {
    if (event.action() != DebugEvent.Action.ACCEPT) return;
    int duration = event.element().arrivalTime() - event.iterationStartTime();
    if (duration < bestDurations[event.element().stop()]) {
      bestDurations[event.element().stop()] = duration;
    }
  }

  Map<Integer, Integer> toMap() {
    Map<Integer, Integer> map = new HashMap<>();
    for (int i = 0; i < bestDurations.length; ++i) {
      if (bestDurations[i] < Integer.MAX_VALUE) {
        map.put(i, bestDurations[i]);
      }
    }
    return map;
  }
}
