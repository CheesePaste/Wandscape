package com.wsteam.wandscape.road.engine;

import java.util.UUID;

import com.wsteam.wandscape.core.event.CustomEvent;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.road.core.RoadEdge;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Listens for {@code road_segment_complete} events and marks the
 * corresponding {@link RoadEdge} COMPLETE once all its segments are built.
 *
 * <p>Segments are enqueued by the manual road tools ({@code SplineBuildPacket});
 * this listener is the sole consumer of the segment-completion event so that
 * completed edges become walkable for routing.
 */
public final class RoadSegmentListener {

    private static final String TAG = "RoadSegmentListener";

    private RoadSegmentListener() {}

    public static void register() {
        var world = WandscapeEngine.getWorld();
        if (world == null || world.eventBus == null) {
            Log.warn(TAG, "Cannot register RoadSegmentListener — engine not bootstrapped");
            return;
        }
        world.eventBus.subscribe(CustomEvent.class, RoadSegmentListener::onEvent);
        Log.info(TAG, "RoadSegmentListener registered on engine EventBus");
    }

    private static void onEvent(CustomEvent event) {
        if ("road_segment_complete".equals(event.name())) {
            onSegmentComplete(event);
        }
    }

    private static void onSegmentComplete(CustomEvent event) {
        ServerLevel level = getServerLevel();
        if (level == null) {
            Log.warn(TAG, "[Road] onSegmentComplete: no server level");
            return;
        }

        String edgeIdStr = event.params().get("edge_id");
        String segIdStr = event.params().get("segment_id");

        if (edgeIdStr == null) {
            Log.warn(TAG, "[Road] segment_complete event missing edge_id — params={}",
                    event.params().keySet());
            return;
        }

        UUID edgeId;
        try {
            edgeId = UUID.fromString(edgeIdStr);
        } catch (IllegalArgumentException e) {
            Log.warn(TAG, "[Road] invalid edge_id in segment_complete: '{}'", edgeIdStr);
            return;
        }

        RoadSavedData roadData = RoadSavedData.getOrCreate(level);
        RoadEdge edge = roadData.getNetwork().getEdge(edgeId);
        if (edge == null) {
            Log.warn(TAG, "[Road] segment_complete for unknown edge {} (network has {} edges)",
                    edgeIdStr, roadData.getNetwork().edgeCount());
            return;
        }

        UUID segmentId = null;
        if (segIdStr != null) {
            try {
                segmentId = UUID.fromString(segIdStr);
            } catch (IllegalArgumentException e) {
                Log.warn(TAG, "[Road] invalid segment_id in event: '{}' — will count anyway", segIdStr);
            }
        }

        boolean allDone;
        if (segmentId != null) {
            allDone = edge.recordSegmentComplete(segmentId);
        } else {
            // No dedup — just decrement (safe: duplicate COMPLETE is idempotent)
            allDone = edge.decrementAndCheckComplete();
        }

        int remaining = edge.getPendingSegmentCount();
        Log.info(TAG, "[Road] segment_complete: edge={} status={} remaining={} allDone={}",
                edgeIdStr, edge.getStatus(), remaining, allDone);

        if (!allDone) return;

        edge.setStatus(RoadEdge.EdgeStatus.COMPLETE);
        roadData.markChanged();
        Log.info(TAG, "[Road] edge {} → COMPLETE", edgeIdStr);
    }

    private static ServerLevel getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }
}
