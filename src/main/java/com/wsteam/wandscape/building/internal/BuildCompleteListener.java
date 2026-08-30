package com.wsteam.wandscape.building.internal;

import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.core.event.CustomEvent;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.service.ParticleService;
import com.wsteam.wandscape.shared.event.BuildingPlacedEvent;
import com.wsteam.wandscape.shared.log.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.*;

/**
 * Subscribes to the engine-internal {@code EventBus} for {@code build_complete} events.
 * When a blueprint finishes placing all pattern blocks, this listener
 * verifies structure integrity and marks the building operational.
 */
public final class BuildCompleteListener {
    private static final String TAG = "BuildCompleteListener";

    private BuildCompleteListener() {}

    /**
     * Register this listener on the engine event bus.
     * Call after engine bootstrap in {@code onServerStarting}.
     */
    public static void register() {
        var world = WandscapeEngine.getWorld();
        if (world == null || world.eventBus == null) {
            Log.warn(TAG, "Cannot register BuildCompleteListener — engine not bootstrapped");
            return;
        }

        world.eventBus.subscribe(CustomEvent.class, BuildCompleteListener::onBuildComplete);
        Log.info(TAG, "BuildCompleteListener registered on engine EventBus");
    }

    private static void onBuildComplete(CustomEvent event) {
        if (!"build_complete".equals(event.name())) return;

        Map<String, String> params = event.params();
        String anchorStr = params.get("anchor");
        String buildingName = params.get("building_name");

        if (anchorStr == null) {
            Log.warn(TAG, "build_complete event missing anchor — cannot verify building");
            return;
        }

        BlockPos anchor = parseAnchor(anchorStr);
        if (anchor == null) return;

        Level level = getServerLevel();
        if (level == null) return;

        BuildingSavedData data = BuildingSavedData.get(level);
        BuildingState state = findByAnchor(data, anchor);
        if (state == null) {
            return;
        }

        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        if (config == null) {
            Log.warn(TAG, "build_complete for {} — config not found", state.getBuildingTypeId());
            return;
        }

        List<BlockOffset> damaged = findDamagedBlocks(level, anchor, config, state.getRotationSteps());
        // 建筑不再因结构损坏而停摆：无论残留多少缺失方块，建成即判定完好并计入贡献，
        // 缺失方块可通过 V 面板「修复」手动补齐。
        state.setStructureIntact(true);
        // Sticky: once construction completes, never show the ghost again,
        // even if the building later becomes damaged.
        state.setHasEverCompleted(true);
        data.setDirty();

        // Refresh client caches: a completed building's construction ghost clears.
        com.wsteam.wandscape.shared.network.BuildingAreaSyncPacket.broadcastToColony(
                ServerLifecycleHooks.getCurrentServer(), anchor);

        if (damaged.isEmpty()) {
            Log.info(TAG, "[Building] {} at {} construction complete — now operational",
                    state.getBuildingTypeId(), anchor);
        } else {
            Log.info(TAG, "[Building] {} at {} — {}/{} blocks missing (still operational, repair via V panel)",
                    state.getBuildingTypeId(), anchor, damaged.size(), config.pattern().size());
        }

        // Assign colony via ColonyApi
        com.wsteam.wandscape.shared.api.ColonyApi colonyApi =
                com.wsteam.wandscape.shared.registry.WandscapeApis.getColonyApiSilently();
        if (colonyApi != null) {
            UUID assignedColonyId = colonyApi.onBuildingIntact(state);
            if (assignedColonyId != null && !assignedColonyId.equals(state.getColonyId())) {
                // Colony was newly created or newly assigned
                data.setDirty();
            }
        }

        // Always notify downstream systems when a building becomes intact.
        // Colony assignment may be null for the very first building;
        // downstream handlers (e.g. tourist spawner) check the registry anyway.
        NeoForge.EVENT_BUS.post(new BuildingPlacedEvent(
                state.getBuildingId(), state.getColonyId(), state.getBuildingTypeId()));

        // ── 建成庆祝：建筑包围盒一圈烟花；奇观建筑额外金色圣光柱 ──
        if (level instanceof ServerLevel srv) {
            ParticleService.celebrateRing(srv, state.getBounds(), 4);
            if ("wonder".equals(state.getCategory())) {
                ParticleService.burstColored(srv,
                        ParticleService.boundsCenterAbove(state.getBounds(), 2),
                        1.0f, 0.85f, 0.30f, 40, 0.14f, 40, true);
            }
        }

        // Record contribution: only fires ColonyEvaluationChangedEvent when this
        // building type transitions from 0→1 intact buildings in the colony.
        UUID colonyId = state.getColonyId();
        if (colonyId != null) {
            boolean changed = data.addBuildingContribution(
                    colonyId, state.getBuildingTypeId());
            if (changed) {
                Log.info(TAG, "[Evaluation] Colony {} gained +{} from first {}",
                        colonyId.toString().substring(0, 8),
                        data.getContributionRegistry().getSnapshot(colonyId),
                        state.getBuildingTypeId());
            }
        }
    }

