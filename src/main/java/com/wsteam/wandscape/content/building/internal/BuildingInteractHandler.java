package com.wsteam.wandscape.content.building.internal;
import com.wsteam.wandscape.impl.WandscapeEngine;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.foundation.ui.panel.PanelStateTracker;
import com.wsteam.wandscape.content.colony.network.ColonyCreatePromptPacket;
import com.wsteam.wandscape.content.npc.data.MageResume;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.network.*;
import com.wsteam.wandscape.content.building.network.*;
import com.wsteam.wandscape.content.production.network.CraftingStationPacket;
import com.wsteam.wandscape.content.production.network.MagicStationPacket;
import com.wsteam.wandscape.content.production.network.WorkstationDataPacket;
import com.wsteam.wandscape.content.tourist.internal.HotelStayHandler;
import com.wsteam.wandscape.content.warehouse.WarehouseMenu;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.util.ItemKey;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import com.wsteam.wandscape.content.warehouse.network.WarehouseDataPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        var colonyApi = com.wsteam.wandscape.api.WandscapeApis.getColonyApiSilently();
        if (colonyApi == null) return null;
        UUID founder = colonyApi.getFounder(colonyId);
        if (founder == null) return null;
        var profileCache = player.server.getProfileCache();
        if (profileCache == null) return null;
        return profileCache.get(founder)
                .map(com.mojang.authlib.GameProfile::getName)
                .orElse(null);
    }

    /** Resolve a building's config creator from its anchor position ("" when unknown). */
    public static String resolveCreator(Level level, net.minecraft.core.BlockPos pos) {
        BuildingSavedData data = BuildingSavedData.get(level);
        UUID id = data.getBuildingIdAt(pos);
        if (id == null) id = data.getBuildingIdInInteractionZone(pos);
        if (id == null) return "";
        BuildingState state = data.getBuilding(id);
        if (state == null) return "";
        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        return config != null ? config.creator() : "";
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

        // Town hall with no colony linked → ask the player to name & create one (even if under construction).
        if ("government".equals(category) && colonyId == null) {
            BuildingConfig promptCfg = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
            String promptCreator = promptCfg != null ? promptCfg.creator() : "";
            PacketDistributor.sendToPlayer(player,
                    new com.wsteam.wandscape.content.colony.network.ColonyCreatePromptPacket(pos, promptCreator));
            Log.info(TAG, "[Colony] Town hall at {} right-clicked with no colony — prompting for name", pos);
            return;
        }

        // Under-construction building → open the construction-site panel
        // (required materials, warehouse/synthesis status, time estimates).
        if (!state.hasEverCompleted()) {
            PacketDistributor.sendToPlayer(player, ConstructionSiteDataPacket.from(level, state));
            return;
        }

        if (colonyId == null) colonyId = new UUID(0, 0);

        BuildingConfig bldConfig = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        String typeId = state.getBuildingTypeId();
        String creator = bldConfig != null ? bldConfig.creator() : "";

        // Town hall with linked colony: show colony level & exp info
        if ("government".equals(category) && state.getColonyId() != null) {
            var levelMgr = com.wsteam.wandscape.impl.WandscapeEngine.getColonyLevelManager();
            int lvl = levelMgr != null ? levelMgr.getLevel(colonyId) : 1;
            int exp = levelMgr != null ? levelMgr.getExperience(colonyId) : 0;
            int expNext = levelMgr != null ? levelMgr.expToNextLevel(colonyId) : 1000;
            String name = levelMgr != null ? levelMgr.getColonyName(colonyId) : "";
            String founderName = resolveFounderName(player, colonyId);
            boolean canUseWarehouse = com.wsteam.wandscape.api.WandscapeApis.getBuildingApi()
                    .getBuildingsByCategory(colonyId, "storage").isEmpty();
            var colonyApi = com.wsteam.wandscape.api.WandscapeApis.getColonyApiSilently();
            int namingStyle = colonyApi != null ? colonyApi.getNamingStyle(colonyId).ordinal() : 0;
            PacketDistributor.sendToPlayer(player,
                    new TownHallOpenPacket(
                            pos, colonyId, name, lvl, exp, expNext, founderName, canUseWarehouse, namingStyle,
                            creator));
            return;
        }

        // Hotel buildings: service with maxOccupancy > 0
        if ("service".equals(category) && bldConfig != null && bldConfig.service() != null
                && bldConfig.service().maxOccupancy() > 0) {
            var hotel = HotelStayHandler.getActive();
            int occupancy = hotel != null ? hotel.getOccupancy(state.getBuildingId()) : 0;
            int maxOcc = bldConfig.service().maxOccupancy();
            var guestNames = hotel != null
                    ? hotel.getGuestNames(state.getBuildingId(), level)
                    : java.util.List.<String>of();
            PacketDistributor.sendToPlayer(player,
                    new HotelOpenPacket(pos, colonyId, state.getBuildingId(), creator, maxOcc, occupancy, guestNames));
            return;
        }

        switch (category) {
            case "storage" -> openWarehouseMenu(player, colonyId, pos, creator);
            case "workstation" -> openWorkstationGui(level, colonyId, player, pos, creator);
            case "crafting_station" -> openCraftingStationGui(level, colonyId, player, pos, creator);
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
                        new ShopOpenPacket(pos, colonyId, state.getBuildingId(), creator, stock, maxStocks));
                // Opening a shop triggers its first restock — push onboarding progress (step 7).
                var guideApi = com.wsteam.wandscape.api.WandscapeApis.getGuideProgressApiSilently();
                if (guideApi != null) guideApi.sendToPlayer(player, colonyId);
            }
            case "tavern" -> {
                List<com.wsteam.wandscape.content.npc.data.MageResume> mageResumes = List.of();
                int recruitCount = 0;
                try {
                    var tavernApi = com.wsteam.wandscape.api.WandscapeApis.getTavernApi();
                    mageResumes = tavernApi.getMageResumes(colonyId);
                    recruitCount = tavernApi.getRecruitCount(colonyId);
                } catch (IllegalStateException ignored) {}
                PacketDistributor.sendToPlayer(player,
                        new TavernOpenPacket(pos, colonyId, recruitCount, mageResumes, creator));
            }
            case "mage_hut" -> {
                if (level instanceof net.minecraft.server.level.ServerLevel sl) {
                    MageHutServerHandler.openMageHut(player, sl, state.getBuildingId(), state);
                }
            }
            case "magic_station" -> openMagicStationGui(level, colonyId, player, pos, creator);
            case "altar" -> {
                if (level instanceof net.minecraft.server.level.ServerLevel sl) {
                    PacketDistributor.sendToPlayer(player,
                            new AltarOpenPacket(pos, colonyId, state.getBuildingId(), creator,
                                    AltarCastHandler.listSpells(sl, state.getBuildingId())));
                }
            }
            case "service", "relax", "decoration", "atm" ->
                    openInfoPanel(player, state, category, bldConfig);
            default -> {
                Log.info(TAG, "[Building] Right-click: type={} at={} intact={} queue={}",
                        state.getBuildingTypeId(), state.getAnchor(),
                        state.isStructureIntact(),
                        state.getTaskQueue().size());
                BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
                if (config != null) {
                    Component lockReason = BuildingUnlockChecker
                            .getLockReason(state.getColonyId(), config);
                    if (lockReason != null) {
                        Log.info(TAG, "[Building] {} locked: {}", state.getBuildingTypeId(), lockReason);
                    }
                }
            }
        }
    }

    /** Open the warehouse container menu (vanilla flow) and push the initial data snapshot. */
    public static void openWarehouseMenu(ServerPlayer player, UUID colonyId,
                                         net.minecraft.core.BlockPos pos, String creator) {
        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                (id, inv, p) -> new WarehouseMenu(id, inv, colonyId, pos),
                Component.translatable("gui.wandscape.warehouse.title")));
        ColonyItemBank bank = ColonyItemBank.get(player.serverLevel());
        if (bank == null) return;
        Map<ItemKey, Long> snapshot = bank.getSnapshot(colonyId);
        Map<ElementType, Long> elemSnapshot = bank.getElementSnapshot(colonyId);
        PacketDistributor.sendToPlayer(player,
                WarehouseDataPacket.from(pos, colonyId, snapshot, elemSnapshot, creator));
    }

    private static void openInfoPanel(ServerPlayer player, BuildingState state,
                                      String category, BuildingConfig config) {        if (config == null) {
            Log.warn(TAG, "[Building] {} category={} has no config — nothing to show",
                    state.getBuildingTypeId(), category);
            return;
        }
        var svc = config.service();
        var relax = config.relax();
        var atm = config.atm();
        int duration = switch (category) {
            case "relax" -> relax != null ? relax.interactionDurationTicks() : 0;
            case "atm" -> atm != null ? atm.interactionDurationTicks() : 0;
            default -> svc != null ? svc.interactionDurationTicks() : 0;
        };
        PacketDistributor.sendToPlayer(player, new BuildingInfoPacket(
                state.getAnchor(), state.getBuildingTypeId(), category,
                svc != null ? svc.elementOutput() : Map.of(),
                svc != null ? svc.energyPerUse() : 0,
                relax != null ? relax.energyRestore() : 0,
                duration,
                config.creator()));
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;

        // Only intercept when player has the Wandscape panel open
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!com.wsteam.wandscape.foundation.ui.panel.PanelStateTracker.isPanelOpen(player)) return;

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
                                           ServerPlayer player, net.minecraft.core.BlockPos pos,
                                           String creator) {
        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;

        var elemLoader = Wandscape.ELEMENT_MAPPING_LOADER;
        Map<ItemKey, Long> decomposableItems = new LinkedHashMap<>();
        Map<String, Map<ElementType, Long>> itemElementValues = new LinkedHashMap<>();
        for (var entry : bank.getSnapshot(colonyId).entrySet()) {
            // Every warehouse item with a real element value is decomposable
            // (1/10 yield); items without a mapping yield nothing and are hidden.
            Map<ElementType, Long> value = elemLoader.getItemElementValue(entry.getKey().itemId());
            if (value.isEmpty()) continue;
            decomposableItems.put(entry.getKey(), entry.getValue());
            itemElementValues.put(entry.getKey().itemId(), value);
        }

        var prodLoader = Wandscape.PRODUCTION_RECIPE_LOADER;
        var synthRecipes = prodLoader != null
                ? prodLoader.getAllSynthesizeRecipes()
                : java.util.Collections.<com.wsteam.wandscape.content.production.data.SynthesizeRecipe>emptyList();

        Map<ElementType, Long> elemSnapshot = bank.getElementSnapshot(colonyId);
        var pkt = WorkstationDataPacket.from(pos, decomposableItems, synthRecipes, elemSnapshot, colonyId, itemElementValues, creator);
        PacketDistributor.sendToPlayer(player, pkt);
    }

    private static void openNodeGui(Level level, ServerPlayer player,
                                    net.minecraft.core.BlockPos pos, BuildingState state) {
        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        if (config == null || config.nodeConfig() == null) {
            Log.warn(TAG, "[Node] building {} has no node_config", state.getBuildingTypeId());
            ScreenFeedbackPacket.send(player, I18n.name(
                    "message.wandscape.building.no_node_config",
                    "[Wandscape] %s — no node_config", state.getBuildingTypeId()), true);
            return;
        }
        var nc = config.nodeConfig();
        PacketDistributor.sendToPlayer(player,
                new NodeDataPacket(pos, state.getBuildingTypeId(), nc.element(),
                        nc.amountPerHarvest(), nc.channelTicks(), config.creator()));
        Log.info(TAG, "[Node] open GUI type={} at={} element={} amount={} ticks={}",
                state.getBuildingTypeId(), pos, nc.element(),
                nc.amountPerHarvest(), nc.channelTicks());
    }

    private static void openMagicStationGui(Level level, UUID colonyId,
                                            ServerPlayer player, net.minecraft.core.BlockPos pos,
                                            String creator) {
        ColonyItemBank bank = ColonyItemBank.get(level);

        var prodLoader = Wandscape.PRODUCTION_RECIPE_LOADER;
        var spellRecipes = prodLoader != null
                ? prodLoader.getSpellRecipes().getAll().values()
                : java.util.Collections.<com.wsteam.wandscape.content.production.data.CraftSpellRecipe>emptyList();

        Map<ElementType, Long> elemSnapshot = bank != null
                ? bank.getElementSnapshot(colonyId) : Map.of();
        var pkt = MagicStationPacket.from(pos, spellRecipes, elemSnapshot, colonyId, creator);
        PacketDistributor.sendToPlayer(player, pkt);
    }

    private static void openCraftingStationGui(Level level, UUID colonyId,
                                                ServerPlayer player, net.minecraft.core.BlockPos pos,
                                                String creator) {
        ColonyItemBank bank = ColonyItemBank.get(level);

        var prodLoader = Wandscape.PRODUCTION_RECIPE_LOADER;
        var wandRecipes = prodLoader != null
                ? prodLoader.getCraftWandRecipes().getAll().values()
                : java.util.Collections.<com.wsteam.wandscape.content.production.data.CraftWandRecipe>emptyList();
        var potionRecipes = prodLoader != null
                ? prodLoader.getPotionRecipes().getAll().values()
                : java.util.Collections.<com.wsteam.wandscape.content.production.data.BrewPotionRecipe>emptyList();
        var miscRecipes = prodLoader != null
                ? prodLoader.getMiscRecipes().getAll().values()
                : java.util.Collections.<com.wsteam.wandscape.content.production.data.MiscRecipe>emptyList();

        Map<ElementType, Long> elemSnapshot = bank != null
                ? bank.getElementSnapshot(colonyId) : Map.of();
        var pkt = CraftingStationPacket.from(pos, wandRecipes, potionRecipes, miscRecipes,
                elemSnapshot, colonyId, creator);
        PacketDistributor.sendToPlayer(player, pkt);
    }
}
