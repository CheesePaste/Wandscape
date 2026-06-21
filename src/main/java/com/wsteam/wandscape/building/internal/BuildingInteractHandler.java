package com.wsteam.wandscape.building.internal;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.warehouse.ColonyItemBank;
import com.wsteam.wandscape.warehouse.WarehouseMenu;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Intercepts right-click on blocks within a building's pattern
 * by looking up {@link BuildingSavedData#posIndex}.
 *
 * <p>Warehouse (category=storage) opens its GUI directly — no BE needed.
 * Other buildings print an info message.
 */
public final class BuildingInteractHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private BuildingInteractHandler() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;

        var pos = event.getPos();
        BuildingSavedData data = BuildingSavedData.get(level);
        UUID buildingId = data.getBuildingIdAt(pos);
        if (buildingId == null) return;

        BuildingState state = data.getBuilding(buildingId);
        if (state == null) return;

        if ("storage".equals(state.getCategory())) {
            // Warehouse: open GUI directly from SavedData (no BE)
            UUID colonyId = state.getColonyId();
            if (colonyId == null) colonyId = new UUID(0, 0); // match WarehouseManager.addResource fallback
            Map<ItemKey, Long> snapshot = ColonyItemBank.get(level).getSnapshot(colonyId);
            if (event.getEntity() instanceof ServerPlayer player) {
                player.openMenu(WarehouseMenu.createMenuProvider(snapshot));
            }
        } else {
            // Other buildings: print info log
            if (event.getEntity() instanceof ServerPlayer player) {
                player.displayClientMessage(Component.literal(
                        "[Wandscape] " + state.getBuildingTypeId()
                        + " | intact=" + state.isStructureIntact()
                        + " | shutdown=" + state.isShutdown()
                        + " | queue=" + state.getTaskQueue().size()),
                        false);
            }
            LOGGER.info("[Building] Right-click: type={} at={} intact={} shutdown={} queue={}",
                    state.getBuildingTypeId(), state.getAnchor(),
                    state.isStructureIntact(), state.isShutdown(),
                    state.getTaskQueue().size());
        }

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}
