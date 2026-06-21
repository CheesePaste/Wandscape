package com.wsteam.wandscape.building.internal;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * Listens for block break and explosion events.
 * Marks affected buildings as {@code structureIntact = false}.
 */
public final class BuildingBreakHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private BuildingBreakHandler() {}

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Level level = event.getPlayer().level();
        if (level.isClientSide()) return;

        handleBlockRemoval(level, event.getPos());
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;

        for (BlockPos pos : event.getAffectedBlocks()) {
            handleBlockRemoval(level, pos);
        }
    }

    private static void handleBlockRemoval(Level level, BlockPos pos) {
        BuildingSavedData data = BuildingSavedData.get(level);
        UUID buildingId = data.getBuildingIdAt(pos);
        if (buildingId == null) return;

        BuildingState state = data.getBuilding(buildingId);
        if (state == null || !state.isStructureIntact()) return;

        state.setStructureIntact(false);
        data.setDirty();
        LOGGER.info("[Building] Structure damaged: type={} at={} (block at {})",
                state.getBuildingTypeId(), state.getAnchor(), pos);
    }
}
