package com.wsteam.wandscape.content.building.internal;
import com.wsteam.wandscape.content.task.boundary.EventBus;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.building.network.BuildingAreaSyncPacket;
import com.wsteam.wandscape.content.colony.event.ColonyEvaluationChangedEvent;

import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.projection.BuildingRotation;
import com.wsteam.wandscape.content.task.event.CustomEvent;
import com.wsteam.wandscape.foundation.service.ParticleService;
import com.wsteam.wandscape.content.building.event.BuildingPlacedEvent;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
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
        var world = com.wsteam.wandscape.content.task.ecs.World.getActive();
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
        // Resolve by building_id first — anchors are no longer unique once bounding
        // boxes may overlap. Fall back to the anchor search for legacy events that
        // predate the id tag.
        BuildingState state = findById(data, params.get("building_id"));
        if (state == null) state = findByAnchor(data, anchor);
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
        com.wsteam.wandscape.content.building.network.BuildingAreaSyncPacket.broadcastToColony(
                ServerLifecycleHooks.getCurrentServer(), anchor);

        if (damaged.isEmpty()) {
            Log.info(TAG, "[Building] {} at {} construction complete — now operational",
                    state.getBuildingTypeId(), anchor);
        } else {
            Log.info(TAG, "[Building] {} at {} — {}/{} blocks missing (still operational, repair via V panel)",
                    state.getBuildingTypeId(), anchor, damaged.size(), config.pattern().size());
        }

        // Assign colony via ColonyApiImpl
        UUID assignedColonyId = com.wsteam.wandscape.content.colony.ColonyApiImpl.get().onBuildingIntact(state);
        if (assignedColonyId != null && !assignedColonyId.equals(state.getColonyId())) {
            // Colony was newly created or newly assigned
            data.setDirty();
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

    /** Find a building by its id string (from a completion event); null when absent/invalid. */
    @Nullable
    private static BuildingState findById(BuildingSavedData data, String buildingIdStr) {
        if (buildingIdStr == null || buildingIdStr.isEmpty()) return null;
        try {
            return data.getBuilding(UUID.fromString(buildingIdStr));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Find all pattern blocks that don't match the expected state.
     * Returns an empty list if the building is fully intact.
     *
     * @param rotationSteps number of 90° CCW rotations applied to the building (0-3)
     */
    public static List<BlockOffset> findDamagedBlocks(Level level, BlockPos anchor, BuildingConfig config,
                                                        int rotationSteps) {
        int steps = rotationSteps & 3;

        // 「预期是什么方块」按 palette 预解析（几百项），而不是按 pattern 逐格现算
        // （几十万项）。原先要造一张 44 万条 "x,y,z" String 键的 HashMap，建筑旋转过时
        // 还要再逐条把方块状态字符串解析、旋转、序列化回去 —— 大建筑上那一步是秒级的，
        // 而打开工地面板就会走这里。旋转整张 palette 是 O(palette)，仓库里已有工具。
        Expected[] byPalette = parseExpectedPalette(
                BuildingRotation.rotatePalette(config.palette(), steps));

        List<BlockOffset> pattern = config.pattern();
        List<Integer> indices = config.blockIndices();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int ax = anchor.getX(), ay = anchor.getY(), az = anchor.getZ();

        List<BlockOffset> damaged = new ArrayList<>();
        for (int i = 0; i < pattern.size(); i++) {
            Expected expected = byPalette[indices.get(i)];
            if (expected == null) continue;   // 空气标记：「这格不属于本建筑」，不参与校验
            BlockOffset off = pattern.get(i);
            cursor.set(ax + off.x(), ay + off.y(), az + off.z());
            if (!expected.matches(level.getBlockState(cursor))) {
                damaged.add(BuildingRotation.rotateOffset(off, steps));
            }
        }
        return damaged;
    }

    /**
     * 一条 palette 条目的「预期样子」：方块本身 + 需要核对的状态属性。
     * {@code props} 为 null（本模组 93% 的方块如此）时，一次引用比较就能判定，
     * 完全不必碰字符串。
     */
    private record Expected(Block block, @Nullable Map<Property<?>, String> props) {
        boolean matches(BlockState actual) {
            if (actual.getBlock() != block) return false;
            if (props == null) return true;
            for (var entry : props.entrySet()) {
                Comparable<?> value = valueOf(actual, entry.getKey());
                if (value == null) return false;
                if (!entry.getValue().equals(nameOf(entry.getKey(), value))) return false;
            }
            return true;
        }
    }

    /** 解析整张 palette；数组里为 null 的槽位表示「这格不存在」或该条目无法解析。 */
    private static Expected[] parseExpectedPalette(List<String> palette) {
        Expected[] out = new Expected[palette.size()];
        for (int p = 0; p < palette.size(); p++) {
            out[p] = parseExpected(palette.get(p));
        }
        return out;
    }

    @Nullable
    private static Expected parseExpected(String spec) {
        String baseId = spec;
        String propsStr = null;
        int bracket = spec.indexOf('[');
        if (bracket > 0 && spec.endsWith("]")) {
            baseId = spec.substring(0, bracket);
            propsStr = spec.substring(bracket + 1, spec.length() - 1);
        }

        ResourceLocation rl;
        try {
            rl = ResourceLocation.parse(baseId);
        } catch (RuntimeException e) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.get(rl);
        if (block == null) return null;

        if (propsStr == null || propsStr.isEmpty()) return new Expected(block, null);

        Map<Property<?>, String> props = new LinkedHashMap<>();
        for (String kv : propsStr.split(",")) {
            String[] parts = kv.split("=", 2);
            if (parts.length != 2) continue;
            Property<?> prop = block.getStateDefinition().getProperty(parts[0].trim());
            if (prop != null) {
                props.put(prop, parts[1].trim());
            }
        }
        return new Expected(block, props.isEmpty() ? null : props);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Comparable<?> valueOf(BlockState state, Property<?> prop) {
        return state.getValue((Property) prop);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static String nameOf(Property<?> prop, Comparable<?> value) {
        return ((Property) prop).getName((Comparable) value);
    }

    private static Level getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }
}
