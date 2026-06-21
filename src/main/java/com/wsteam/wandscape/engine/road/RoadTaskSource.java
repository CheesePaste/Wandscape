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
 * Poll-based TaskSource that publishes pending road segments and
 * decorations to the engine task pool.
 *
 * <p>Segments are enqueued by {@link RoadEventListener} after road
 * network planning; decorations are enqueued when an edge transitions
 * to COMPLETE.
 *
 * <p>Poll interval: 20 ticks (1 second). Priority: 10 (lowest).
 */
public class RoadTaskSource implements TaskSource {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int POLL_INTERVAL_TICKS = 20;
    private static final int ROAD_PRIORITY = 10;

    // ---- Pending work items ----

    /** A road segment waiting to be published as a task. */
    public record PendingSegment(UUID segmentId, UUID edgeId, JsonArray tiles) {}

    /** A decoration batch waiting to be published as a task. */
    public record PendingDecoration(UUID decorationId, UUID edgeId, JsonArray tiles) {}

    private static final ConcurrentLinkedQueue<PendingSegment> pendingSegments =
            new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<PendingDecoration> pendingDecorations =
            new ConcurrentLinkedQueue<>();

    // ---- Enqueue (called from RoadEventListener) ----

    public static void enqueueSegment(PendingSegment seg) {
        pendingSegments.offer(seg);
    }

    public static void enqueueDecoration(PendingDecoration deco) {
        pendingDecorations.offer(deco);
        LOGGER.info("[RoadTaskSource] decoration enqueued: decoId={} edge={} tiles={} queueSize={}",
                deco.decorationId().toString().substring(0, 8),
                deco.edgeId().toString().substring(0, 8),
                deco.tiles().size(), pendingDecorations.size());
    }

    // ---- TaskSource implementation ----

    @Override
    public int pollIntervalTicks() {
        return POLL_INTERVAL_TICKS;
    }

    @Override
    public void poll(GlobalTaskPool pool, World world) {
        LOGGER.debug("[RoadTaskSource] poll: {} segments, {} decorations pending",
                pendingSegments.size(), pendingDecorations.size());
        publishSegments(pool);
        publishDecorations(pool);
    }

    private static void publishSegments(GlobalTaskPool pool) {
        List<PendingSegment> batch = drain(pendingSegments);
        if (batch.isEmpty()) return;

        LOGGER.info("[RoadTaskSource] publishing {} road segments", batch.size());
        for (PendingSegment s : batch) {
            Map<String, JsonElement> params = new HashMap<>();
            params.put("segment_id", new JsonPrimitive(s.segmentId().toString()));
            params.put("edge_id", new JsonPrimitive(s.edgeId().toString()));
            params.put("tiles", s.tiles());

            try {
                long taskId = pool.addTask(new TaskRequest(
                        "road:build_segment", params, ROAD_PRIORITY));
                LOGGER.info("[RoadTaskSource] segment task #{} edge={} tiles={}",
                        taskId, s.edgeId().toString().substring(0, 8), s.tiles().size());
            } catch (Exception e) {
                LOGGER.warn("[RoadTaskSource] failed to publish segment {}: {}",
                        s.segmentId(), e.getMessage());
            }
        }
    }

    private static void publishDecorations(GlobalTaskPool pool) {
        List<PendingDecoration> batch = drain(pendingDecorations);
        if (batch.isEmpty()) return;

        LOGGER.info("[RoadTaskSource] publishing {} decoration batches", batch.size());
        for (PendingDecoration d : batch) {
            Map<String, JsonElement> params = new HashMap<>();
            params.put("decoration_id", new JsonPrimitive(d.decorationId().toString()));
            params.put("edge_id", new JsonPrimitive(d.edgeId().toString()));
            params.put("tiles", d.tiles());

            try {
                long taskId = pool.addTask(new TaskRequest(
                        "road:build_decoration", params, ROAD_PRIORITY));
                LOGGER.info("[RoadTaskSource] decoration task #{} edge={} tiles={}",
                        taskId, d.edgeId().toString().substring(0, 8), d.tiles().size());
            } catch (Exception e) {
                LOGGER.warn("[RoadTaskSource] failed to publish decoration {}: {}",
                        d.decorationId(), e.getMessage());
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
