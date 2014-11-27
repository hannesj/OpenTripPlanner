/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.routing.impl;

import org.opentripplanner.routing.algorithm.GenericAStar;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.pathparser.BasicPathParser;
import org.opentripplanner.routing.pathparser.NoThruTrafficPathParser;
import org.opentripplanner.routing.pathparser.PathParser;
import org.opentripplanner.routing.services.GraphService;
import org.opentripplanner.routing.services.PathService;
import org.opentripplanner.routing.services.SPTService;
import org.opentripplanner.routing.spt.GraphPath;
import org.opentripplanner.routing.spt.ShortestPathTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SimpleAStarPathServiceImpl implements PathService {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleAStarPathServiceImpl.class);

    private GraphService graphService;

    private SPTServiceFactory sptServiceFactory;

    private SPTVisitor sptVisitor = null;

    public SimpleAStarPathServiceImpl(GraphService graphService, SPTServiceFactory sptServiceFactory) {
        this.graphService = graphService;
        this.sptServiceFactory = sptServiceFactory;
    }

    /**
     * Give up on searching for the first itinerary after this many seconds have elapsed.
     * A negative or zero value means search forever.
     */
    public double firstPathTimeout = 0; // seconds

    /**
     * Stop searching for additional itineraries (beyond the first one) after this many seconds
     * have elapsed, relative to the beginning of the search for the first itinerary.
     * A negative or zero value means search forever.
     * Setting this lower than the firstPathTimeout will avoid searching for additional
     * itineraries when finding the first itinerary takes a long time. This helps keep overall
     * response time down while assuring that the end user will get at least one response.
     */
    public double multiPathTimeout = 0; // seconds

    @Override
    public List<GraphPath> getPaths(RoutingRequest options) {


        GenericAStar sptService = (GenericAStar) sptServiceFactory.instantiate();

        ArrayList<GraphPath> paths = new ArrayList<>();

        // make sure the options has a routing context *before* cloning it (otherwise you get
        // orphan RoutingContexts leaving temporary edges in the graph until GC)
        if (options.rctx == null) {
            options.setRoutingContext(graphService.getGraph(options.routerId));
            options.rctx.pathParsers = new PathParser[] { new BasicPathParser(),
                    new NoThruTrafficPathParser() };
        }

        long searchBeginTime = System.currentTimeMillis();
        LOG.info("BEGIN SEARCH at:" + searchBeginTime);

        int originalItineraries = options.getNumItineraries();
        options.setNumItineraries(1);
        ShortestPathTree spt;
        long firstPathTimeoutAbsolute = searchBeginTime+(long)(firstPathTimeout*1000.0);
        long multiPathTimeoutAbsolute = searchBeginTime+(long)(multiPathTimeout*1000.0);

        while (paths.size() < originalItineraries ) {
            long subsearchBeginTime = System.currentTimeMillis();
            LOG.info("BEGIN SUBSEARCH at:" + (subsearchBeginTime-searchBeginTime) + " " + paths.size() );
            if ( paths.isEmpty() ) {
                sptService.startSearch(options, null, firstPathTimeoutAbsolute);
                if (sptService.runState == null) {
                    LOG.info("Did not get runState");
                    return null;
                }
            }
            else {
                LOG.info("Increase numItineraries");
                sptService.runState.options.numItineraries++;
            }
            long timeout = paths.isEmpty() ? firstPathTimeoutAbsolute : multiPathTimeoutAbsolute;
            LOG.info("Starting search" + sptService.runState.options.numItineraries + " " + (timeout-searchBeginTime));
            sptService.runSearch(timeout);
            spt = sptService.runState.spt;

            if (spt == null) {
                // Serious failure, no paths provided. This could be signaled with an exception.
                LOG.warn("Aborting search. {} paths found, elapsed time {} sec", 
                        paths.size(), (System.currentTimeMillis() - searchBeginTime) / 1000.0);
                break;
            }
            List<GraphPath> somePaths = spt.getPaths(); // somePaths may be empty, but is never null.
            LOG.info("END SUBSEARCH ({} msec of {} msec total)",
                    System.currentTimeMillis() - subsearchBeginTime,
                    System.currentTimeMillis() - searchBeginTime);
            LOG.info("SPT provides {} paths to target. Visited {} nodes", somePaths.size(), sptService.runState.nVisited);

            /* First, accumulate any new paths found into the list of itineraries. */
            for (GraphPath path : somePaths) {
                if ( ! paths.contains(path)) {
                    paths.add(path);
                    LOG.info("New trips: {}", path.getTrips());
                }
            }
            LOG.debug("{} / {} itineraries", paths.size(), options.numItineraries);
            if (options.rctx.aborted || System.currentTimeMillis() > firstPathTimeoutAbsolute || paths.size() == 0) {
                // search was cleanly aborted, probably due to a timeout. 
                // There may be useful paths, but we should stop retrying.
                break;
            }
        }
        if (paths.size() == 0) {
            return null;
        }
        // We order the list of returned paths by the time of arrival or departure (not path duration)
        Collections.sort(paths, new PathComparator(options.arriveBy));
        return paths;
    }

    public void setSPTVisitor(SPTVisitor sptVisitor){
        this.sptVisitor = sptVisitor;
    }
}