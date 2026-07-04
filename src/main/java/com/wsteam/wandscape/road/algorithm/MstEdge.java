package com.wsteam.wandscape.road.algorithm;

/**
 * An edge in a Minimum Spanning Tree, referencing
 * indices into the input point list (not UUIDs).
 */
public record MstEdge(int fromIndex, int toIndex, int distance) {
}