    /** Parse "x,y,z" string into BlockPos. */
    private static BlockPos parseAnchor(String s) {
        String[] parts = s.split(",");
        if (parts.length != 3) return null;
        try {
            return new BlockPos(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            Log.warn(TAG, "Invalid anchor format: {}", s);
            return null;
        }
    }

    /** Find a building by anchor position. */
    private static BuildingState findByAnchor(BuildingSavedData data, BlockPos anchor) {
        for (BuildingState state : data.getAllBuildings()) {
            if (state.getAnchor().equals(anchor)) return state;
        }
        return null;
    }

    /**
     * Find all pattern blocks that don't match the expected state.
     * Returns an empty list if the building is fully intact.
     *
     * @param rotationSteps number of 90° CCW rotations applied to the building (0-3)
     */
    public static List<BlockOffset> findDamagedBlocks(Level level, BlockPos anchor, BuildingConfig config,
                                                        int rotationSteps) {
        java.util.List<BlockOffset> pattern = com.wsteam.wandscape.projection.BuildingRotation
                .rotateOffsets(config.pattern(), rotationSteps);
        java.util.Map<String, String> blockMapping = rotationSteps != 0
                ? com.wsteam.wandscape.projection.BuildingRotation.rotateBlockMapping(
                        config.blockMapping(), rotationSteps)
                : config.blockMapping();

        List<BlockOffset> damaged = new ArrayList<>();
        for (BlockOffset offset : pattern) {
            BlockPos target = anchor.offset(offset.x(), offset.y(), offset.z());
            String expectedKey = offset.toKey();
            String expectedSpec = blockMapping.get(expectedKey);
            if (expectedSpec == null) continue;

            BlockState actual = level.getBlockState(target);

            if (!blockMatchesSpec(actual, expectedSpec)) {
                damaged.add(offset);
            }
        }
        return damaged;
    }

    private static boolean blockMatchesSpec(BlockState actual, String expectedSpec) {
        String expectedBlockId = expectedSpec;
        java.util.Map<String, String> expectedProps = java.util.Collections.emptyMap();

        int bracket = expectedSpec.indexOf('[');
        if (bracket > 0 && expectedSpec.endsWith("]")) {
            expectedBlockId = expectedSpec.substring(0, bracket);
            String propsStr = expectedSpec.substring(bracket + 1, expectedSpec.length() - 1);
            expectedProps = new LinkedHashMap<>();
            for (String kv : propsStr.split(",")) {
                String[] parts = kv.split("=", 2);
                if (parts.length == 2) {
                    expectedProps.put(parts[0].trim(), parts[1].trim());
                }
            }
        }

        String actualId = actual.getBlock().builtInRegistryHolder().key().location().toString();
        if (!actualId.equals(expectedBlockId)) return false;

        for (var entry : expectedProps.entrySet()) {
            net.minecraft.world.level.block.state.properties.Property<?> prop =
                    actual.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (prop == null) return false;
            String actualValue = getPropertyValue(actual, prop);
            if (!entry.getValue().equals(actualValue)) return false;
        }
        return true;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static String getPropertyValue(BlockState state,
                                            net.minecraft.world.level.block.state.properties.Property<?> prop) {
        Comparable<?> value = state.getValue((net.minecraft.world.level.block.state.properties.Property) prop);
        return value != null ? ((net.minecraft.world.level.block.state.properties.Property) prop).getName(value) : "";
    }

    private static Level getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }
}
