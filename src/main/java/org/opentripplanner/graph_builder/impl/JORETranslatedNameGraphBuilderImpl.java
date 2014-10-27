package org.opentripplanner.graph_builder.impl;

import com.google.common.collect.ImmutableMap;
import org.apache.http.client.ClientProtocolException;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.opentripplanner.graph_builder.services.GraphBuilder;
import org.opentripplanner.gtfs.GtfsLibrary;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.vertextype.TransitStop;
import org.opentripplanner.routing.vertextype.TransitVertex;
import org.opentripplanner.util.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class JORETranslatedNameGraphBuilderImpl implements GraphBuilder{

    private static final Logger LOG = LoggerFactory.getLogger(JORETranslatedNameGraphBuilderImpl.class);

    private File path;

    private URL url;

    private static final Map<String, Integer> joreSchema = new ImmutableMap.Builder<String, Integer>()
            .put("koordinaatti1x", 7)
            .put("koordinaatti1y", 7)
            .put("koordinaatti2mx", 8)
            .put("koordinaatti2my", 8)
            .put("pysäkin nimi", 20)
            .put("nimi ruotsiksi", 20)
            .put("pysäkin osoite", 20)
            .put("osoite ruotsiksi", 20)
            .put("lähtölaituri", 3)
            .put("koordinaatti3x", 7)
            .put("koordinaatti3y", 7)
            .put("pysäkin paikannimi", 20)
            .put("paikannimi ruotsiksi", 20)
            .put("katos", 2)
            .put("’kk’+pys. lyhyttunnus", 6)
            .put("koordinaatti4x", 8)
            .put("koordinaatti4y", 8)
            .put("laskettu/mitattu",1)
            .put("esteettomyysluokka", 1)
            .put("pysäkin suunta", 15)
            .put("pysäkin säde", 3)
            .put("terminaali", 7)
            .put("empty", 1).build();

    public JORETranslatedNameGraphBuilderImpl(File path) {
        this.setPath(path);
    }

    public JORETranslatedNameGraphBuilderImpl(URL url) {
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

        LOG.info("Starting translation from JORE");
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
                    LOG.warn("invalid line "+line + " " + line.length() + lineSize);
                    continue;
                }
                Map<String, String> stopInformation = new HashMap<>();
                String stopId = line.substring(1, 8);
                int i=8;
                for(Map.Entry<String, Integer> entry : joreSchema.entrySet()){
                    int n = entry.getValue();
                    String value = line.substring(i, i=i+n ).trim();
                    if (!value.isEmpty()) {
                        stopInformation.put(entry.getKey(), value);
                    }
                }
                Vertex stop = graph.getVertex(GtfsLibrary.convertIdToString(new AgencyAndId("HSL", stopId)));
                if (!(stop instanceof TransitVertex)){
                    LOG.warn("Could not find stop HSL_" + stopId );
                    continue;
                }
                ((TransitVertex) stop).translatedName.addTranslation("fi", stopInformation.get("pysäkin nimi"));
                ((TransitVertex) stop).translatedName.addTranslation("sv", stopInformation.get("nimi ruotsiksi"));
            }
        } catch (IOException e){
            e.printStackTrace();
        }

    }

    @Override
    public List<String> provides() {
        return Arrays.asList("transit stop translations", "translation");
    }

    @Override
    public List<String> getPrerequisites() {
        return Arrays.asList("transit");
    }

    @Override
    public void checkInputs() {
        if (path != null) {
            if (!path.exists()) {
                throw new RuntimeException("JORE Stop path " + path + " does not exist.");
            }
            if (!path.canRead()) {
                throw new RuntimeException("JORE Stop path " + path + " cannot be read.");
            }
        } else if (url != null) {
            try {
                HttpUtils.testUrl(url.toExternalForm());
            } catch (ClientProtocolException e) {
                throw new RuntimeException("Error Stop to " + url.toExternalForm() + "\n" + e);
            } catch (IOException e) {
                throw new RuntimeException("JORE Stop url " + url.toExternalForm()
                        + " cannot be read.\n" + e);
            }
        }
    }
}
