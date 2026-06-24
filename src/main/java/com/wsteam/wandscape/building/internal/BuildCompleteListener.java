package com.wsteam.wandscape.building.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.core.event.CustomEvent;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Subscribes to the engine-internal {@code EventBus} for {@code build_complete} events.
 * When a blueprint finishes placing all pattern blocks, this listener
 * verifies structure integrity and marks the building operational.
 */
public final class BuildCompleteListener {
    private static final Logger LOGGER = LogUtils.getLogger();

    private BuildCompleteListener() {}

    /**
     * Register this listener on the engine event bus.
     * Call after engine bootstrap in {@code onServerStarting}.
     */
    public static void register() {
        var world = com.wsteam.wandscape.engine.WandscapeEngine.getWorld();
        if (world == null || world.eventBus == null) {
            LOGGER.warn("Cannot register BuildCompleteListener — engine not bootstrapped");
            return;
        }

        world.eventBus.subscribe(CustomEvent.class, BuildCompleteListener::onBuildComplete);
        LOGGER.info("BuildCompleteListener registered on engine EventBus");
    }

    private static void onBuildComplete(CustomEvent event) {
        if (!"build_complete".equals(event.name())) return;

        Map<String, String> params = event.params();
        String anchorStr = params.get("anchor");
        String buildingName = params.get("building_name");

        if (anchorStr == null) {
            LOGGER.warn("build_complete event missing anchor — cannot verify building");
            return;
        }

        BlockPos anchor = parseAnchor(anchorStr);
        if (anchor == null) return;

        Level level = getServerLevel();
        if (level == null) return;

        BuildingSavedData data = BuildingSavedData.get(level);
        BuildingState state = findByAnchor(data, anchor);
        if (state == null) {
            LOGGER.debug("build_complete for unknown building at {} (name={}) — may be unregistered",
                    anchor, buildingName);
            return;
        }

        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        if (config == null) {
            LOGGER.warn("build_complete for {} — config not found", state.getBuildingTypeId());
            return;
        }

        List<BlockOffset> damaged = findDamagedBlocks(level, anchor, config);
        boolean intact = damaged.isEmpty();
        state.setStructureIntact(intact);
        data.setDirty();

        if (intact) {
            LOGGER.info("[Building] {} at {} construction complete — now operational",
                    state.getBuildingTypeId(), anchor);

            // Assign colony via ColonyApi
            com.wsteam.wandscape.shared.api.ColonyApi colonyApi =
                    com.wsteam.wandscape.shared.registry.WandscapeApis.getColonyApiSilently();
            if (colonyApi != null) {
                UUID assignedColonyId = colonyApi.onBuildingIntact(state);
                if (assignedColonyId != null && !assignedColonyId.equals(state.getColonyId())) {
                    // Colony was newly created or newly assigned
                    data.setDirty();
                    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                            new com.wsteam.wandscape.shared.event.BuildingPlacedEvent(
                                    state.getBuildingId(), assignedColonyId, state.getBuildingTypeId()));
                }
            }

            // Record contribution: only fires ColonyEvaluationChangedEvent when this
            // building type transitions from 0→1 intact buildings in the colony.
            UUID colonyId = state.getColonyId();
            if (colonyId != null) {
                boolean changed = data.addBuildingContribution(
                        colonyId, state.getBuildingTypeId());
                if (changed) {
                    LOGGER.info("[Evaluation] Colony {} gained +{} from first {}",
                            colonyId.toString().substring(0, 8),
                            data.getContributionRegistry().getSnapshot(colonyId),
                            state.getBuildingTypeId());
                }
            }
        } else {
            LOGGER.warn("[Building] {} at {} — {} blocks still damaged after build_complete, re-enqueuing partial repair",
                    state.getBuildingTypeId(), anchor, damaged.size());
            BuildingBreakHandler.enqueueRepairForOffsets(state, config, damaged);
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
            LOGGER.warn("Invalid anchor format: {}", s);
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
     */
    static List<BlockOffset> findDamagedBlocks(Level level, BlockPos anchor, BuildingConfig config) {
        List<BlockOffset> damaged = new ArrayList<>();
        for (BlockOffset offset : config.pattern()) {
            BlockPos target = anchor.offset(offset.x(), offset.y(), offset.z());
            String expectedKey = offset.toKey();
            String expectedSpec = config.blockMapping().get(expectedKey);
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
        return value != null ? value.toString() : "";
    }

    private static Level getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }
}
