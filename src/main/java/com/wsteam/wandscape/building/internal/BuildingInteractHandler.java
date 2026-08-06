package com.wsteam.wandscape.building.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.network.HotelOpenPacket;
import com.wsteam.wandscape.building.network.NodeDataPacket;
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
 *
 * <p>All building interaction dispatch is centralized in
 * {@link #handleInteraction(ServerPlayer, Level, BlockPos, BuildingState)},
 * called from both {@link #onRightClickBlock} and {@code OverviewInteractPacket}.
 */
public final class BuildingInteractHandler {
    private static final String TAG = "BuildingInteractHandler";

    private BuildingInteractHandler() {}

    private static volatile ShopStockManager shopStockManager;

    /** Set by Wandscape after ShopStockManager is registered. */
    public static void setShopStockManager(ShopStockManager manager) {
        shopStockManager = manager;
    }

    /** Resolve a colony's founding player's display name for the town hall screen. */
    @Nullable
    private static String resolveFounderName(ServerPlayer player, UUID colonyId) {
        var colonyApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getColonyApiSilently();
        if (colonyApi == null) return null;
        UUID founder = colonyApi.getFounder(colonyId);
        if (founder == null) return null;
        var profileCache = player.server.getProfileCache();
        if (profileCache == null) return null;
        return profileCache.get(founder)
                .map(com.mojang.authlib.GameProfile::getName)
                .orElse(null);
    }

    /**
     * Central dispatch for building right-click interactions.
     * Called from both {@link #onRightClickBlock} (normal mode) and
     * {@code OverviewInteractPacket} (overview mode).
     *
     * <p>Sends the appropriate GUI data packet to the player based on
     * building type/category. No return value — the caller handles
     * event cancellation if applicable.
     */
    public static void handleInteraction(ServerPlayer player, Level level,
                                          net.minecraft.core.BlockPos pos, BuildingState state) {
        String category = state.getCategory();
        UUID colonyId = state.getColonyId();

        // Town hall with no colony linked → ask the player to name & create one.
        if ("government".equals(category) && colonyId == null) {
            PacketDistributor.sendToPlayer(player,
                    new com.wsteam.wandscape.shared.network.ColonyCreatePromptPacket(pos));
            Log.info(TAG, "[Colony] Town hall at {} right-clicked with no colony — prompting for name", pos);
            return;
        }

        if (colonyId == null) colonyId = new UUID(0, 0);

        BuildingConfig bldConfig = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        String typeId = state.getBuildingTypeId();

        // Town hall with linked colony: show colony level & exp info
        if ("government".equals(category) && state.getColonyId() != null) {
            var levelMgr = com.wsteam.wandscape.engine.WandscapeEngine.getColonyLevelManager();
            int lvl = levelMgr != null ? levelMgr.getLevel(colonyId) : 1;
            int exp = levelMgr != null ? levelMgr.getExperience(colonyId) : 0;
            int expNext = levelMgr != null ? levelMgr.expToNextLevel(colonyId) : 1000;
            String name = levelMgr != null ? levelMgr.getColonyName(colonyId) : "";
            String founderName = resolveFounderName(player, colonyId);
            PacketDistributor.sendToPlayer(player,
                    new com.wsteam.wandscape.building.network.TownHallOpenPacket(
                            pos, colonyId, name, lvl, exp, expNext, founderName));
            return;
        }

        // Hotel buildings: service with maxOccupancy > 0
        if ("service".equals(category) && bldConfig != null && bldConfig.service() != null
                && bldConfig.service().maxOccupancy() > 0) {
            var hotel = com.wsteam.wandscape.tourist.internal.HotelStayHandler.getActive();
            int occupancy = hotel != null ? hotel.getOccupancy(state.getBuildingId()) : 0;
            int maxOcc = bldConfig.service().maxOccupancy();
            var guestNames = hotel != null
                    ? hotel.getGuestNames(state.getBuildingId(), level)
                    : java.util.List.<String>of();
            PacketDistributor.sendToPlayer(player,
                    new HotelOpenPacket(pos, colonyId, state.getBuildingId(), maxOcc, occupancy, guestNames));
            return;
        }

        switch (category) {
            case "storage" -> {
                ColonyItemBank bank = ColonyItemBank.get(level);
                if (bank == null) return;
                Map<ItemKey, Long> snapshot = bank.getSnapshot(colonyId);
                Map<ElementType, Long> elemSnapshot = bank.getElementSnapshot(colonyId);
                PacketDistributor.sendToPlayer(player,
                        WarehouseDataPacket.from(pos, colonyId, snapshot, elemSnapshot));
            }
            case "workstation" -> openWorkstationGui(level, colonyId, player, pos);
            case "crafting_station" -> openCraftingStationGui(level, colonyId, player, pos);
            case "node" -> openNodeGui(level, player, pos, state);
            case "shop" -> {
                if (shopStockManager != null) {
                    shopStockManager.ensureStockInitialized(state.getBuildingId());
                }
                Map<String, Integer> stock = shopStockManager != null
                        ? shopStockManager.getStock(state.getBuildingId()) : Map.of();
                Map<String, Integer> maxStocks = shopStockManager != null
                        ? shopStockManager.getAllMaxStocks(state.getBuildingId()) : Map.of();
                PacketDistributor.sendToPlayer(player,
                        new ShopOpenPacket(pos, colonyId, state.getBuildingId(), stock, maxStocks));
            }
            case "tavern" -> {
                List<com.wsteam.wandscape.shared.data.MageResume> mageResumes = List.of();
                try {
                    var tavernApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getTavernApi();
                    mageResumes = tavernApi.getMageResumes(colonyId);
                } catch (IllegalStateException ignored) {}
                PacketDistributor.sendToPlayer(player,
                        new TavernOpenPacket(pos, colonyId, mageResumes));
            }
            case "potion_station" -> {
                player.displayClientMessage(Component.literal(
                        "[Wandscape] Potion Station — not yet implemented"), false);
            }
            default -> {
                Log.info(TAG, "[Building] Right-click: type={} at={} intact={} shutdown={} queue={}",
                        state.getBuildingTypeId(), state.getAnchor(),
                        state.isStructureIntact(), state.isShutdown(),
                        state.getTaskQueue().size());
                BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
                if (config != null) {
                    String lockReason = com.wsteam.wandscape.building.internal.BuildingUnlockChecker
                            .getLockReason(state.getColonyId(), config);
                    if (lockReason != null) {
                        Log.info(TAG, "[Building] {} locked: {}", state.getBuildingTypeId(), lockReason);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;

        // Only intercept when player has the Wandscape panel open
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!com.wsteam.wandscape.shared.network.PanelStateTracker.isPanelOpen(player)) return;

        var pos = event.getPos();
        BuildingSavedData data = BuildingSavedData.get(level);

        // 1. Exact block match (clicked on building pattern block)
        UUID buildingId = data.getBuildingIdAt(pos);

        // 2. Interaction zone fallback: check building boundary
        if (buildingId == null) {
            buildingId = data.getBuildingIdInInteractionZone(pos);
        }

        if (buildingId == null) return;

        BuildingState state = data.getBuilding(buildingId);
        if (state == null) return;

        handleInteraction(player, level, pos, state);

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private static void openWorkstationGui(Level level, UUID colonyId,
                                           ServerPlayer player, net.minecraft.core.BlockPos pos) {
        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;

        var elemLoader = Wandscape.ELEMENT_MAPPING_LOADER;
        Map<ItemKey, Long> decomposableItems = new LinkedHashMap<>();
        for (var entry : bank.getSnapshot(colonyId).entrySet()) {
            // Every warehouse item with a real element value is decomposable
            // (1/5 yield); items without a mapping yield nothing and are hidden.
            if (elemLoader.getItemElementValue(entry.getKey().itemId()).isEmpty()) continue;
            decomposableItems.put(entry.getKey(), entry.getValue());
        }

        var prodLoader = Wandscape.PRODUCTION_RECIPE_LOADER;
        var synthRecipes = prodLoader != null
                ? prodLoader.getAllSynthesizeRecipes()
                : java.util.Collections.<com.wsteam.wandscape.production.data.SynthesizeRecipe>emptyList();

        Map<ElementType, Long> elemSnapshot = bank.getElementSnapshot(colonyId);
        var pkt = WorkstationDataPacket.from(pos, decomposableItems, synthRecipes, elemSnapshot, colonyId);
        PacketDistributor.sendToPlayer(player, pkt);
    }

    private static void openNodeGui(Level level, ServerPlayer player,
                                    net.minecraft.core.BlockPos pos, BuildingState state) {
        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        if (config == null || config.nodeConfig() == null) {
            Log.warn(TAG, "[Node] building {} has no node_config", state.getBuildingTypeId());
            player.displayClientMessage(Component.literal(
                    "[Wandscape] " + state.getBuildingTypeId() + " — no node_config"), false);
            return;
        }
        var nc = config.nodeConfig();
        PacketDistributor.sendToPlayer(player,
                new NodeDataPacket(pos, state.getBuildingTypeId(), nc.element(),
                        nc.amountPerHarvest(), nc.channelTicks()));
        Log.info(TAG, "[Node] open GUI type={} at={} element={} amount={} ticks={}",
                state.getBuildingTypeId(), pos, nc.element(),
                nc.amountPerHarvest(), nc.channelTicks());
    }

    private static void openCraftingStationGui(Level level, UUID colonyId,
                                                ServerPlayer player, net.minecraft.core.BlockPos pos) {
        ColonyItemBank bank = ColonyItemBank.get(level);

        var prodLoader = Wandscape.PRODUCTION_RECIPE_LOADER;
        var wandRecipes = prodLoader != null
                ? prodLoader.getCraftWandRecipes().getAll().values()
                : java.util.Collections.<com.wsteam.wandscape.production.data.CraftWandRecipe>emptyList();

        Map<ElementType, Long> elemSnapshot = bank != null
                ? bank.getElementSnapshot(colonyId) : Map.of();
        var pkt = CraftingStationPacket.from(pos, wandRecipes, elemSnapshot, colonyId);
        PacketDistributor.sendToPlayer(player, pkt);
    }
}
