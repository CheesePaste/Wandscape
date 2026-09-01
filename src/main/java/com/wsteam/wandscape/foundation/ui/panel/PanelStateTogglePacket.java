package com.wsteam.wandscape.foundation.ui.panel;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.road.network.RoadAreaSyncPacket;
import com.wsteam.wandscape.content.building.network.BuildingAreaSyncPacket;
import com.wsteam.wandscape.content.colony.network.ColonyStatsSyncPacket;

import com.wsteam.wandscape.api.ColonyApi;
import com.wsteam.wandscape.api.ColonyStatusApi;
import com.wsteam.wandscape.content.colony.data.ColonyStatusSnapshot;
import com.wsteam.wandscape.api.WandscapeApis;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Notifies server that the player opened or closed the Wandscape panel.
 * Server stores this state to gate building right-click interactions.
 *
 * <p>On open, if no colony exists near the player, the server auto-creates one at the
 * player's position (moved earlier from the "first town hall placement with no colony"
 * flow), so the colony exists before the first building is placed — which the per-colony
 * first-free ({@code first_free}) claim requires.
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
            UUID colonyId = null;
            ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
            if (colonyApi != null) {
                // 小镇与玩家绑定：优先返回玩家自己的小镇（无论距离），面板永远操作自己的小镇。
                // 否则玩家已有小镇时走空间查找，在远处按 V 会新建第二个小镇。
                colonyId = colonyApi.getColonyByFounder(playerId);
                if (colonyId == null) {
                    colonyId = colonyApi.getColonyId(player.blockPosition());
                }
                // Always sync building areas（无小镇时发空包清空客户端缓存）——否则缓存会带着
                // 上一世界（存档）的建筑边界框进入新存档，首次建建筑时误报重叠。
                BuildingAreaSyncPacket.sendToPlayer(player, colonyId);
                if (colonyId != null) {
                    ColonyStatusApi metricsApi = WandscapeApis.getColonyStatusApiSilently();
                    if (metricsApi != null) {
                        ColonyStatusSnapshot snap = metricsApi.getSnapshotSafe(colonyId);
                        if (snap.colonyId() != null) {
                            PacketDistributor.sendToPlayer(player, ColonyStatsSyncPacket.fromSnapshot(snap));
                        }
                    }
                }
            }

            // Roads are level-global — sync under-construction roads for the construction ghost.
            RoadAreaSyncPacket.sendToPlayer(player);

            // Seed tutorial progress (recomputed when a colony exists; otherwise
            // only the saved value so a pre-colony dismissal still persists).
            var guideApi = com.wsteam.wandscape.api.WandscapeApis.getGuideProgressApiSilently();
            if (guideApi != null) {
                guideApi.sendToPlayer(player, colonyId);
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
