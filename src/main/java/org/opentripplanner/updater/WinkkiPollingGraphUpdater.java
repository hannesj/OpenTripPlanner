package org.opentripplanner.updater;

import com.vividsolutions.jts.geom.Geometry;
import org.geotools.geojson.geom.GeometryJSON;
import org.opengis.feature.simple.SimpleFeature;
import org.opentripplanner.routing.alertpatch.Alert;
import org.opentripplanner.routing.alertpatch.TranslatedString;

import java.util.Date;

public class WinkkiPollingGraphUpdater extends WFSNotePollingGraphUpdater {

    private GeometryJSON g = new GeometryJSON(8);

    protected Alert getNote(SimpleFeature feature) {
        Alert alert = Alert.createSimpleAlerts("winkki:" + feature.getAttribute("licence_type"));
        alert.alertDescriptionText = feature.getAttribute("event_description") == null ? new TranslatedString("") : new TranslatedString(feature.getAttribute("event_description").toString());
        alert.effectiveStartDate = feature.getAttribute("licence_startdate") == null ? (Date) feature.getAttribute("event_startdate") : (Date) feature.getAttribute("licence_startdate");
        alert.effectiveEndDate = feature.getAttribute("licence_enddate") == null ? (Date) feature.getAttribute("event_enddate") : (Date) feature.getAttribute("licence_enddate");
        alert.geometry = g.toString((Geometry) feature.getDefaultGeometry());

        if (alert.effectiveStartDate.after(new Date()) || alert.effectiveEndDate.before(new Date()))
            return null;
        return alert;
    }

}