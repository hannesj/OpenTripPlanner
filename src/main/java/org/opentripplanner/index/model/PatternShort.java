package org.opentripplanner.index.model;

import java.util.Collection;
import java.util.List;

import org.opentripplanner.routing.edgetype.TripPattern;

import com.beust.jcommander.internal.Lists;

public class PatternShort {

    public String id;
    public String desc;
    public String direction;
    public String shortName;
    public String longName;


    public PatternShort (TripPattern pattern) {
        this(pattern, false);
    }

    public PatternShort(TripPattern pattern, boolean detail) {
        id = pattern.code;
        desc = pattern.name;
        if (detail) {
            direction = pattern.getDirection();
            shortName = pattern.route.getShortName();
            longName = pattern.route.getLongName();
        }
    }
    
    public static List<PatternShort> list (Collection<TripPattern> in) {
        List<PatternShort> out = Lists.newArrayList();
        for (TripPattern pattern : in) out.add(new PatternShort(pattern));
        return out;
    }    
    
}
