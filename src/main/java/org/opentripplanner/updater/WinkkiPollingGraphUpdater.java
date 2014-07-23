package org.opentripplanner.updater;

import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.data.Query;
import org.geotools.data.wfs.WFSDataStore;
import org.geotools.data.wfs.WFSDataStoreFactory;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.opengis.feature.Feature;
import org.opengis.feature.Property;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.FeatureType;
import org.opengis.geometry.TransfiniteSet;
import org.opengis.referencing.FactoryException;
import org.opentripplanner.analyst.core.GeometryIndex;
import org.opentripplanner.routing.alertpatch.Alert;
import org.opentripplanner.routing.alertpatch.TranslatedString;
import org.opentripplanner.routing.edgetype.PlainStreetEdge;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.services.StreetVertexIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;

import static org.geotools.referencing.CRS.getAuthorityFactory;

public class WinkkiPollingGraphUpdater extends PollingGraphUpdater{


    private static Logger LOG = LoggerFactory.getLogger(WinkkiPollingGraphUpdater.class);

    private GraphUpdaterManager updaterManager;

    private String url;

    private String typeName;

    private Query query;
    private FeatureSource<SimpleFeatureType, SimpleFeature> source;

    private Set<PlainStreetEdge> arrayEdges= new HashSet<>();

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
        ArrayList<String> winkkiAlerts = new ArrayList<String>();

        @Override
        public void run(Graph graph) {
            FeatureIterator<SimpleFeature> features = null;

            try {
                features = source.getFeatures(query).features();
            } catch (IOException e) {
                e.printStackTrace();
            }
            while ( features.hasNext()){
                SimpleFeature feature = features.next();
                LOG.info(feature.getAttribute("event_identifier").toString());
                if (feature.getDefaultGeometry() == null){
                    continue;
                }
                Geometry geom = (Geometry) feature.getDefaultGeometry();
                Collection<StreetEdge> edges = graph.streetIndex.getEdgesForEnvelope(geom.getEnvelopeInternal());
                for(StreetEdge e: edges){
                    if (!(e instanceof PlainStreetEdge) || geom.disjoint(e.getGeometry()))
                        continue;
                    HashSet<Alert> a = Alert.newSimpleAlertSet("winkki:" + feature.getAttribute("licence_type"));
                    a.iterator().next().alertDescriptionText = new TranslatedString(feature.getAttributes().toString());
                    a.iterator().next().effectiveStartDate = (Date) feature.getAttribute("event_startdate");
                    ((PlainStreetEdge) e).setNote(a);
                    LOG.info("Intersects with: " + e.getLabel());
                }
            }
        }
    }
}
