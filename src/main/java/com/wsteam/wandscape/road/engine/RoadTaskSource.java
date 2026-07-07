package com.wsteam.wandscape.road.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.task.source.TaskSource;
import com.wsteam.wandscape.task.engine.pool.GlobalTaskPool;
import com.wsteam.wandscape.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Poll-based TaskSource that publishes pending road segments to the
 * engine task pool.
 *
 * <p>Segments are enqueued by {@link RoadEventListener} after road
 * network planning.
 *
 * <p>Poll interval: 20 ticks (1 second). Priority: 10 (lowest).
 */
public class RoadTaskSource implements TaskSource {

    private static final String TAG = "RoadTaskSource";
    private static final int POLL_INTERVAL_TICKS = 20;
    private static final int ROAD_PRIORITY = 10;

    // ---- Pending work items ----

    /** A road segment waiting to be published as a task. */
    public record PendingSegment(UUID segmentId, UUID edgeId, JsonArray tiles) {}

    private static final ConcurrentLinkedQueue<PendingSegment> pendingSegments =
            new ConcurrentLinkedQueue<>();

    // ---- Enqueue (called from RoadEventListener) ----

    public static void enqueueSegment(PendingSegment seg) {
        pendingSegments.offer(seg);
    }

    // ---- TaskSource implementation ----

    @Override
    public int pollIntervalTicks() {
        return POLL_INTERVAL_TICKS;
    }

    @Override
    public void poll(GlobalTaskPool pool, World world) {
        Log.debug(TAG, "[RoadTaskSource] poll: {} segments pending",
                pendingSegments.size());
        publishSegments(pool);
    }

    private static void publishSegments(GlobalTaskPool pool) {
        List<PendingSegment> batch = drain(pendingSegments);
        if (batch.isEmpty()) return;

        Log.info(TAG, "[RoadTaskSource] publishing {} road segments", batch.size());
        for (PendingSegment s : batch) {
            Map<String, JsonElement> params = new HashMap<>();
            params.put("segment_id", new JsonPrimitive(s.segmentId().toString()));
            params.put("edge_id", new JsonPrimitive(s.edgeId().toString()));
            params.put("tiles", s.tiles());

            try {
                long taskId = pool.addTask(new TaskRequest(
                        "road:build_segment", params, ROAD_PRIORITY));
                Log.info(TAG, "[RoadTaskSource] segment task #{} edge={} tiles={}",
                        taskId, s.edgeId().toString().substring(0, 8), s.tiles().size());
            } catch (Exception e) {
                Log.warn(TAG, "[RoadTaskSource] failed to publish segment {}: {}",
                        s.segmentId(), e.getMessage());
            }
        }
    }

    private static <T> List<T> drain(ConcurrentLinkedQueue<T> queue) {
        List<T> batch = new ArrayList<>();
        T item;
        while ((item = queue.poll()) != null) {
            batch.add(item);
        }
        return batch;
    }
}
