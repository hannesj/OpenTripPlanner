package org.opentripplanner.routing.algorithm.strategies;

import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.spt.ShortestPathTree;
import org.opentripplanner.routing.vertextype.PoiVertex;

import java.util.LinkedList;
import java.util.List;

public class PoiClassTerminationStrategy implements SearchTerminationStrategy{

    private final int numItineraries;

    private final String category;

    public List<Vertex> foundVertices = new LinkedList<>();

    public PoiClassTerminationStrategy(String category, int numItineraries){
        this.category = category;
        this.numItineraries = numItineraries;
    }

    public PoiClassTerminationStrategy(String category){
        this(category, 1);
    }


    @Override
    public boolean shouldSearchTerminate(Vertex origin, Vertex target, State current, ShortestPathTree spt, RoutingRequest traverseOptions) {
        Vertex currentVertex = current.getVertex();
        if (currentVertex instanceof PoiVertex){
            if (((PoiVertex) currentVertex).categories.contains(category)){
                if (!foundVertices.contains(currentVertex)) {
                    foundVertices.add(currentVertex);
                    if (foundVertices.size() >= numItineraries){
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
