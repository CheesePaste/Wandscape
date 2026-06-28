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
import com.wsteam.wandscape.core.types.ResourceStack;
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

import javax.annotation.Nullable;
import com.wsteam.wandscape.shared.log.Log;

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

    private static final String TAG = "FailureAnalyzerSystem";
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

        ServerLevel level = getServerLevel();
        if (level == null) return;

        // ── 1. Re-check AWAITING_RESOURCES: wake if warehouse has enough ──
        checkAwaitingResources(world);

        // ── 2. Handle FAILED tasks (wand requirements, evaluation) ──
        List<GlobalTask> failedTasks = world.taskPool.getByState(TaskState.FAILED);
        if (failedTasks.isEmpty()) return;

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

    /**
     * Poll all AWAITING_RESOURCES tasks and transition back to PENDING_ASSIGN
     * when the warehouse has enough of the needed resource.
     */
    private void checkAwaitingResources(World world) {
        List<GlobalTask> waiting = world.taskPool.getByState(TaskState.AWAITING_RESOURCES);
        if (waiting.isEmpty()) return;

        int awakened = 0;
        for (GlobalTask task : waiting) {
            if (task.awaitingResource == null || task.awaitingResource.isEmpty()) continue;
            // All-or-nothing: ALL needed resources must be available
            boolean allAvailable = true;
            for (ResourceStack need : task.awaitingResource) {
                if (world.colonyResources.available(need.resource()) < need.amount()) {
                    allAvailable = false;
                    break;
                }
            }
            if (allAvailable) {
                task.state = TaskState.PENDING_ASSIGN;
                task.awaitingResource = null;
                task.schedulerRetryCount = 0;
                awakened++;
            }
        }
        if (awakened > 0) {
            Log.info(TAG, "[FailureAnalyzer] awakened {} AWAITING_RESOURCES tasks", awakened);
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

        // ── Step 0: Check if a suitable wand already exists in the warehouse ──
        UUID colonyId = extractColonyFromTask(task, level);
        if (colonyId == null) {
            Log.warn(TAG, "[FailureAnalyzer] cannot determine colony for task #{} '{}'",
                    task.id, task.sequence.label());
            recoveringTasks.add(task.id);
            return;
        }

        if (wandExistsInWarehouse(colonyId, reqs, level)) {
            task.state = TaskState.PENDING_ASSIGN;
            task.assignedNpcId = null;
            task.failureReason = null;
            task.schedulerRetryCount = 0;
            Log.info(TAG, "[FailureAnalyzer] wand for reqs={} found in warehouse → task #{} → PENDING_ASSIGN",
                    reqs, task.id);
            return;
        }

        // ── Step 1: Find a wand preset that satisfies the requirements ──
        String presetId = findPresetForRequirements(reqs);
        if (presetId == null) {
            Log.warn(TAG, "[FailureAnalyzer] no wand preset satisfies reqs={} for task #{}",
                    reqs, task.id);
            recoveringTasks.add(task.id); // permanent: no preset can satisfy this
            return;
        }

        // ── Step 2: Check if craft recipe exists ──
        if (Wandscape.PRODUCTION_RECIPE_LOADER == null
                || !Wandscape.PRODUCTION_RECIPE_LOADER.getCraftWandRecipes().contains(presetId)) {
            Log.warn(TAG, "[FailureAnalyzer] no craft recipe for preset={} (task #{})",
                    presetId, task.id);
            recoveringTasks.add(task.id); // permanent: recipe doesn't exist
            return;
        }
        var recipe = Wandscape.PRODUCTION_RECIPE_LOADER.getCraftWandRecipes().get(presetId);

        // ── Step 3: Check colony C/M/W against the preset's unlockRequirement ──
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
            Log.warn(TAG, "[FailureAnalyzer] colony {} C/M/W={}/{}/{} too low for {} "
                            + "(requires {}/{}/{}) — stopped, task #{}",
                    colonyId.toString().substring(0, 8),
                    api.getColonyComfort(colonyId),
                    api.getColonyMagic(colonyId),
                    api.getColonyWonder(colonyId),
                    presetId,
                    unlock.minComfort(), unlock.minMagic(), unlock.minWonder(),
                    task.id);
            recoveringTasks.add(task.id); // permanent: need more C/M/W buildings
            return;
        }

        // ── Step 4: Check if craft_wand for this preset is already in-flight ──
        if (isCraftWandInFlight(presetId, world)) {
            Log.debug(TAG, "[FailureAnalyzer] craft_wand for {} already in-flight, will retry task #{} next heartbeat",
                    presetId, task.id);
            // Don't add to recoveringTasks — next heartbeat step 0 will retry
            return;
        }

        // ── Step 5: Find a crafting station in the same colony ──
        List<UUID> stations = api.getBuildingsByCategory(colonyId, "crafting_station");
        if (stations.isEmpty()) {
            Log.warn(TAG, "[FailureAnalyzer] no crafting station in colony={} for task #{}",
                    colonyId.toString().substring(0, 8), task.id);
            recoveringTasks.add(task.id); // permanent: no crafting station to make wands
            return;
        }

        UUID stationId = stations.get(0);
        BlockPos stationPos = api.getBuilding(stationId).getPosition();

        // ── Step 6: Enqueue craft_wand production task ──
        Map<String, JsonElement> params = new LinkedHashMap<>();
        params.put("anchor", posToJsonArray(stationPos));
        params.put("recipe_id", new JsonPrimitive(presetId));
        params.put("count", new JsonPrimitive(1));
        params.put("channel_ticks", new JsonPrimitive(1200));
        params.put("mana_cost", new JsonPrimitive(5));

        WorkItem work = new WorkItem("production:craft_wand", params, 10);
        api.enqueueWork(stationId, work);
        // Don't add to recoveringTasks — next heartbeat step 0 finds wand in warehouse

        Log.info(TAG, "[FailureAnalyzer] enqueued craft_wand:{} at station {} colony={} "
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
            Log.info(TAG, "[FailureAnalyzer] selected {} (total_level={}) for reqs={}",
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

    /**
     * Check if the colony warehouse already contains a wand whose behaviors
     * cover the given requirements.
     */
    private boolean wandExistsInWarehouse(UUID colonyId,
                                                  Map<BehaviourTag, BehaviourLevel> reqs,
                                                  ServerLevel level) {
        var bank = com.wsteam.wandscape.warehouse.ColonyItemBank.get(level);
        if (bank == null) return false;
        for (var entry : bank.getSnapshot(colonyId).entrySet()) {
            if (!"wandscape:wand".equals(entry.getKey().itemId())) continue;
            if (entry.getValue() <= 0) continue;
            CompoundTag nbt = entry.getKey().nbt();
            if (nbt == null) continue;
            CompoundTag behaviors = nbt.getCompound("behaviors");
            if (behaviors.isEmpty()) continue;
            if (behaviorsCover(behaviors, reqs)) return true;
        }
        return false;
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
