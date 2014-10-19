package org.opentripplanner.inspector;

import org.opentripplanner.inspector.EdgeVertexTileRenderer.EdgeVertexRenderer;
import org.opentripplanner.inspector.EdgeVertexTileRenderer.EdgeVisualAttributes;
import org.opentripplanner.inspector.EdgeVertexTileRenderer.VertexVisualAttributes;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.edgetype.StreetBikeRentalLink;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.vertextype.BikeRentalStationVertex;
import org.opentripplanner.routing.vertextype.IntersectionVertex;
import org.opentripplanner.routing.vertextype.TransitVertex;

import java.awt.*;

/**
 * Created by hannes on 17/10/14.
 */
public class WheelchairEdgeRenderer implements EdgeVertexRenderer {


    private ScalarColorPalette slopePalette = new DefaultScalarColorPalette(0.0, 0.08, 1.0);


    private static final Color NOT_WHEELCHAIR_COLOR_EDGE = Color.RED;

    public WheelchairEdgeRenderer() {
    }

    @Override
    public boolean renderEdge(Edge e, EdgeVertexTileRenderer.EdgeVisualAttributes attrs) {
        if (e instanceof StreetEdge) {
            StreetEdge pse = (StreetEdge) e;
            if (!pse.isWheelchairAccessible()) {
                attrs.color = NOT_WHEELCHAIR_COLOR_EDGE;
                attrs.label = "wheelchair=no";
            } else {
                attrs.color = slopePalette.getColor(pse.getMaxSlope());
                attrs.label = String.format("%.02f", pse.getMaxSlope());
            }
        } else {
            return false;
        }
        return true;
    }

    @Override
    public boolean renderVertex(Vertex v, EdgeVertexTileRenderer.VertexVisualAttributes attrs) {
        if (v instanceof TransitVertex) {
            if(((TransitVertex) v).getStop().getWheelchairBoarding() == 0)
                attrs.color = Color.ORANGE;
            if(((TransitVertex) v).getStop().getWheelchairBoarding() == 1)
                attrs.color = Color.GREEN;
            if(((TransitVertex) v).getStop().getWheelchairBoarding() == 2)
                attrs.color = Color.PINK;
            attrs.label = v.getName();
        } else  {
            return false;
        }
        return true;
    }
}
