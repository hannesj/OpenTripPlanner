package org.opentripplanner.routing.graph.kryosupport;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.ReferenceResolver;
import com.esotericsoftware.kryo.util.IdentityObjectIntMap;
import com.esotericsoftware.kryo.util.Util;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import java.io.PrintStream;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.opentripplanner.street.model.edge.Edge;
import org.opentripplanner.street.model.vertex.VertexLabel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reference resolver that splits objects into two buckets: Edge instances use a negative ID space
 * and everything else uses a non-negative ID space. This doubles the usable range of reference IDs
 * to avoid overflowing Integer.MAX_VALUE in large graphs.
 *
 * Optionally tracks how many reference IDs are assigned per class for diagnostics.
 */
public class PartitionedReferenceResolver implements ReferenceResolver {

  private static final int DEFAULT_CAPACITY = 2048;

  /**
   * Kryo reserves -1 and -2 for internal use, so start negative IDs at -3. The offset is applied
   * as {@code id = -(index + NEGATIVE_ID_OFFSET)} and reversed with {@code index = -id -
   * NEGATIVE_ID_OFFSET}.
   */
  private static final int NEGATIVE_ID_OFFSET = 3;
  private static final int LOG_STEP = 1_000_000;
  private static final Logger LOG = LoggerFactory.getLogger(PartitionedReferenceResolver.class);

  private final IdentityObjectIntMap<Object> edgeWrittenObjects = new IdentityObjectIntMap<>();
  private final IdentityObjectIntMap<Object> otherWrittenObjects = new IdentityObjectIntMap<>();
  private final ArrayList<Object> edgeReadObjects = new ArrayList<>();
  private final ArrayList<Object> otherReadObjects = new ArrayList<>();
  private final int maximumCapacity;
  private final boolean trackCounts;
  private final TObjectIntMap<Class<?>> referenceCounts;
  private final Set<Class<?>> extraNegativePartitionClasses;
  private long nextEdgeLogThreshold = LOG_STEP;
  private long nextOtherLogThreshold = LOG_STEP;

  public PartitionedReferenceResolver() {
    this(DEFAULT_CAPACITY, false);
  }

  public PartitionedReferenceResolver(boolean trackCounts) {
    this(DEFAULT_CAPACITY, trackCounts);
  }

  public PartitionedReferenceResolver(int maximumCapacity) {
    this(maximumCapacity, false);
  }

  public PartitionedReferenceResolver(int maximumCapacity, boolean trackCounts) {
    this.maximumCapacity = maximumCapacity;
    this.trackCounts = trackCounts;
    this.referenceCounts = trackCounts ? new TObjectIntHashMap<>() : null;
    this.extraNegativePartitionClasses = parseExtraNegativePartitionClasses();
  }

  @Override
  public void setKryo(Kryo kryo) {}

  @Override
  public int addWrittenObject(Object object) {
    if (isEdge(object)) {
      int idx = edgeWrittenObjects.size;
      int id = -(idx + NEGATIVE_ID_OFFSET); // negative ID space for vertices, skipping -1/-2
      edgeWrittenObjects.put(object, id);
      logProgress(true, idx + 1);
      track(object);
      return id;
    } else {
      int idx = otherWrittenObjects.size;
      // non-negative ID space for everything else
      otherWrittenObjects.put(object, idx);
      logProgress(false, idx + 1);
      track(object);
      return idx;
    }
  }

  @Override
  public int getWrittenId(Object object) {
    if (isEdge(object)) {
      return edgeWrittenObjects.get(object, -1);
    } else {
      return otherWrittenObjects.get(object, -1);
    }
  }

  @Override
  public int nextReadId(Class type) {
    if (isEdge(type)) {
      int idx = edgeReadObjects.size();
      edgeReadObjects.add(null);
      return -(idx + NEGATIVE_ID_OFFSET);
    } else {
      int idx = otherReadObjects.size();
      otherReadObjects.add(null);
      return idx;
    }
  }

  @Override
  public void setReadObject(int id, Object object) {
    if (id < 0) {
      int idx = -id - NEGATIVE_ID_OFFSET;
      edgeReadObjects.set(idx, object);
    } else {
      otherReadObjects.set(id, object);
    }
  }

  @Override
  public Object getReadObject(Class type, int id) {
    if (id < 0) {
      int idx = -id - NEGATIVE_ID_OFFSET;
      return idx < edgeReadObjects.size() ? edgeReadObjects.get(idx) : null;
    } else {
      return id < otherReadObjects.size() ? otherReadObjects.get(id) : null;
    }
  }

