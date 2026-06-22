package com.wsteam.wandscape.building.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.production.menu.CraftingStationMenu;
import com.wsteam.wandscape.production.menu.WorkstationMenu;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.warehouse.ColonyItemBank;
import com.wsteam.wandscape.warehouse.WarehouseMenu;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

        String category = state.getCategory();
        UUID colonyId = state.getColonyId();
        if (colonyId == null) colonyId = new UUID(0, 0);

        switch (category) {
            case "storage" -> {
                Map<ItemKey, Long> snapshot = ColonyItemBank.get(level).getSnapshot(colonyId);
                if (event.getEntity() instanceof ServerPlayer player) {
                    player.openMenu(WarehouseMenu.createMenuProvider(snapshot));
                }
            }
            case "workstation" -> openWorkstationGui(level, colonyId, event);
            case "crafting_station" -> openCraftingStationGui(event);
            case "potion_station" -> {
                if (event.getEntity() instanceof ServerPlayer player) {
                    player.displayClientMessage(Component.literal(
                            "[Wandscape] Potion Station — not yet implemented"), false);
                }
            }
            default -> {
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
        }

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private static void openWorkstationGui(Level level, UUID colonyId,
                                           PlayerInteractEvent.RightClickBlock event) {
        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;

        var elemLoader = Wandscape.ELEMENT_MAPPING_LOADER;
        Map<ItemKey, Long> decomposableItems = new LinkedHashMap<>();
        for (var entry : bank.getSnapshot(colonyId).entrySet()) {
            ResourceLocation rl = ResourceLocation.tryParse(entry.getKey().itemId());
            if (rl == null) continue;
            var block = BuiltInRegistries.BLOCK.get(rl);
            if (block != null && elemLoader.isDecomposable(block.defaultBlockState())) {
                decomposableItems.put(entry.getKey(), entry.getValue());
            }
        }

        var prodLoader = Wandscape.PRODUCTION_RECIPE_LOADER;
        var synthRecipes = prodLoader != null
                ? prodLoader.getSynthesizeRecipes().getAll().values()
                : java.util.Collections.<com.wsteam.wandscape.production.data.SynthesizeRecipe>emptyList();

        if (event.getEntity() instanceof ServerPlayer player) {
            player.openMenu(WorkstationMenu.createMenuProvider(
                    event.getPos(), decomposableItems, synthRecipes));
        }
    }

    private static void openCraftingStationGui(PlayerInteractEvent.RightClickBlock event) {
        var prodLoader = Wandscape.PRODUCTION_RECIPE_LOADER;
        var wandRecipes = prodLoader != null
                ? prodLoader.getCraftWandRecipes().getAll().values()
                : java.util.Collections.<com.wsteam.wandscape.production.data.CraftWandRecipe>emptyList();

        if (event.getEntity() instanceof ServerPlayer player) {
            player.openMenu(CraftingStationMenu.createMenuProvider(event.getPos(), wandRecipes));
        }
    }
}
