package org.opentripplanner.graph_builder.impl;

import com.google.common.collect.ImmutableMap;
import org.apache.http.client.ClientProtocolException;
import org.opentripplanner.graph_builder.services.GraphBuilder;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.vertextype.TransitStop;
import org.opentripplanner.util.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.namespace.QName;
import java.io.*;
import java.net.URL;
import java.util.*;


public class JOREAccessibilityGraphBuilderImpl implements GraphBuilder{

    private static final Logger LOG = LoggerFactory.getLogger(JOREAccessibilityGraphBuilderImpl.class);

    private File path;

    private URL url;

    private static final Map<String, Integer> joreSchema = new ImmutableMap.Builder<String, Integer>()
            .put("sivukaltevuus", 4)
            .put("pituuskaltevuus", 4)
            .put("min_leveys", 4)
            .put("max_leveys", 4)
            .put("syvyys", 4)
            .put("korotus_ajorataan", 4)
            .put("korotus_kaytavaan", 4)
            .put("takakaide_korkeus", 4)
            .put("suojaava_korkeus", 4)
            .put("alapiena_korkeus", 4)
            .put("penkki_korkeus", 4)
            .put("roska_astia", 1)
            .put("vaara", 1)
            .put("katos", 1)
            .put("valaistus", 1)
            .put("varoitusalue", 1)
            .put("erotus_varoitusalue", 1)
            .put("erotus_odotusalue", 1)
            .put("esteeton_kulku", 1)
            .put("pyoratie_samassa", 1)
            .put("pyoratie_sijainti", 1)
            .put("pysakin_malli", 1)
            .put("esteettomyys", 1)
            .put("luokka", 1)
            .put("huomioitavaa", 119).build();

    public JOREAccessibilityGraphBuilderImpl(File path) {
        this.setPath(path);
    }

    public JOREAccessibilityGraphBuilderImpl(URL url) {
        this.setUrl(url);
    }

    void setPath(File path) {
        this.path = path;
    }

    void setUrl(URL url) {
        this.url = url;
    }

    @Override
    public void buildGraph(Graph graph, HashMap<Class<?>, Object> extra) {
        BufferedReader reader = null;


        if (path != null) {
            try {
                reader = new BufferedReader(new FileReader(path));
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            try {
                InputStream is = url.openConnection().getInputStream();
                reader = new BufferedReader(new InputStreamReader(is));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        int lineSize = 8;
        for (Integer i : joreSchema.values()){
            lineSize += i;
        }

        String line = null;
        try {
            while ((line = reader.readLine()) != null) {
                if (line.length() != lineSize){
                    continue;
                }
                Map<String, String> accessibilityInformation = new HashMap<>();
                String stopId = line.substring(1, 8);
                int i=8;
                for(Map.Entry<String, Integer> entry : joreSchema.entrySet()){
                    int n = entry.getValue();
                    String value = line.substring(i, i=i+n ).trim();
                    if (!value.isEmpty()) {
                        accessibilityInformation.put(entry.getKey(), value);
                    }
                }
                Vertex stop = graph.getVertex("HSL_" + stopId);
                if (!(stop instanceof TransitStop)){
                    LOG.warn("Could not find stop HSL_" + stopId );
                    continue;
                }
                ((TransitStop) stop).accessibilityInformation = accessibilityInformation;
            }
        } catch (IOException e){
            e.printStackTrace();
        }

    }

    @Override
    public List<String> provides() {
        return Arrays.asList("transit accessibility information", "linking");
    }

    @Override
    public List<String> getPrerequisites() {
        return Arrays.asList("transit");
    }

    @Override
    public void checkInputs() {
        if (path != null) {
            if (!path.exists()) {
                throw new RuntimeException("JORE Accessibility path " + path + " does not exist.");
            }
            if (!path.canRead()) {
                throw new RuntimeException("JORE Accessibility path " + path + " cannot be read.");
            }
        } else if (url != null) {
            try {
                HttpUtils.testUrl(url.toExternalForm());
            } catch (ClientProtocolException e) {
                throw new RuntimeException("Error connecting to " + url.toExternalForm() + "\n" + e);
            } catch (IOException e) {
                throw new RuntimeException("JORE Accessibility url " + url.toExternalForm()
                        + " cannot be read.\n" + e);
            }
        }
    }
}
