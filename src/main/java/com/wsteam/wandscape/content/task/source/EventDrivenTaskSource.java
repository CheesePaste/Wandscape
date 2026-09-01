package com.wsteam.wandscape.content.task.source;

import com.google.gson.JsonElement;
import com.wsteam.wandscape.core.boundary.EventBus;
import com.wsteam.wandscape.core.boundary.ResourceShortageHandler;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.event.TaskCompleted;
import com.wsteam.wandscape.core.types.BlockType;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.content.task.op.api.AtomicOp;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.content.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTaskPool;
import com.wsteam.wandscape.content.task.runtime.TaskSequence;

import java.util.List;
import java.util.Map;

/**
 * Event-driven TaskSource. Subscribes to domain events and translates them
 * into TaskRequests. Currently handles {@link TaskCompleted} (chain-reaction
 * hook for future use).
 *
 * <p>Resource shortage recovery is now handled directly in
 * {@link GlobalTaskPool#markAwaitingResources}
 * via {@link ResourceShortageHandler}.
 */
public class EventDrivenTaskSource implements TaskSource {

    private static final String TAG = "EventSrc";

    private static final int NO_POLL = Integer.MAX_VALUE;

    public EventDrivenTaskSource(GlobalTaskPool taskPool, EventBus eventBus) {
        eventBus.subscribe(TaskCompleted.class, this::onTaskCompleted);
        Log.info(TAG, "subscribed to TaskCompleted");
    }

    @Override
    public int pollIntervalTicks() {
        return NO_POLL;
    }

    @Override
    public void poll(GlobalTaskPool pool, World world) {
        // All task creation is event-driven; nothing to poll.
    }

    private void onTaskCompleted(TaskCompleted e) {
    }

    // ======================== Blueprint auto-registration ========================

    /** Register default gather blueprints (ResourceRequestOp → TransformOp). */
    public static void registerDefaultBlueprints(BlueprintRegistry registry) {
        registry.register("gather:wood", params ->
                gatherSteps(ResourceId.WOOD, BlockType.OAK_PLANKS, params));

        registry.register("gather:stone_bricks", params ->
                gatherSteps(ResourceId.STONE_BRICKS, BlockType.STONE_BRICKS, params));

        registry.register("gather:stone", params ->
                gatherSteps(ResourceId.STONE, BlockType.STONE, params));

        registry.register("gather:glass", params ->
                gatherSteps(ResourceId.GLASS, BlockType.GLASS, params));

        registry.register("gather:iron_ingot", params ->
                gatherSteps(ResourceId.IRON_INGOT, BlockType.IRON_ORE, params));

        registry.register("gather:wheat", params ->
                gatherSteps(ResourceId.WHEAT, BlockType.DIRT, params));

        Log.info(TAG, "registered default blueprints: gather:[wood,stone_bricks,stone,glass,iron_ingot,wheat]");
    }

    private static TaskSequence gatherSteps(ResourceId resource, BlockType visualBlock,
                                            Map<String, JsonElement> params) {
        int amount = params.containsKey("amount") ? params.get("amount").getAsInt() : 16;
        GridPos loc = parseLocation(params);
        return new TaskSequence(
                List.of(
                        new AtomicOp.ResourceRequestOp(List.of(new ResourceStack(resource, amount))),
                        AtomicOp.TransformOp.place(loc, visualBlock)
                ),
                "Gather " + resource.id() + " x" + amount);
    }

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
