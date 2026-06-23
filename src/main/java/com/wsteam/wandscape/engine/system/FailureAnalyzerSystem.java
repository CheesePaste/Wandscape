package com.wsteam.wandscape.engine.system;

import java.util.*;

import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.core.ecs.System;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.task.GlobalTask;
import com.wsteam.wandscape.core.task.TaskFailureReason;
import com.wsteam.wandscape.core.task.TaskState;
import com.wsteam.wandscape.core.types.BehaviourLevel;
import com.wsteam.wandscape.core.types.BehaviourTag;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.wand.internal.WandPresetLoader;
import com.wsteam.wandscape.Wandscape;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import javax.annotation.Nullable;

/**
 * Monitors {@link TaskState#FAILED} tasks and attempts automated recovery.
 *
 * <p>Handles two failure modes:
 * <ul>
 *   <li>{@link TaskFailureReason.WandRequirementUnmet} — finds a wand preset that
 *       satisfies the missing requirements, locates a colony crafting station, and
 *       enqueues a {@code craft_wand} production task.</li>
 *   <li>{@link TaskFailureReason.ColonyEvaluationTooLow} — the colony's C/M/W values
 *       are insufficient to unlock the required wand preset; recovery is deferred
 *       until the colony's evaluation improves (no retry).</li>
 * </ul>
 *
 * <p>Runs on a 20-tick heartbeat to avoid overhead.
 */
public class FailureAnalyzerSystem implements System {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int HEARTBEAT = 20;

    private final WandPresetLoader presetLoader;
    private int tickCounter = 0;

    /** Task IDs already enqueued for recovery, to prevent duplicates. */
    private final Set<Long> recoveringTasks = new HashSet<>();

    public FailureAnalyzerSystem(WandPresetLoader presetLoader) {
        this.presetLoader = presetLoader;
    }

    /** Monotone counter to avoid checking C/M/W on every heartbeat for the same preset. */
    private int colonyCheckTick = 0;

    @Override
    public void update(World world, float delta) {
        tickCounter++;
        if (tickCounter % HEARTBEAT != 0) return;

        List<GlobalTask> failedTasks = world.taskPool.getByState(TaskState.FAILED);
        if (failedTasks.isEmpty()) return;

        ServerLevel level = getServerLevel();
        if (level == null) return;

        BuildingApi api = getBuildingApi();
        if (api == null) return;

        colonyCheckTick++;
        for (GlobalTask task : failedTasks) {
            if (recoveringTasks.contains(task.id)) continue;

            if (task.failureReason instanceof TaskFailureReason.WandRequirementUnmet wr) {
                handleWandRequirementUnmet(task, wr, level, api, world, colonyCheckTick);
            }
        }
    }

    private void handleWandRequirementUnmet(GlobalTask task,
                                            TaskFailureReason.WandRequirementUnmet reason,
                                            ServerLevel level,
                                            BuildingApi api,
                                            World world,
                                            int checkTick) {
        Map<BehaviourTag, BehaviourLevel> reqs = reason.requirements();
        if (reqs.isEmpty()) return;

        com.wsteam.wandscape.core.task.GlobalTaskPool taskPool = world.taskPool;

        // 1. Find a wand preset that satisfies the requirements
        String presetId = findPresetForRequirements(reqs);
        if (presetId == null) {
            LOGGER.warn("[FailureAnalyzer] no wand preset satisfies reqs={} for task #{}",
                    reqs, task.id);
            recoveringTasks.add(task.id); // don't retry
            return;
        }

        // 2. Check if craft recipe exists and look up its unlockRequirement
        if (Wandscape.PRODUCTION_RECIPE_LOADER == null
                || !Wandscape.PRODUCTION_RECIPE_LOADER.getCraftWandRecipes().contains(presetId)) {
            LOGGER.warn("[FailureAnalyzer] no craft recipe for preset={} (task #{})",
                    presetId, task.id);
            recoveringTasks.add(task.id);
            return;
        }
        var recipe = Wandscape.PRODUCTION_RECIPE_LOADER.getCraftWandRecipes().get(presetId);

        // 3. Determine colony from task anchor
        UUID colonyId = extractColonyFromTask(task, level);
        if (colonyId == null) {
            LOGGER.warn("[FailureAnalyzer] cannot determine colony for task #{} '{}'",
                    task.id, task.sequence.label());
            recoveringTasks.add(task.id);
            return;
        }

        // 4. Check colony C/M/W against the preset's unlockRequirement
        if (!com.wsteam.wandscape.production.internal.RecipeUnlockChecker
                .isUnlocked(colonyId, recipe.unlockRequirement())) {
            var unlock = recipe.unlockRequirement();
            taskPool.failTask(task.id,
                    new TaskFailureReason.ColonyEvaluationTooLow(
                            presetId,
                            unlock.minComfort(), unlock.minMagic(), unlock.minWonder(),
                            api.getColonyComfort(colonyId),
                            api.getColonyMagic(colonyId),
                            api.getColonyWonder(colonyId)));
            LOGGER.warn("[FailureAnalyzer] colony {} C/M/W={}/{}/{} too low for {} "
                            + "(requires {}/{}/{}) — stopped, task #{}",
                    colonyId.toString().substring(0, 8),
                    api.getColonyComfort(colonyId),
                    api.getColonyMagic(colonyId),
                    api.getColonyWonder(colonyId),
                    presetId,
                    unlock.minComfort(), unlock.minMagic(), unlock.minWonder(),
                    task.id);
            recoveringTasks.add(task.id);
            return;
        }

        // 6. Check if a craft_wand for this preset is already in-flight
        if (isCraftWandInFlight(presetId, world)) {
            LOGGER.info("[FailureAnalyzer] craft_wand for {} already in-flight, skip task #{}",
                    presetId, task.id);
            recoveringTasks.add(task.id);
            return;
        }

        // 7. Find a crafting station in the same colony (existence check only)
        List<UUID> stations = api.getBuildingsByCategory(colonyId, "crafting_station");
        if (stations.isEmpty()) {
            LOGGER.warn("[FailureAnalyzer] no crafting station in colony={} for task #{}",
                    colonyId.toString().substring(0, 8), task.id);
            recoveringTasks.add(task.id);
            return;
        }

        // 8. Pick the first registered crafting station (no structural/shutdown check)
        UUID stationId = stations.get(0);

        BlockPos stationPos = api.getBuilding(stationId).getPosition();

        // 9. Enqueue craft_wand production task
        Map<String, JsonElement> params = new LinkedHashMap<>();
        params.put("anchor", posToJsonArray(stationPos));
        params.put("recipe_id", new JsonPrimitive(presetId));
        params.put("count", new JsonPrimitive(1));
        params.put("channel_ticks", new JsonPrimitive(1200));
        params.put("mana_cost", new JsonPrimitive(5));

        // craft_wand has no wand requirement (cold-start prevention), so no overrides needed
        WorkItem work = new WorkItem("production:craft_wand", params, 10);
        api.enqueueWork(stationId, work);
        recoveringTasks.add(task.id);

        LOGGER.info("[FailureAnalyzer] enqueued craft_wand:{} at station {} colony={} "
                        + "to resolve task #{} '{}' reqs={}",
                presetId, stationId.toString().substring(0, 8),
                colonyId.toString().substring(0, 8),
                task.id, task.sequence.label(), reqs);
    }

