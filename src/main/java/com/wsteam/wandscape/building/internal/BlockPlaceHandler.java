package com.wsteam.wandscape.building.internal;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.be.AbstractWandscapeBE;
import com.wsteam.wandscape.building.block.WandscapeBuildingBlock;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.data.WorkItem;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Listens for building block placement and:
 * <ol>
 *   <li>Validates the multi-block structure against the JSON pattern</li>
 *   <li>Registers the building in {@link BuildingApiImpl}</li>
 *   <li>Enqueues {@link WorkItem}s for any missing pattern blocks</li>
 * </ol>
 *
 * <p>Manually registered on NeoForge.EVENT_BUS in Wandscape constructor.
 */
public final class BlockPlaceHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private BlockPlaceHandler() {}

    /**
     * Handle building block placement.
     */
    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide()) return;

        BlockState state = event.getPlacedBlock();
        if (!(state.getBlock() instanceof WandscapeBuildingBlock buildingBlock)) return;

        BlockPos pos = event.getPos();
        Level level = (Level) event.getLevel(); // EntityPlaceEvent only fires on server

        String buildingTypeId = buildingBlock.getBuildingTypeId();
        LOGGER.info("[Building] Placed block detected: type={} at {}", buildingTypeId, pos);

        // 1. Look up config
        BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();
        BuildingConfig config = configLoader.get(buildingTypeId);
        if (config == null) {
            LOGGER.warn("[Building] No config for type '{}' — skipping", buildingTypeId);
            return;
        }

        // 2. Get or create the BE
        if (!(level.getBlockEntity(pos) instanceof AbstractWandscapeBE be)) {
            LOGGER.warn("[Building] No AbstractWandscapeBE at {} — skipping", pos);
            return;
        }

        // 3. Register with BuildingApi (no-op if already registered)
        EnqueueHelper.registerIfAbsent(pos, config, buildingTypeId);

        // 4. Validate structure: find missing blocks in pattern.
        //    If any blocks are missing, enqueue ONE repair job using the building's
        //    own blueprint (registered from BuildingConfig JSON). The full blueprint
        //    re-runs — already-correct blocks are no-ops, missing ones get placed.
        int missingCount = 0;
        for (BlockOffset offset : config.pattern()) {
            BlockPos target = pos.offset(offset.x(), offset.y(), offset.z());
            String expectedBlockId = config.blockMapping().get(offset.toKey());
            if (expectedBlockId == null) continue;

            BlockState actual = level.getBlockState(target);
            String actualId = actual.getBlock().builtInRegistryHolder().key().location().toString();

            if (!actualId.equals(expectedBlockId)) {
                missingCount++;
                LOGGER.info("[Building] Missing block at {} offset ({},{},{}) expected={} actual={}",
                        target, offset.x(), offset.y(), offset.z(), expectedBlockId, actualId);
            }
        }

        if (missingCount > 0) {
            WorkItem work = EnqueueHelper.buildWorkItem(config, pos, buildingTypeId, 49);
            be.enqueueWork(work);
            LOGGER.info("[Building] Structure incomplete: type={} at {} — {} blocks missing, enqueued 1 repair job (blueprint=build:{})",
                    buildingTypeId, pos, missingCount, buildingTypeId);
        }

        // 6. Mark structure status
        be.setStructureIntact(missingCount == 0);
        if (missingCount == 0) {
            LOGGER.info("[Building] Structure complete: type={} at {}", buildingTypeId, pos);
        }
    }
}
