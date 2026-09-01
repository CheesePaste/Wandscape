package com.wsteam.wandscape.shared.network;

import com.wsteam.wandscape.content.building.internal.BuildingInteractHandler;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.api.ColonyMetricsApi;
import com.wsteam.wandscape.shared.data.ColonyMetricsSnapshot;
import com.wsteam.wandscape.shared.event.ColonyEvaluationChangedEvent;
import com.wsteam.wandscape.shared.event.ElementBalanceChangedEvent;
import com.wsteam.wandscape.shared.event.TouristArrivedEvent;
import com.wsteam.wandscape.shared.event.TouristDepartedEvent;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
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

        ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi == null) return;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        ColonyMetricsApi metricsApi = WandscapeApis.getColonyMetricsApiSilently();
        if (metricsApi == null) return;

        ColonyMetricsSnapshot snap = metricsApi.getSnapshotSafe(colonyId);

        for (UUID playerId : panelOpenPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) continue;
            // 面板永远操作玩家自己的小镇（与 PanelStateTogglePacket 打开时一致）：先按 founder
            // 匹配（无视距离，远程俯视/Sim 态也收得到），无则退化为就近殖民地。
            UUID playerColony = colonyApi.getColonyByFounder(playerId);
            if (playerColony == null) {
                playerColony = colonyApi.getColonyId(player.blockPosition());
            }
            if (snap.colonyId() != null && snap.colonyId().equals(playerColony)) {
                PacketDistributor.sendToPlayer(player, ColonyStatsSyncPacket.fromSnapshot(snap));
            }
        }
    }
}
