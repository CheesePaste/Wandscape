package com.wsteam.wandscape.core.road;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An edge in the road network — a road segment connecting two nodes.
 * Mutable: status and segmentTaskIds change as segments are built.
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
    private final List<XZPoint> path;
    private final List<Long> segmentTaskIds;
    private EdgeStatus status;

    public RoadEdge(UUID edgeId, UUID fromNodeId, UUID toNodeId,
                    String tier, List<XZPoint> path) {
        this.edgeId = edgeId;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.tier = tier;
        this.path = new ArrayList<>(path);
        this.segmentTaskIds = new ArrayList<>();
        this.status = EdgeStatus.PLANNED;
    }

    /** Full constructor with status and existing task IDs (used by NBT load). */
    public RoadEdge(UUID edgeId, UUID fromNodeId, UUID toNodeId,
                    String tier, List<XZPoint> path,
                    List<Long> segmentTaskIds, EdgeStatus status) {
        this.edgeId = edgeId;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.tier = tier;
        this.path = new ArrayList<>(path);
        this.segmentTaskIds = new ArrayList<>(segmentTaskIds);
        this.status = status;
    }

    // ---- Getters ----

    public UUID getEdgeId() { return edgeId; }
    public UUID getFromNodeId() { return fromNodeId; }
    public UUID getToNodeId() { return toNodeId; }
    public String getTier() { return tier; }
    public List<XZPoint> getPath() { return List.copyOf(path); }
    public List<Long> getSegmentTaskIds() { return List.copyOf(segmentTaskIds); }
    public EdgeStatus getStatus() { return status; }

    // ---- Mutators ----

    public void setStatus(EdgeStatus status) { this.status = status; }

    public void addSegmentTaskId(long taskId) {
        segmentTaskIds.add(taskId);
        if (status == EdgeStatus.PLANNED) {
            status = EdgeStatus.BUILDING;
        }
    }

    /** Check if all registered segments have been completed. */
    public boolean allSegmentsCompleted(List<Long> completedTaskIds) {
        if (segmentTaskIds.isEmpty()) return false;
        return completedTaskIds.containsAll(segmentTaskIds);
    }

    @Override
    public String toString() {
        return "RoadEdge[id=" + edgeId + " from=" + fromNodeId
                + " to=" + toNodeId + " tier=" + tier
                + " status=" + status + " pathLen=" + path.size()
                + " segments=" + segmentTaskIds.size() + "]";
    }
}
