package com.wsteam.wandscape.foundation.ui.panel;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.road.network.RoadAreaSyncPacket;
import com.wsteam.wandscape.content.building.network.BuildingAreaSyncPacket;
import com.wsteam.wandscape.content.colony.network.ColonyStatsSyncPacket;

import com.wsteam.wandscape.content.colony.ownership.ColonyOwnership;
import com.wsteam.wandscape.api.ColonyStatusApi;
import com.wsteam.wandscape.content.colony.data.ColonyStatusSnapshot;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.networking.Net;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Notifies server that the player opened or closed the Wandscape panel.
 * Server stores this state to gate building right-click interactions.
 *
 * <p>On open, the server resolves the player's **current** colony
 * ({@code ColonyOwnership.activeColony}) and pushes that colony's stats snapshot; the
 * building-area packet is sent either way (empty when there is no current colony, so a
 * previous world's cached boundaries cannot leak into the new save).
 *
 * <p>无当前镇 = 建镇引导态：**绝不回退到空间最近小镇**（否则无镇玩家会观察到别人的小镇数据），
 * 也**不会**自动为玩家创建殖民地——建镇只能由「右键无主市政厅 → 命名」触发。
 */
public record PanelStateTogglePacket(boolean open) implements CustomPacketPayload {

    public static final Type<PanelStateTogglePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "panel_state_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PanelStateTogglePacket> STREAM_CODEC =
            StreamCodec.of(PanelStateTogglePacket::write, PanelStateTogglePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleServer(PanelStateTogglePacket packet, ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (packet.open) {
            PanelStateTracker.open(playerId);
            // 完全平行隔离：面板永远只绑定玩家**当前操作的小镇**（可由玩家切换，不再等于
            // 「我创始的那座」）；没有当前镇 = 建镇引导态，
            // 绝不回退到空间「最近小镇」（否则无镇玩家会观察到别人的小镇数据）。
            UUID colonyId = ColonyOwnership.activeColony(player);
            // Always sync building areas（无当前镇时发空包清空客户端缓存）——否则缓存会带着
            // 上一世界（存档）的建筑边界框进入新存档，首次建建筑时误报重叠。
            BuildingAreaSyncPacket.sendToPlayer(player, colonyId);
            // 顶栏统计：无当前镇时 getSnapshotSafe(null) 给 EMPTY（colonyId = null），
            // 照发不误正好把客户端上一座镇的顶栏清空——这就是引导态要的「发空包清缓存」。
            ColonyStatusApi metricsApi = WandscapeApis.getColonyStatusApiSilently();
            if (metricsApi != null) {
                ColonyStatusSnapshot snap = metricsApi.getSnapshotSafe(colonyId);
                Net.toPlayer(player, ColonyStatsSyncPacket.fromSnapshot(snap));
            }

            // Roads are level-global — sync under-construction roads for the construction ghost.
            RoadAreaSyncPacket.sendToPlayer(player);

            // Seed tutorial progress (recomputed when a colony exists; otherwise
            // only the saved value so a pre-colony dismissal still persists).
            var tutorialApi = com.wsteam.wandscape.api.WandscapeApis.getTutorialApiSilently();
            if (tutorialApi != null) {
                tutorialApi.sendToPlayer(player, colonyId);
            }
        } else {
            PanelStateTracker.close(playerId);
        }
    }

    static void write(RegistryFriendlyByteBuf buf, PanelStateTogglePacket pkt) {
        buf.writeBoolean(pkt.open);
    }

    static PanelStateTogglePacket read(RegistryFriendlyByteBuf buf) {
        return new PanelStateTogglePacket(buf.readBoolean());
    }
}
