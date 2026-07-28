package com.wsteam.wandscape.shared.network;

import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.event.ColonyEvaluationChangedEvent;
import com.wsteam.wandscape.shared.event.TouristArrivedEvent;
import com.wsteam.wandscape.shared.event.TouristDepartedEvent;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
/**
 * Server-side tracker of which players have the Wandscape panel open.
 * Used by {@link com.wsteam.wandscape.building.internal.BuildingInteractHandler}
 * to gate right-click building interactions.
 */
public final class PanelStateTracker {

    private static final Set<UUID> panelOpenPlayers = ConcurrentHashMap.newKeySet();

    private PanelStateTracker() {}

    public static boolean isPanelOpen(ServerPlayer player) {
        return panelOpenPlayers.contains(player.getUUID());
    }

    public static void open(UUID playerId) {
        panelOpenPlayers.add(playerId);
    }

    public static void close(UUID playerId) {
        panelOpenPlayers.remove(playerId);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            panelOpenPlayers.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onColonyEvaluationChanged(ColonyEvaluationChangedEvent event) {
        if (!event.hasChanged()) return;
        syncHudForColony(event.getColonyId(), event.getNewComfort(), event.getNewMagic(), event.getNewWonder());
    }

    @SubscribeEvent
    public static void onTouristArrived(TouristArrivedEvent event) {
        syncHudForColony(event.getColonyId());
    }

    @SubscribeEvent
    public static void onTouristDeparted(TouristDepartedEvent event) {
        syncHudForColony(event.getColonyId());
    }

    /** Sync HUD data for a colony, querying current values from APIs. */
    private static void syncHudForColony(UUID colonyId) {
        BuildingApi buildingApi = WandscapeApis.getBuildingApi();
        int c = buildingApi.getColonyComfort(colonyId);
        int m = buildingApi.getColonyMagic(colonyId);
        int w = buildingApi.getColonyWonder(colonyId);
        syncHudForColony(colonyId, c, m, w);
    }

    /** Sync HUD data for a colony with known evaluation values. */
    private static void syncHudForColony(UUID colonyId, int comfort, int magic, int wonder) {
        BuildingApi buildingApi = WandscapeApis.getBuildingApi();
        ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi == null) return;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        // ── Collect HUD data (use silent variants where available) ──

        int touristCount = 0;
        var touristApi = WandscapeApis.getTouristApiSilently();
        if (touristApi != null) touristCount = touristApi.getTouristCount(colonyId);

        int overnightStayerCount = 0;
        if (touristApi != null) overnightStayerCount = touristApi.getOvernightStayerCount(colonyId);

        int shutdownCount = 0;
        List<String> shutdownBuildingNames = List.of();
        try {
            var buildings = buildingApi.getColonyBuildings(colonyId);
            var shutdownBuildings = buildings.stream().filter(b -> b.isShutdown()).toList();
            shutdownCount = shutdownBuildings.size();
            shutdownBuildingNames = shutdownBuildings.stream().map(b -> b.getBuildingTypeId()).toList();
        } catch (Exception ignored) {}

        int npcIdleCount = 0, npcTotalCount = 0;
        try {
            var npcApi = WandscapeApis.getNpcApi();
            npcIdleCount = npcApi.getIdleNpcs(colonyId).size();
            npcTotalCount = npcApi.getColonyNpcs(colonyId).size();
        } catch (Exception ignored) {}

        int earthAmount = 0, woodAmount = 0, waterAmount = 0, fireAmount = 0, windAmount = 0;
        int metalAmount = 0, darkAmount = 0;
        try {
            var warehouseApi = WandscapeApis.getWarehouseApiSilently();
            if (warehouseApi != null) {
                var elements = warehouseApi.getAllElements(colonyId);
                earthAmount = elements.getOrDefault(ElementType.EARTH, 0L).intValue();
                woodAmount = elements.getOrDefault(ElementType.WOOD, 0L).intValue();
                waterAmount = elements.getOrDefault(ElementType.WATER, 0L).intValue();
                fireAmount = elements.getOrDefault(ElementType.FIRE, 0L).intValue();
                windAmount = elements.getOrDefault(ElementType.WIND, 0L).intValue();
                metalAmount = elements.getOrDefault(ElementType.METAL, 0L).intValue();
                darkAmount = elements.getOrDefault(ElementType.DARK, 0L).intValue();
            }
        } catch (Exception ignored) {}

        var levelMgr = com.wsteam.wandscape.engine.WandscapeEngine.getColonyLevelManager();
        int lvl = levelMgr != null ? levelMgr.getLevel(colonyId) : 1;
        int exp = levelMgr != null ? levelMgr.getExperience(colonyId) : 0;
        String name = levelMgr != null ? levelMgr.getColonyName(colonyId) : "";

        for (UUID playerId : panelOpenPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) continue;
            UUID playerColony = colonyApi.getColonyId(player.blockPosition());
            if (colonyId.equals(playerColony)) {
                PacketDistributor.sendToPlayer(player,
                        new ColonyStatsSyncPacket(colonyId, comfort, magic, wonder, name, lvl, exp,
                                touristCount, overnightStayerCount, shutdownCount,
                                npcIdleCount, npcTotalCount,
                                earthAmount, woodAmount, waterAmount, fireAmount, windAmount,
                                metalAmount, darkAmount,
                                shutdownBuildingNames));
            }
        }
    }
}
