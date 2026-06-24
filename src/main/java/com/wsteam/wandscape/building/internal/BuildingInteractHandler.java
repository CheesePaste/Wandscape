package com.wsteam.wandscape.building.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.production.network.CraftingStationPacket;
import com.wsteam.wandscape.production.network.WorkstationDataPacket;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.warehouse.ColonyItemBank;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Intercepts right-click on blocks within a building's pattern
 * by looking up {@link BuildingSavedData#posIndex}.
 *
 * <p>Sends data packets directly to client — no ContainerMenu needed.
 * The client opens the appropriate MedievalScreen upon receiving the packet.
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
                if (event.getEntity() instanceof ServerPlayer player) {
                    ColonyItemBank bank = ColonyItemBank.get(level);
                    Map<ItemKey, Long> snapshot = bank.getSnapshot(colonyId);
                    Map<ElementType, Long> elemSnapshot = bank.getElementSnapshot(colonyId);
                    var pkt = WarehouseDataPacket.from(pos, colonyId, snapshot, elemSnapshot);
                    PacketDistributor.sendToPlayer(player, pkt);
                }
            }
            case "workstation" -> openWorkstationGui(level, colonyId, event);
            case "crafting_station" -> openCraftingStationGui(level, colonyId, event);
            case "potion_station" -> {
                if (event.getEntity() instanceof ServerPlayer player) {
                    player.displayClientMessage(Component.literal(
                            "[Wandscape] Potion Station — not yet implemented"), false);
                }
            }
            default -> {
                if (event.getEntity() instanceof ServerPlayer player) {
                    String status = "[Wandscape] " + state.getBuildingTypeId()
                            + " | intact=" + state.isStructureIntact()
                            + " | shutdown=" + state.isShutdown()
                            + " | queue=" + state.getTaskQueue().size();
                    // Show unlock lock reason if the building is not yet unlocked
                    BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
                    if (config != null) {
                        String lockReason = com.wsteam.wandscape.building.internal.BuildingUnlockChecker
                                .getLockReason(state.getColonyId(), config);
                        if (lockReason != null) {
                            status += "\n  [Locked] " + lockReason;
                        }
                    }
                    player.displayClientMessage(Component.literal(status), false);
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
            Map<ElementType, Long> elemSnapshot = bank.getElementSnapshot(colonyId);
            var pkt = WorkstationDataPacket.from(event.getPos(), decomposableItems, synthRecipes, elemSnapshot, colonyId);
            PacketDistributor.sendToPlayer(player, pkt);
        }
    }

    private static void openCraftingStationGui(Level level, UUID colonyId,
                                               PlayerInteractEvent.RightClickBlock event) {
        ColonyItemBank bank = ColonyItemBank.get(level);

        var prodLoader = Wandscape.PRODUCTION_RECIPE_LOADER;
        var wandRecipes = prodLoader != null
                ? prodLoader.getCraftWandRecipes().getAll().values()
                : java.util.Collections.<com.wsteam.wandscape.production.data.CraftWandRecipe>emptyList();

        if (event.getEntity() instanceof ServerPlayer player) {
            Map<ElementType, Long> elemSnapshot = bank != null
                    ? bank.getElementSnapshot(colonyId) : Map.of();
            var pkt = CraftingStationPacket.from(event.getPos(), wandRecipes, elemSnapshot, colonyId);
            PacketDistributor.sendToPlayer(player, pkt);
        }
    }
}