    /**
     * Iterate all wand presets and return the one with the lowest total behavior
     * level sum among all presets that fully cover the given requirements.
     *
     * <p>Ties are broken by alphabetical preset ID for determinism.
     */
    @Nullable
    private String findPresetForRequirements(Map<BehaviourTag, BehaviourLevel> reqs) {
        String bestId = null;
        int bestSum = Integer.MAX_VALUE;

        for (var entry : presetLoader.getAllPresets().entrySet()) {
            String presetId = entry.getKey();
            CompoundTag nbt = entry.getValue().nbt();
            CompoundTag behaviors = nbt.getCompound("behaviors");
            if (behaviors.isEmpty()) continue;

            if (!behaviorsCover(behaviors, reqs)) continue;

            int sum = behaviorsSum(behaviors);
            if (sum < bestSum || (sum == bestSum && presetId.compareTo(bestId) < 0)) {
                bestSum = sum;
                bestId = presetId;
            }
        }

        if (bestId != null) {
            LOGGER.info("[FailureAnalyzer] selected {} (total_level={}) for reqs={}",
                    bestId, bestSum, reqs);
        }
        return bestId;
    }

    /** Sum all behavior level values in a wand's behaviors tag. */
    private static int behaviorsSum(CompoundTag behaviors) {
        int sum = 0;
        for (String key : behaviors.getAllKeys()) {
            sum += behaviors.getInt(key);
        }
        return sum;
    }

    private boolean behaviorsCover(CompoundTag behaviors,
                                   Map<BehaviourTag, BehaviourLevel> reqs) {
        for (var entry : reqs.entrySet()) {
            String key = mapToNbtKey(entry.getKey());
            int required = entry.getValue().value();
            if (!behaviors.contains(key)) return false;
            if (behaviors.getInt(key) < required) return false;
        }
        return true;
    }

    /** Extract colony UUID from the task's anchor parameter. */
    @Nullable
    private static UUID extractColonyFromTask(GlobalTask task, ServerLevel level) {
        JsonElement anchor = task.taskParams.get("anchor");
        if (!(anchor instanceof JsonArray arr) || arr.size() < 3) return null;

        try {
            BlockPos pos = new BlockPos(
                    arr.get(0).getAsInt(),
                    arr.get(1).getAsInt(),
                    arr.get(2).getAsInt());
            BuildingSavedData data = BuildingSavedData.get(level);
            UUID buildingId = data.getBuildingIdAt(pos);
            if (buildingId == null) return null;
            var state = data.getBuilding(buildingId);
            return state != null ? state.getColonyId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Check if a craft_wand task for the given preset is already active in the pool. */
    private static boolean isCraftWandInFlight(String presetId, World world) {
        for (GlobalTask t : world.taskPool.all()) {
            if (t.state == TaskState.COMPLETED || t.state == TaskState.FAILED) continue;
            if (!"production:craft_wand".equals(t.blueprintId)) continue;
            JsonElement recipeId = t.taskParams.get("recipe_id");
            if (recipeId != null && recipeId.isJsonPrimitive()
                    && presetId.equals(recipeId.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static JsonArray posToJsonArray(BlockPos pos) {
        JsonArray arr = new JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }

    @Nullable
    private static BuildingApi getBuildingApi() {
        try {
            return WandscapeApis.getBuildingApi();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    @Nullable
    private static ServerLevel getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }

    /** Map core BehaviourTag to the lowercase NBT key used in wand presets. */
    private static String mapToNbtKey(BehaviourTag tag) {
        return switch (tag) {
            case BUILDING           -> "building";
            case FARMING            -> "farming";
            case MINING             -> "mining";
            case LOGGING            -> "logging";
            case CRAFTING           -> "crafting";
            case GATHERING          -> "gathering";
            case RITUAL             -> "ritual";
            case ENTITY_INTERACTION -> "entity_interaction";
        };
    }
}
