package com.wsteam.wandscape.content.colony.network;

import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.colony.settings.ColonySettings;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.Net;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: 本镇设置（设置中心「本镇」页）的一次改动。
 *
 * <p>包体不带 colonyId：服务端只按 founder 反查发起者自己的小镇，所以「改别人的小镇」
 * 根本无入口，也不必再叠一层归属校验——凭据不是客户端说了算的 id，而是玩家身份本身。
 *
 * <p>写入后回推一次 {@link ColonyStatsSyncPacket}：成功时是刚落盘的值，被拒时是服务端现值，
 * 客户端的乐观改动两种情况都会收敛到权威值。
 *
 * <p>Client→Server update for a per-colony setting. The target colony is resolved from the
 * sender's founder, never from the wire; the authoritative snapshot is pushed back either way
 * so an optimistic client edit always converges (accepted or rolled back).
 */
public record ColonySettingUpdatePacket(String key, String value) implements CustomPacketPayload {

    private static final String TAG = "ColonySettingUpdatePacket";

    public static final Type<ColonySettingUpdatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "colony_setting_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ColonySettingUpdatePacket> STREAM_CODEC =
            StreamCodec.of(ColonySettingUpdatePacket::write, ColonySettingUpdatePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleServer(ColonySettingUpdatePacket packet, ServerPlayer player) {
        if (player == null || player.isRemoved()) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        server.execute(() -> {
            ColonySettings.apply(player, packet.key(), packet.value());
            sendAuthoritative(player);
        });
    }

    /** 回推服务端权威快照：成功时是刚写入的值，失败时是现值（客户端据此撤回）。 */
    private static void sendAuthoritative(ServerPlayer player) {
        try {
            var colonyApi = WandscapeApis.getColonyApiSilently();
            var statusApi = WandscapeApis.getColonyStatusApiSilently();
            if (colonyApi == null || statusApi == null) return;
            UUID colonyId = colonyApi.getColonyByFounder(player.getUUID());
            if (colonyId == null) return;
            var snapshot = statusApi.getSnapshotSafe(colonyId);
            if (snapshot.colonyId() == null) return;
            Net.toPlayer(player, ColonyStatsSyncPacket.fromSnapshot(snapshot));
        } catch (Throwable t) {
            Log.warn(TAG, "Failed to push colony settings back to {}: {}",
                    player.getGameProfile().getName(), t.getMessage());
        }
    }

    static void write(RegistryFriendlyByteBuf buf, ColonySettingUpdatePacket pkt) {
        buf.writeUtf(pkt.key != null ? pkt.key : "");
        buf.writeUtf(pkt.value != null ? pkt.value : "");
    }

    static ColonySettingUpdatePacket read(RegistryFriendlyByteBuf buf) {
        return new ColonySettingUpdatePacket(buf.readUtf(), buf.readUtf());
    }
}
