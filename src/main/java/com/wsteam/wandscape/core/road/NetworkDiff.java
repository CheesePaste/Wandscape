package com.wsteam.wandscape.core.road;

import java.util.List;

/**
 * Result of comparing a freshly computed MST against
 * the existing road network during a rebuild.
 *
 * @param retained  edges present in both new MST and existing network
 * @param deprecated edges in existing network but absent from new MST
 * @param newEdges  edges in new MST but absent from existing network
 */
public record NetworkDiff(
        List<RoadEdge> retained,
        List<RoadEdge> deprecated,
        List<RoadEdge> newEdges) {
}
