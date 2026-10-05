package com.wsteam.wandscape.content.colony.network;

import com.wsteam.wandscape.content.colony.roster.ColonyRole;

import com.wsteam.wandscape.foundation.networking.Net;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Client→Server: Updates the colony display name.
 * Player types a new name in the Town Hall screen → this packet carries it to the server.
 */
public record ColonyNameUpdatePacket(UUID colonyId, String name) implements CustomPacketPayload {

    public static final Type<ColonyNameUpdatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "colony_name_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ColonyNameUpdatePacket> STREAM_CODEC =
            StreamCodec.of(ColonyNameUpdatePacket::write, ColonyNameUpdatePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleServer(ColonyNameUpdatePacket packet, ServerPlayer player) {
        // 档位：改小镇名 = OWNER（映射「改小镇设置」），其余档位一律拒止。
        if (!com.wsteam.wandscape.content.colony.ownership.ColonyOwnership
                .hasRole(player, packet.colonyId(), ColonyRole.OWNER)) {
            com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.deny(player, "town", "小镇");
            return;
        }
        var colonyApi = com.wsteam.wandscape.api.WandscapeApis.getColonyApiSilently();
        String name = packet.name().trim();
        if (name.length() > 30) name = name.substring(0, 30);
        if (colonyApi != null) colonyApi.setColonyName(packet.colonyId(), name);

        var metricsApi = com.wsteam.wandscape.api.WandscapeApis.getColonyStatusApiSilently();
        if (metricsApi != null) {
            // getSnapshotSafe：快照异常不再冒泡出包 handler（禁崩溃）。
            // 此处 colonyId 已由上面的 hasRole 判定保证非空，所以 EMPTY（colonyId=null）意味着
            // 「取数失败」而不是「引导态空包」——失败时降级为不发，保留客户端上一份已知状态，
            // 别把顶栏/面板当前镇清空（与 ProjectionEnterPacket 同一惯例）。
            var snap = metricsApi.getSnapshotSafe(packet.colonyId());
            if (snap.colonyId() != null) {
                Net.toPlayer(player,
                        ColonyStatsSyncPacket.fromSnapshot(snap));
            }
        }
    }

    static void write(RegistryFriendlyByteBuf buf, ColonyNameUpdatePacket pkt) {
        buf.writeUUID(pkt.colonyId);
        buf.writeUtf(pkt.name != null ? pkt.name : "");
    }

    static ColonyNameUpdatePacket read(RegistryFriendlyByteBuf buf) {
        return new ColonyNameUpdatePacket(buf.readUUID(), buf.readUtf());
    }
}
