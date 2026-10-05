package com.wsteam.wandscape.foundation.ui.panel;
import com.wsteam.wandscape.content.colony.network.ColonyStatsSyncPacket;

import com.wsteam.wandscape.content.building.internal.BuildingInteractHandler;
import com.wsteam.wandscape.content.colony.ownership.ColonyOwnership;
import com.wsteam.wandscape.api.ColonyStatusApi;
import com.wsteam.wandscape.content.colony.data.ColonyStatusSnapshot;
import com.wsteam.wandscape.content.colony.event.ColonyEvaluationChangedEvent;
import com.wsteam.wandscape.content.element.event.ElementBalanceChangedEvent;
import com.wsteam.wandscape.content.tourist.event.TouristArrivedEvent;
import com.wsteam.wandscape.content.tourist.event.TouristDepartedEvent;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.networking.Net;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side tracker of which players have the Wandscape panel open.
 * Used by {@link BuildingInteractHandler}
 * to gate right-click building interactions.
 *
 * <p>Pushes {@link ColonyStatsSyncPacket} on colony evaluation change, tourist
 * arrival/departure, and (once per server tick, coalesced) warehouse element
 * balance changes so the panel's top-bar numbers stay live.
 */
public final class PanelStateTracker {

    private static final Set<UUID> panelOpenPlayers = ConcurrentHashMap.newKeySet();

    /** Colonies whose element balance changed; flushed once per server tick. */
    private static final Set<UUID> pendingElementSyncColonies = ConcurrentHashMap.newKeySet();

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
        syncHudForColony(event.getColonyId());
    }

    @SubscribeEvent
    public static void onTouristArrived(TouristArrivedEvent event) {
        syncHudForColony(event.getColonyId());
    }

    @SubscribeEvent
    public static void onTouristDeparted(TouristDepartedEvent event) {
        syncHudForColony(event.getColonyId());
    }

    @SubscribeEvent
    public static void onElementBalanceChanged(ElementBalanceChangedEvent event) {
        if (event.getColonyId() != null) {
            pendingElementSyncColonies.add(event.getColonyId());
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (pendingElementSyncColonies.isEmpty()) return;
        for (UUID colonyId : pendingElementSyncColonies) {
            syncHudForColony(colonyId);
        }
        pendingElementSyncColonies.clear();
    }

    private static void syncHudForColony(UUID colonyId) {
        if (panelOpenPlayers.isEmpty()) return;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        ColonyStatusApi metricsApi = WandscapeApis.getColonyStatusApiSilently();
        if (metricsApi == null) return;

        ColonyStatusSnapshot snap = metricsApi.getSnapshotSafe(colonyId);

        for (UUID playerId : panelOpenPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) continue;
            // 顶栏只推给「当前镇 == 该殖民地」的开面板玩家：多镇下必须比当前镇，
            // 比创始人（getColonyByFounder）会让被邀请参与别人的镇、以及被转让出去的成员
            // 永远收不到自己当前镇的顶栏数据。
            // 无当前镇 = 建镇引导态，不推任何殖民地数据，绝不退化为空间最近小镇。
            UUID playerColony = ColonyOwnership.activeColony(player);
            if (snap.colonyId() != null && snap.colonyId().equals(playerColony)) {
                Net.toPlayer(player, ColonyStatsSyncPacket.fromSnapshot(snap));
            }
        }
    }
}
