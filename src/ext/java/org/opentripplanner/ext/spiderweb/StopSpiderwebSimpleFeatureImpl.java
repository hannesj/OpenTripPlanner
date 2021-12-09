package org.opentripplanner.ext.spiderweb;

import org.locationtech.jts.geom.GeometryFactory;
import org.opentripplanner.common.geometry.GeometryUtils;
import org.opentripplanner.model.Stop;

public class StopSpiderwebSimpleFeatureImpl
        extends SpiderwebSimpleFeature {

    private final static GeometryFactory gf = GeometryUtils.getGeometryFactory();

    private final String mode;
    private final String time;
    private final Integer parent;
    private final String name;
    private final String trip;
    private final String route;
    private final String color;
    private final String departureTime;
    private final Boolean staySeated;


    public StopSpiderwebSimpleFeatureImpl(
            String id,
            Stop stop,
            String mode,
            String time,
            Integer parent
    ) {
        super(id, gf.createPoint(stop.getCoordinate().asJtsCoordinate()));
        this.mode = mode;
        this.time = time;
        this.parent = parent;
        this.name = stop.getName();
        this.trip = null;
        this.route = null;
        this.color = null;
        this.departureTime = null;
        this.staySeated = null;
    }

    public StopSpiderwebSimpleFeatureImpl(
            String id,
            Stop stop,
            String mode,
            String time,
            Integer parent,
            Boolean staySeated,
            String trip,
            String route,
            String color,
            String departureTime
    ) {
        super(id, gf.createPoint(stop.getCoordinate().asJtsCoordinate()));
        this.mode = mode;
        this.time = time;
        this.parent = parent;
        this.staySeated = staySeated;
        this.name = stop.getName();
        this.trip = trip;
        this.route = route;
        this.color = color != null ? '#' + color : null;
        this.departureTime = departureTime;
    }

    @Override
    public boolean hasUserData() {
        return super.hasUserData();
    }

    @Override
    public Object getAttribute(String s) {
        switch (s) {
            case "mode": return mode;
            case "time": return time;
            case "parent": return parent;
            case "name": return name;
            case "trip": return trip;
            case "route": return route;
            case "color": return color;
            case "departureTime": return departureTime;
            case "staySeated": return staySeated;
            default: return null;
        }
    }
}
