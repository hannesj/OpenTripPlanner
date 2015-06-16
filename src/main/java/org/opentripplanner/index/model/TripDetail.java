package org.opentripplanner.index.model;

import org.onebusaway.gtfs.model.Trip;
import org.opentripplanner.routing.edgetype.TripPattern;

public class TripDetail extends TripShort{

    public PatternShort pattern;

    public TripDetail (Trip trip, TripPattern tripPattern) {
        super(trip);
        pattern = new PatternShort(tripPattern);
    }
}