  @Override
  public void reset() {
    final int edgeSize = edgeReadObjects.size();
    final int otherSize = otherReadObjects.size();
    edgeReadObjects.clear();
    otherReadObjects.clear();
    if (edgeSize > maximumCapacity) {
      edgeReadObjects.trimToSize();
      edgeReadObjects.ensureCapacity(maximumCapacity);
    }
    if (otherSize > maximumCapacity) {
      otherReadObjects.trimToSize();
      otherReadObjects.ensureCapacity(maximumCapacity);
    }
    edgeWrittenObjects.clear(maximumCapacity);
    otherWrittenObjects.clear(maximumCapacity);
    nextEdgeLogThreshold = LOG_STEP;
    nextOtherLogThreshold = LOG_STEP;
  }

  @Override
  public boolean useReferences(Class type) {
    return (
      !Util.isWrapperClass(type) && !Util.isEnum(type) && !VertexLabel.class.isAssignableFrom(type)
    );
  }

  private boolean isEdge(Object object) {
    return object instanceof Edge || isInExtraNegativePartition(object.getClass());
  }

  private boolean isEdge(Class<?> type) {
    return (
      type != null && (Edge.class.isAssignableFrom(type) || isInExtraNegativePartition(type))
    );
  }

  private void track(Object object) {
    if (!trackCounts || object == null) {
      return;
    }
    referenceCounts.adjustOrPutValue(object.getClass(), 1, 1);
  }

  private boolean isInExtraNegativePartition(Class<?> type) {
    if (extraNegativePartitionClasses.isEmpty() || type == null) {
      return false;
    }
    for (Class<?> clazz : extraNegativePartitionClasses) {
      if (clazz.isAssignableFrom(type)) {
        return true;
      }
    }
    return false;
  }

  private Set<Class<?>> parseExtraNegativePartitionClasses() {
    String raw = System.getProperty("otp.kryo.partitionedResolver.negativeTypes");
    if (raw == null || raw.isBlank()) {
      return Collections.emptySet();
    }
    String[] classNames = raw.split(",");
    Set<Class<?>> result = new HashSet<>();
    for (String name : classNames) {
      try {
        result.add(Class.forName(name.trim()));
      } catch (ClassNotFoundException e) {
        LOG.warn(
          "PartitionedReferenceResolver: could not load extra negative partition class {}",
          name.trim()
        );
      }
    }
    return Collections.unmodifiableSet(result);
  }

  private void logProgress(boolean edge, int count) {
    if (!LOG.isInfoEnabled()) {
      return;
    }
    if (edge) {
      while (count >= nextEdgeLogThreshold) {
        LOG.info(
          "PartitionedReferenceResolver: edge references allocated: {}",
          nextEdgeLogThreshold
        );
        nextEdgeLogThreshold += LOG_STEP;
      }
    } else {
      while (count >= nextOtherLogThreshold) {
        LOG.info(
          "PartitionedReferenceResolver: non-edge references allocated: {}",
          nextOtherLogThreshold
        );
        nextOtherLogThreshold += LOG_STEP;
      }
    }
  }

  /** Clear accumulated tracking counts without touching resolver state. */
  public void resetReferenceCounts() {
    if (trackCounts) {
      referenceCounts.clear();
    }
  }

  /** Print reference counts to stdout in descending order. No-op when tracking is disabled. */
  public void summarize() {
    summarize(System.out);
  }

  /** Print reference counts to the provided stream in descending order. */
  public void summarize(PrintStream out) {
    if (!trackCounts) {
      LOG.info("PartitionedReferenceResolver: reference tracking disabled; no summary available.");
      return;
    }
    if (referenceCounts.isEmpty()) {
      LOG.info(
        "PartitionedReferenceResolver summary: no entries recorded (resolver may have been reset or tracking started late)."
      );
      return;
    }
    List<SimpleEntry<Class<?>, Integer>> entries = new ArrayList<>();
    referenceCounts.forEachEntry((clazz, count) -> {
      entries.add(new SimpleEntry<>(clazz, count));
      return true;
    });
    entries.sort(Comparator.comparing(SimpleEntry<Class<?>, Integer>::getValue).reversed());
    StringBuilder sb = new StringBuilder("PartitionedReferenceResolver summary:\n");
    for (SimpleEntry<Class<?>, Integer> entry : entries) {
      String line = entry.getValue() + " " + entry.getKey().getName();
      sb.append(line).append('\n');
      out.println(line);
    }
    LOG.info(sb.toString().trim());
  }
}
