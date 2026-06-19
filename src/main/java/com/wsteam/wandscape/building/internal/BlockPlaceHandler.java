package com.wsteam.wandscape.building.internal;

import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.be.AbstractWandscapeBE;
import com.wsteam.wandscape.building.block.WandscapeBuildingBlock;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

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

        // 3. Generate building ID — stored in BE NBT for persistence across restarts
        UUID generatedId = UUID.randomUUID();

        // 4. Create BuildingData and register with API
        BuildingDataImpl data = new BuildingDataImpl(
                generatedId,
                buildingTypeId,
                config.category(),
                pos,
                null, // colonyId — not yet determined (stage 4: colony lifecycle)
                config.comfort(),
                config.magic(),
                config.wonder(),
                config.maintenanceCost(),
                config.queue().capacity()
        );

        try {
            var api = WandscapeApis.getBuildingApi();
            api.registerBuilding(data);
            LOGGER.info("[Building] Registered: id={} type={} comfort={} magic={} wonder={} maintenance={}",
                    generatedId, buildingTypeId, config.comfort(), config.magic(), config.wonder(), config.maintenanceCost());
        } catch (IllegalStateException e) {
            LOGGER.warn("[Building] BuildingApi not loaded: {}", e.getMessage());
        }

        // 5. Validate structure: find missing blocks in pattern
        int missingCount = 0;
        for (BlockOffset offset : config.pattern()) {
            BlockPos target = pos.offset(offset.x(), offset.y(), offset.z());
            String expectedBlockId = config.blockMapping().get(offset.toKey());
            if (expectedBlockId == null) continue;

            BlockState actual = level.getBlockState(target);
            String actualId = actual.getBlock().builtInRegistryHolder().key().location().toString();

            if (!actualId.equals(expectedBlockId)) {
                WorkItem work = new WorkItem(
                        "build:" + extractBlockName(expectedBlockId),
                        java.util.Map.of(
                                "x", String.valueOf(target.getX()),
                                "y", String.valueOf(target.getY()),
                                "z", String.valueOf(target.getZ())
                        ),
                        49 // V1: below 50 to skip PENDING_APPROVAL (no approval UI yet)
                );
                be.enqueueWork(work);
                missingCount++;
                LOGGER.info("[Building] Missing block at {} offset ({},{},{}) expected={} actual={} → enqueued repair",
                        target, offset.x(), offset.y(), offset.z(), expectedBlockId, actualId);
            }
        }

        // 6. Mark structure status
        be.setStructureIntact(missingCount == 0);
        if (missingCount == 0) {
            LOGGER.info("[Building] Structure complete: type={} at {}", buildingTypeId, pos);
        } else {
            LOGGER.info("[Building] Structure incomplete: type={} at {} — {} blocks need repair",
                    buildingTypeId, pos, missingCount);
        }
    }

    private static String extractBlockName(String fullId) {
        // "minecraft:stone_bricks" → "stone_bricks"
        // "wandscape:forest_node" → "forest_node"
        int colon = fullId.indexOf(':');
        return colon >= 0 ? fullId.substring(colon + 1) : fullId;
    }
}
