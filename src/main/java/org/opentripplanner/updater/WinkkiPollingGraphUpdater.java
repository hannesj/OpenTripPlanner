package org.opentripplanner.updater;

import com.vividsolutions.jts.geom.Geometry;
import org.geotools.data.FeatureSource;
import org.geotools.data.Query;
import org.geotools.data.wfs.WFSDataStore;
import org.geotools.data.wfs.WFSDataStoreFactory;
import org.geotools.feature.FeatureIterator;
import org.geotools.geojson.geom.GeometryJSON;
import org.geotools.referencing.CRS;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.FactoryException;
import org.opentripplanner.common.geometry.SphericalDistanceLibrary;
import org.opentripplanner.routing.alertpatch.Alert;
import org.opentripplanner.routing.alertpatch.TranslatedString;
import org.opentripplanner.routing.edgetype.PlainStreetEdge;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.graph.Graph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;

public class WinkkiPollingGraphUpdater extends PollingGraphUpdater{

    private static Logger LOG = LoggerFactory.getLogger(WinkkiPollingGraphUpdater.class);
    private static final double SEARCH_RADIUS_M = 30; // meters
    private static final double SEARCH_RADIUS_DEG = SphericalDistanceLibrary.metersToDegrees(SEARCH_RADIUS_M);

    private GraphUpdaterManager updaterManager;

    private String url;

    private String typeName;

    private Query query;
    private FeatureSource<SimpleFeatureType, SimpleFeature> source;

    private Set<StreetEdge> alertEdges = new HashSet<>();

    private GeometryJSON g = new GeometryJSON(8);

    // Here the updater can be configured using the properties in the file 'Graph.properties'.
    // The property frequencySec is already read and used by the abstract base class.
    @Override
    protected void configurePolling(Graph graph, Preferences preferences) throws Exception {
        url = preferences.get("url", null);
        typeName = preferences.get("typeName", "hkr:winkki_works");

        LOG.info("Configured Winkki polling updater: frequencySec={} and url={}",
                getFrequencySec(), url);
    }

    // Here the updater gets to know its parent manager to execute GraphWriterRunnables.
    @Override
    public void setGraphUpdaterManager(GraphUpdaterManager updaterManager) {
        LOG.info("Winkki polling updater: updater manager is set");
        this.updaterManager = updaterManager;
    }

    // Here the updater can be initialized.
    @Override
    public void setup() throws IOException{
        LOG.info("Setup Winkki polling updater");
        Map connectionParameters = new HashMap();
        connectionParameters.put(WFSDataStoreFactory.URL.key, new URL(url) );
        WFSDataStore data = (new WFSDataStoreFactory()).createDataStore(connectionParameters);
        query = new Query( typeName );
        try {
            query.setCoordinateSystem(CRS.decode("EPSG:4326", true));
        } catch (FactoryException e) {
            e.printStackTrace();
        }
        source = data.getFeatureSource(typeName);
    }

    // This is where the updater thread receives updates and applies them to the graph.
    // This method will be called every frequencySec seconds.
    @Override
    protected void runPolling() {
        LOG.info("Run Winkki polling updater with hashcode: {}", this.hashCode());
        // Execute example graph writer
        updaterManager.execute(new ExampleGraphWriter());
    }

    // Here the updater can cleanup after itself.
    @Override
    public void teardown() {
        LOG.info("Teardown example polling updater");
    }

    // This is a private GraphWriterRunnable that can be executed to modify the graph
    private class ExampleGraphWriter implements GraphWriterRunnable {

        @Override
        public void run(Graph graph) {
            FeatureIterator<SimpleFeature> features = null;
            Set<StreetEdge> newAlertEdges = new HashSet<>();

            try {
                features = source.getFeatures(query).features();
            } catch (IOException e) {
                e.printStackTrace();
            }
            while ( features.hasNext()){
                SimpleFeature feature = features.next();
                LOG.debug(feature.getAttribute("event_identifier").toString());
                if (feature.getDefaultGeometry() == null){
                    continue;
                }
                Geometry geom = (Geometry) feature.getDefaultGeometry();

                Alert a = Alert.createSimpleAlerts("winkki:" + feature.getAttribute("licence_type"));
                a.alertDescriptionText = feature.getAttribute("event_description") == null ? new TranslatedString("") : new TranslatedString(feature.getAttribute("event_description").toString());
                a.effectiveStartDate = feature.getAttribute("licence_startdate") == null ? (Date) feature.getAttribute("event_startdate") : (Date) feature.getAttribute("licence_startdate");
                a.effectiveEndDate = feature.getAttribute("licence_enddate") == null ? (Date) feature.getAttribute("event_enddate") : (Date) feature.getAttribute("licence_enddate");
                a.geometry = g.toString(geom);

                if (a.effectiveStartDate.after(new Date()) || a.effectiveEndDate.before(new Date()))
                    continue;

                Geometry searchArea = geom.buffer(SEARCH_RADIUS_DEG);
                Collection<StreetEdge> edges = graph.streetIndex.getEdgesForEnvelope(searchArea.getEnvelopeInternal());
                for(StreetEdge e: edges){
                    if (!(e instanceof PlainStreetEdge) || searchArea.disjoint(e.getGeometry()))
                        continue;
                    if (alertEdges.contains(e)){
                        removeAlerts(e);
                        alertEdges.remove(e);
                    }

                    Set<Alert> notes = e.getNotes();
                    if (notes == null){
                        notes = new HashSet<Alert>();
                        notes.add(a);
                        ((PlainStreetEdge) e).setNote(notes);
                    }
                    else {
                        notes.add(a);
                    }
                    newAlertEdges.add(e);
                    LOG.trace("Intersects with: " + e.getLabel());
                }
            }
            Iterator<StreetEdge> si = alertEdges.iterator();
            while (si.hasNext()){
                StreetEdge e = si.next();
                removeAlerts(e);
                si.remove();
            }
            alertEdges = newAlertEdges;
            LOG.info("Added " + newAlertEdges.size() + " edges with notes");
        }

        private void removeAlerts(StreetEdge e) {
            Set<Alert> edgeNotes = e.getNotes();
            Iterator<Alert> ni = edgeNotes.iterator();
            while (ni.hasNext()){
                Alert alert = ni.next();
                if (alert.alertHeaderText.getSomeTranslation().startsWith("winkki"))
                    ni.remove();
            }
        }
    }


}
