package com.wsteam.wandscape.shared.network;

import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.event.ColonyEvaluationChangedEvent;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

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

        UUID colonyId = event.getColonyId();
        int newComfort = event.getNewComfort();
        int newMagic = event.getNewMagic();
        int newWonder = event.getNewWonder();

        BuildingApi buildingApi = WandscapeApis.getBuildingApi();
        ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi == null) return;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        for (UUID playerId : panelOpenPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) continue;
            UUID playerColony = colonyApi.getColonyId(player.blockPosition());
            if (colonyId.equals(playerColony)) {
                // Include colony level in sync
                var levelMgr = com.wsteam.wandscape.engine.WandscapeEngine.getColonyLevelManager();
                int lvl = levelMgr != null ? levelMgr.getLevel(colonyId) : 1;
                int exp = levelMgr != null ? levelMgr.getExperience(colonyId) : 0;
                String name = levelMgr != null ? levelMgr.getColonyName(colonyId) : "";
                PacketDistributor.sendToPlayer(player,
                        new ColonyStatsSyncPacket(colonyId, newComfort, newMagic, newWonder, name, lvl, exp));
            }
        }
    }
}
