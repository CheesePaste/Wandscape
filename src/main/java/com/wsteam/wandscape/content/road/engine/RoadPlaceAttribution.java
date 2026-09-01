package com.wsteam.wandscape.content.road.engine;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks player road placements that are still in flight so the onboarding
 * "build a road" step only counts once the road is actually built.
 *
 * <p>{@code RoadPlacePacket} registers an attribution keyed by the task's
 * segment UUID when it publishes the build task; {@code RoadSegmentListener}
 * consumes it when {@code road_segment_complete} fires — i.e. after the
 * blueprint's place operations have put the blocks down.
 */
public final class RoadPlaceAttribution {

    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();

    /** A manual road placement awaiting build completion, tied to its player's colony. */
    public record Pending(UUID playerId, UUID colonyId) {}

    private RoadPlaceAttribution() {}

    public static void register(String segmentId, UUID playerId, UUID colonyId) {
        if (segmentId == null) return;
        PENDING.put(segmentId, new Pending(playerId, colonyId));
    }

    /** Remove and return the pending placement for a completed segment, or null. */
    public static Pending consume(String segmentId) {
        return segmentId == null ? null : PENDING.remove(segmentId);
    }
}
