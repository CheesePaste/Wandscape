package com.wsteam.wandscape.road.core;

import java.util.*;
/**
 * An edge in the road network — a road segment connecting two nodes.
 * The path is stored as 3D points so terrain height is part of the data.
 * Mutable: status, pendingSegmentCount, and decorationTaskId change as road is built.
 *
 * <p>Segment completion is tracked by count rather than task ID:
 * {@link #incrementPendingSegments} / {@link #decrementPendingSegments}.
 * When the count reaches zero, all segments are complete.
 */
public class RoadEdge {

    public enum EdgeStatus {
        /** Edge planned but no segments published yet. */
        PLANNED,
        /** At least one segment task is in the pool or in-progress. */
        BUILDING,
        /** All segments have been built. */
        COMPLETE
    }

    private final UUID edgeId;
    private final UUID fromNodeId;
    private final UUID toNodeId;
    private final String tier;
    private final SplineModel spline;
    private final List<PathPoint> cachedPath;
    private final List<Long> segmentTaskIds;
    private EdgeStatus status;
    private Long decorationTaskId; // null until decoration enqueued
    private int pendingSegmentCount;
    private int width; // road width in blocks (default 3, editable in road editor)
    private final Set<UUID> completedSegmentIds = new HashSet<>();
    /** All block positions modified by this road edge (surface + fill + excavation + decoration). */
    private final Set<PathPoint> placedBlocks = new HashSet<>();
    /** Per-material demand (bare block id → count) for this edge's build segment. */
    private Map<String, Integer> materialCounts = Map.of();

    public RoadEdge(UUID edgeId, UUID fromNodeId, UUID toNodeId,
                    String tier, SplineModel spline) {
        this.edgeId = edgeId;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.tier = tier;
        this.spline = spline;
        this.cachedPath = generatePathCache(spline);
        this.segmentTaskIds = new ArrayList<>();
        this.status = EdgeStatus.PLANNED;
        this.width = 3;
    }

    /** Full constructor with status and existing task IDs (used by NBT load). */
    public RoadEdge(UUID edgeId, UUID fromNodeId, UUID toNodeId,
                    String tier, SplineModel spline,
                    List<Long> segmentTaskIds, EdgeStatus status) {
        this.edgeId = edgeId;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.tier = tier;
        this.spline = spline;
        this.cachedPath = generatePathCache(spline);
        this.segmentTaskIds = new ArrayList<>(segmentTaskIds);
        this.status = status;
        this.width = 3;
    }

    private static List<PathPoint> generatePathCache(SplineModel spline) {
        Set<PathPoint> unique = new java.util.LinkedHashSet<>();
        if (spline != null && !spline.getPoints().isEmpty()) {
            for (CurveSample sample : spline.tessellate(0.5)) {
                SplineVec3 pos = sample.position();
                unique.add(new PathPoint((int) Math.floor(pos.x()), (int) Math.floor(pos.y()), (int) Math.floor(pos.z())));
            }
        }
        return new ArrayList<>(unique);
    }

    // ---- Getters ----

    public UUID getEdgeId() { return edgeId; }
    public UUID getFromNodeId() { return fromNodeId; }
    public UUID getToNodeId() { return toNodeId; }
    public String getTier() { return tier; }
    public SplineModel getSpline() { return spline; }
    public List<PathPoint> getPath() { return List.copyOf(cachedPath); }
    public List<Long> getSegmentTaskIds() { return List.copyOf(segmentTaskIds); }
    public EdgeStatus getStatus() { return status; }
    public Long getDecorationTaskId() { return decorationTaskId; }

    /** All block positions modified by this edge. Immutable snapshot. */
    public Set<PathPoint> getPlacedBlocks() { return Set.copyOf(placedBlocks); }

    /** Add block positions that the road system placed or modified. */
    public void addPlacedBlocks(Collection<PathPoint> blocks) {
        placedBlocks.addAll(blocks);
    }

    /** Clear and replace all placed block positions. Used during NBT load. */
    public void setPlacedBlocks(Collection<PathPoint> blocks) {
        placedBlocks.clear();
        placedBlocks.addAll(blocks);
    }

    // ---- Mutators ----

    public void setStatus(EdgeStatus status) { this.status = status; }

    public void setDecorationTaskId(long taskId) { this.decorationTaskId = taskId; }

    public Map<String, Integer> getMaterialCounts() { return Map.copyOf(materialCounts); }

    public void setMaterialCounts(Map<String, Integer> counts) {
        this.materialCounts = counts != null ? Map.copyOf(counts) : Map.of();
    }

    /** Record the live segment task id for this edge (used to cancel on withdraw). */
    public void addSegmentTaskId(long taskId) {
        segmentTaskIds.add(taskId);
    }

    public int getWidth() { return width; }
    public void setWidth(int w) { this.width = w; }

    // ---- Segment completion tracking ----

    /** Called when segments are enqueued. Transitions PLANNED → BUILDING. */
    public void incrementPendingSegments(int count) {
        if (count <= 0) return;
        pendingSegmentCount += count;
        if (status == EdgeStatus.PLANNED) {
            status = EdgeStatus.BUILDING;
        }
    }

    /**
     * Record a segment as complete by its unique segment UUID (not task ID).
     * Guards against duplicate events for the same segment.
     *
     * @return true if this was the last pending segment (edge is now fully built)
     */
    public boolean recordSegmentComplete(UUID segmentId) {
        if (!completedSegmentIds.add(segmentId)) return false; // duplicate
        pendingSegmentCount = Math.max(0, pendingSegmentCount - 1);
        return pendingSegmentCount <= 0;
    }

    /**
     * Decrement the segment counter without dedup (fallback when segment_id is absent).
     * Safe because duplicate COMPLETE transitions are idempotent.
     *
     * @return true if this was the last pending segment
     */
    public boolean decrementAndCheckComplete() {
        pendingSegmentCount = Math.max(0, pendingSegmentCount - 1);
        return pendingSegmentCount <= 0;
    }

    public int getPendingSegmentCount() { return pendingSegmentCount; }

    @Override
    public String toString() {
        return "RoadEdge[id=" + edgeId + " from=" + fromNodeId
                + " to=" + toNodeId + " tier=" + tier
                + " status=" + status + " pathLen=" + cachedPath.size()
                + " width=" + width
                + " segments=" + segmentTaskIds.size() + "]";
    }
}
