package com.wsteam.wandscape.building.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.network.HotelOpenPacket;
import com.wsteam.wandscape.building.network.ShopOpenPacket;
import com.wsteam.wandscape.building.network.TavernOpenPacket;
import com.wsteam.wandscape.production.network.CraftingStationPacket;
import com.wsteam.wandscape.production.network.WorkstationDataPacket;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.warehouse.ColonyItemBank;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Intercepts right-click on blocks within a building's pattern
 * by looking up {@link BuildingSavedData#posIndex}.
 *
 * <p>Sends data packets directly to client — no ContainerMenu needed.
 * The client opens the appropriate MedievalScreen upon receiving the packet.
 */
public final class BuildingInteractHandler {
    private static final String TAG = "BuildingInteractHandler";

    private BuildingInteractHandler() {}

    private static volatile ShopStockManager shopStockManager;

    /** Set by Wandscape after ShopStockManager is registered. */
    public static void setShopStockManager(ShopStockManager manager) {
        shopStockManager = manager;
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;

        // Only intercept when player has the Wandscape panel open
        if (event.getEntity() instanceof ServerPlayer player) {
            if (!com.wsteam.wandscape.shared.network.PanelStateTracker.isPanelOpen(player)) {
                return;
            }
        }

        var pos = event.getPos();
        BuildingSavedData data = BuildingSavedData.get(level);

        // 1. Exact block match (clicked on building pattern block)
        UUID buildingId = data.getBuildingIdAt(pos);

        // 2. Interaction zone fallback: buildings with interaction_radius > 0
        //    can be interacted with from nearby blocks (e.g. shops)
        if (buildingId == null) {
            buildingId = data.getBuildingIdInInteractionZone(pos);
        }

        if (buildingId == null) return;

        BuildingState state = data.getBuilding(buildingId);
        if (state == null) return;

        String category = state.getCategory();
        UUID colonyId = state.getColonyId();
        if (colonyId == null) colonyId = new UUID(0, 0);

        BuildingConfig bldConfig = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());

        // Hotel buildings: service with maxOccupancy > 0
        if ("service".equals(category) && bldConfig != null && bldConfig.service() != null
                && bldConfig.service().maxOccupancy() > 0) {
            if (event.getEntity() instanceof ServerPlayer player) {
                var hotel = com.wsteam.wandscape.tourist.internal.HotelStayHandler.getActive();
                int occupancy = hotel != null ? hotel.getOccupancy(buildingId) : 0;
                int maxOcc = bldConfig.service().maxOccupancy();
                var guestNames = hotel != null
                        ? hotel.getGuestNames(buildingId, event.getLevel())
                        : java.util.List.<String>of();
                var pkt = new HotelOpenPacket(pos, colonyId, buildingId, maxOcc, occupancy, guestNames);
                PacketDistributor.sendToPlayer(player, pkt);
            }
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

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
            case "shop" -> {
                if (event.getEntity() instanceof ServerPlayer player) {
                    if (shopStockManager != null) {
                        shopStockManager.ensureStockInitialized(buildingId);
                    }
                    Map<String, Integer> stock = shopStockManager != null
                            ? shopStockManager.getStock(buildingId) : Map.of();
                    Map<String, Integer> maxStocks = shopStockManager != null
                            ? shopStockManager.getAllMaxStocks(buildingId) : Map.of();
                    var pkt = new ShopOpenPacket(pos, colonyId, buildingId, stock, maxStocks);
                    PacketDistributor.sendToPlayer(player, pkt);
                }
            }
            case "tavern" -> {
                if (event.getEntity() instanceof ServerPlayer player) {
                    List<com.wsteam.wandscape.shared.data.MageResume> mageResumes = List.of();
                    try {
                        var tavernApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getTavernApi();
                        mageResumes = tavernApi.getMageResumes(colonyId);
                    } catch (IllegalStateException ignored) {}
                    var pkt = new TavernOpenPacket(pos, colonyId, mageResumes);
                    PacketDistributor.sendToPlayer(player, pkt);
                }
            }
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
                Log.info(TAG, "[Building] Right-click: type={} at={} intact={} shutdown={} queue={}",
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
            String itemId = entry.getKey().itemId();
            if (elemLoader.hasSeedValue(itemId)) {
                decomposableItems.put(entry.getKey(), entry.getValue());
            }
        }

        var prodLoader = Wandscape.PRODUCTION_RECIPE_LOADER;
        var synthRecipes = prodLoader != null
                ? prodLoader.getAllSynthesizeRecipes()
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
