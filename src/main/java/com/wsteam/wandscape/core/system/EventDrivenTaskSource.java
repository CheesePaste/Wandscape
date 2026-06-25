package com.wsteam.wandscape.core.system;

import com.wsteam.wandscape.core.Log;
import com.wsteam.wandscape.core.boundary.EventBus;
import com.wsteam.wandscape.core.boundary.ResourceShortageHandler;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.event.MobNearby;
import com.wsteam.wandscape.core.event.ResourceLow;
import com.wsteam.wandscape.core.event.TaskAwaitingResources;
import com.wsteam.wandscape.core.event.TaskCompleted;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.task.*;
import com.wsteam.wandscape.core.types.*;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Pure event-driven TaskSource.
 * Subscribes to domain events and translates them into TaskRequests.
 * Its {@link #poll} is a no-op — all tasks are created reactively on event fire.
 *
 * <p>Subscribed events and their blueprint mappings:
 * <ul>
 *   <li>{@link ResourceLow} → {@code gather:<resourceId>} (补货采集)</li>
 *   <li>{@link TaskAwaitingResources} → same as ResourceLow, but higher priority</li>
 *   <li>{@link MobNearby} → {@code ritual:defense} (防御结界)</li>
 *   <li>{@link TaskCompleted} → stub: chain-reaction hook for future use</li>
 * </ul>
 *
 * <p>Blueprint auto-registration:
 * Call {@link #registerDefaultBlueprints(BlueprintRegistry)} before bootstrapping
 * to register {@code "gather:*"} and {@code "ritual:defense"} blueprints.
 */
public class EventDrivenTaskSource implements TaskSource {

    private static final String TAG = "EventSrc";

    // effectively-never poll interval; this source runs on events, not ticks
    private static final int NO_POLL = Integer.MAX_VALUE;

    private final GlobalTaskPool taskPool;
    private final Supplier<GridPos> defaultLocation;

    /**
     * V1 dedup: prevent create-gather → AWAITING_RESOURCES → TaskAwaitingResources event
     * → create-gather → ... infinite loop.
     *
     * Two layers:
     * 1. Time-based cooldown per resource (suppress bursts)
     * 2. Already-pending check: skip if any non-COMPLETED gather task for this resource exists
     */
    @Nullable
    private ResourceShortageHandler resourceHandler;

    private final Map<ResourceId, Long> lastGatherRequest = new HashMap<>();
    private static final long DUPLICATE_COOLDOWN_MS = 10_000;

    /**
     * Create and wire subscriptions.
     *
     * @param taskPool        the global pool to push tasks into
     * @param eventBus        bus to subscribe to
     * @param defaultLocation supplies a colony center or spawn point for tasks
     *                        that don't carry a position (e.g. ResourceLow)
     */
    public EventDrivenTaskSource(GlobalTaskPool taskPool, EventBus eventBus,
                                  Supplier<GridPos> defaultLocation) {
        this.taskPool = taskPool;
        this.defaultLocation = defaultLocation;

        eventBus.subscribe(ResourceLow.class, this::onResourceLow);
        eventBus.subscribe(TaskAwaitingResources.class, this::onTaskAwaitingResources);
        eventBus.subscribe(MobNearby.class, this::onMobNearby);
        eventBus.subscribe(TaskCompleted.class, this::onTaskCompleted);

        Log.info(TAG, "subscribed to ResourceLow, TaskAwaitingResources, MobNearby, TaskCompleted");
    }

    /** Set an optional handler for resource shortages (e.g. synthesize instead of gather). */
    public void setResourceShortageHandler(@Nullable ResourceShortageHandler handler) {
        this.resourceHandler = handler;
    }

    // ======================== TaskSource contract ========================

    @Override
    public int pollIntervalTicks() {
        return NO_POLL;
    }

    @Override
    public void poll(GlobalTaskPool pool, World world) {
        // All task creation is event-driven; nothing to poll.
    }

    // ======================== Event → TaskRequest translators ========================

    private void onResourceLow(ResourceLow e) {
        // Try engine-layer handler first (e.g. synthesize instead of gather)
        if (resourceHandler != null
                && resourceHandler.handle(e.resource(), e.threshold() - e.current(), defaultLocation.get())) {
            return;
        }
        int shortfall = e.threshold() - e.current();
        int amount = Math.max(shortfall, 16);
        String blueprintId = "gather:" + e.resource().id();
        tryCreateGather(e.resource(), blueprintId, amount, e.threshold(), 15,
                "ResourceLow");
    }

    private void onTaskAwaitingResources(TaskAwaitingResources e) {
        List<ResourceStack> needed = e.needed();
        if (needed.isEmpty()) return;
        // Use the first needed resource as the primary target for gather
        ResourceStack primary = needed.get(0);
        ResourceId resource = primary.resource();
        int amount = primary.amount();
        // Try engine-layer handler first (e.g. synthesize instead of gather)
        if (resourceHandler != null
                && resourceHandler.handle(resource, amount, defaultLocation.get())) {
            return;
        }
        String blueprintId = "gather:" + resource.id();
        tryCreateGather(resource, blueprintId, amount, amount, 40,
                "TaskAwaitingResources#" + e.taskId());
    }

    /**
     * Create a gather task, guarded by two layers:
     *
     * <b>Layer 1 — time cooldown:</b> suppress burst duplicates within 10s.
     *
     * <b>Layer 2 — sum threshold:</b> sum up the target amounts of all
     * active (non-COMPLETED) gather tasks for this resource.  If
     * {@code warehouseAvailable + inFlightSum >= minThreshold}, skip —
     * enough is already in transit.  Otherwise create a new gather for
     * the remaining shortfall (or at least the requested {@code amount}).
     */
    private void tryCreateGather(ResourceId resource, String blueprintId,
                                  int amount, int minThreshold, int priority,
                                  String source) {
        // Layer 1: time-based cooldown
        long now = System.currentTimeMillis();
        long last = lastGatherRequest.getOrDefault(resource, 0L);
        if (now - last < DUPLICATE_COOLDOWN_MS) {
            Log.debug(TAG, "skip gather:%s — cooldown (%s)", resource, source);
            return;
        }

        // Layer 2: sum in-flight amounts from existing active gathers
        String labelPrefix = "Gather " + resource.id();
        int inFlight = 0;
        for (var t : taskPool.all()) {
            if (t.state != TaskState.COMPLETED
                    && t.sequence.label().startsWith(labelPrefix)) {
                for (var op : t.sequence.steps()) {
                    if (op instanceof AtomicOp.ResourceRequestOp req) {
                        for (var item : req.items()) {
                            if (item.resource().equals(resource)) {
                                inFlight += item.amount();
                            }
                        }
                    }
                }
            }
        }

        if (inFlight >= minThreshold) {
            Log.debug(TAG, "skip gather:%s — inFlight=%d >= threshold=%d (%s)",
                    resource, inFlight, minThreshold, source);
            return;
        }

        int gap = minThreshold - inFlight;
        int finalAmount = Math.max(amount, gap);

        lastGatherRequest.put(resource, now);

        GridPos loc = defaultLocation.get();
        Map<String, JsonElement> params = new HashMap<>();
        params.put("amount", new JsonPrimitive(finalAmount));
        params.put("x", new JsonPrimitive(loc.x()));
        params.put("y", new JsonPrimitive(loc.y()));
        params.put("z", new JsonPrimitive(loc.z()));

        try {
            long taskId = taskPool.addTask(new TaskRequest(blueprintId, params, priority));
            Log.info(TAG, "%s → task #%d gather:%s amount=%d inFlight=%d gap=%d pri=%d",
                    source, taskId, resource, finalAmount, inFlight, gap, priority);
        } catch (IllegalArgumentException e) {
            Log.warn(TAG, "skip gather:%s — unknown blueprint, cannot auto-create (%s)",
                    resource, source);
        }
    }

    private void onMobNearby(MobNearby e) {
        Map<String, JsonElement> params = new HashMap<>();
        params.put("mobCount", new JsonPrimitive(e.count()));
        params.put("x", new JsonPrimitive(e.pos().x()));
        params.put("y", new JsonPrimitive(e.pos().y()));
        params.put("z", new JsonPrimitive(e.pos().z()));

        long taskId = taskPool.addTask(new TaskRequest(
                "ritual:defense",
                params,
                50 + Math.min(e.count(), 40)
        ));
        Log.info(TAG, "MobNearby(%s count=%d) → task #%d 'ritual:defense'",
                e.pos(), e.count(), taskId);
    }

    private void onTaskCompleted(TaskCompleted e) {
        Log.debug(TAG, "TaskCompleted(#%d by NPC %d) — no chain rules defined", e.taskId(), e.completedByNpcId());
    }

    // ======================== Blueprint auto-registration ========================

    /**
     * Register the default blueprints this source depends on.
     * Each {@code gather:<resource>} blueprint: ResourceRequestOp → TransformOp (place block as visual).
     * {@code ritual:defense}: single RitualOp(WARDING).
     */
    public static void registerDefaultBlueprints(BlueprintRegistry registry) {
        // Per-resource gather blueprints
        registry.register("gather:wood", (BlueprintSteps) params ->
                gatherSteps(ResourceId.WOOD, BlockType.OAK_PLANKS, params));

        registry.register("gather:stone_bricks", (BlueprintSteps) params ->
                gatherSteps(ResourceId.STONE_BRICKS, BlockType.STONE_BRICKS, params));

        registry.register("gather:stone", (BlueprintSteps) params ->
                gatherSteps(ResourceId.STONE, BlockType.STONE, params));

        registry.register("gather:glass", (BlueprintSteps) params ->
                gatherSteps(ResourceId.GLASS, BlockType.GLASS, params));

        registry.register("gather:iron_ingot", (BlueprintSteps) params ->
                gatherSteps(ResourceId.IRON_INGOT, BlockType.IRON_ORE, params));

        registry.register("gather:wheat", (BlueprintSteps) params ->
                gatherSteps(ResourceId.WHEAT, BlockType.DIRT, params));

        // Defense ritual
        registry.register("ritual:defense", (BlueprintSteps) params -> {
            String count = params.containsKey("mobCount")
                    ? params.get("mobCount").getAsString() : "1";
            GridPos loc = parseLocation(params);
            return new TaskSequence(
                    List.of(new AtomicOp.RitualOp(RitualId.WARDING, loc)),
                    "Defense Warding (mobs: " + count + ")");
        });

        Log.info(TAG, "registered default blueprints: gather:[wood,stone_bricks,stone,glass,iron_ingot,wheat], ritual:defense");
    }

    private static TaskSequence gatherSteps(ResourceId resource, BlockType visualBlock,
                                             Map<String, JsonElement> params) {
        int amount = params.containsKey("amount") ? params.get("amount").getAsInt() : 16;
        GridPos loc = parseLocation(params);
        return new TaskSequence(
                List.of(
                        new AtomicOp.ResourceRequestOp(new ResourceStack(resource, amount)),
                        AtomicOp.TransformOp.place(loc, visualBlock)
                ),
                "Gather " + resource.id() + " x" + amount);
    }

    /** Parse a GridPos from x/y/z params. Returns ORIGIN if missing. */
    private static GridPos parseLocation(Map<String, JsonElement> params) {
        try {
            int x = params.containsKey("x") ? params.get("x").getAsInt() : 0;
            int y = params.containsKey("y") ? params.get("y").getAsInt() : 0;
            int z = params.containsKey("z") ? params.get("z").getAsInt() : 0;
            return new GridPos(x, y, z);
        } catch (NumberFormatException | IllegalStateException e) {
            return GridPos.ORIGIN;
        }
    }
}
