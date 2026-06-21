package com.wsteam.wandscape.engine.road;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.system.TaskSource;
import com.wsteam.wandscape.core.task.GlobalTaskPool;
import com.wsteam.wandscape.core.task.TaskRequest;

/**
 * Poll-based TaskSource that publishes pending road segments
 * to the engine task pool. Segments are enqueued by
 * {@link RoadEventListener} after road network planning.
 *
 * <p>Poll interval: 20 ticks (1 second). Priority: 10 (lowest).
 */
public class RoadTaskSource implements TaskSource {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int POLL_INTERVAL_TICKS = 20;
    private static final int ROAD_PRIORITY = 10;

    /** Shared queue populated by RoadEventListener. */
    private static final ConcurrentLinkedQueue<PendingSegment> pendingQueue =
            new ConcurrentLinkedQueue<>();

    /**
     * A road segment waiting to be published as a task.
     *
     * @param segmentId unique ID for this segment
     * @param edgeId    the edge this segment belongs to
     * @param tiles     pre-built JsonArray of {pos, block} objects
     */
    public record PendingSegment(UUID segmentId, UUID edgeId, JsonArray tiles) {
    }

    /** Enqueue a segment for later publication. Called by RoadEventListener. */
    public static void enqueueSegment(PendingSegment seg) {
        pendingQueue.offer(seg);
    }

    @Override
    public int pollIntervalTicks() {
        return POLL_INTERVAL_TICKS;
    }

    @Override
    public void poll(GlobalTaskPool pool, World world) {
        // Drain all pending segments
        List<PendingSegment> batch = new ArrayList<>();
        PendingSegment seg;
        while ((seg = pendingQueue.poll()) != null) {
            batch.add(seg);
        }

        if (batch.isEmpty()) return;

        LOGGER.info("[RoadTaskSource] publishing {} road segments", batch.size());

        for (PendingSegment s : batch) {
            Map<String, JsonElement> params = new HashMap<>();
            params.put("segment_id", new JsonPrimitive(s.segmentId().toString()));
            params.put("edge_id", new JsonPrimitive(s.edgeId().toString()));
            params.put("tiles", s.tiles()); // JsonArray injected as runtime param

            try {
                long taskId = pool.addTask(new TaskRequest(
                        "road:build_segment", params, ROAD_PRIORITY));
                LOGGER.info("[RoadTaskSource] published task #{} segment={} edge={} tiles={}",
                        taskId, s.segmentId().toString().substring(0, 8),
                        s.edgeId().toString().substring(0, 8), s.tiles().size());
            } catch (Exception e) {
                LOGGER.warn("[RoadTaskSource] failed to publish segment {}: {}",
                        s.segmentId(), e.getMessage());
            }
        }
    }
}
