package com.wsteam.wandscape.content.road.network;

import com.wsteam.wandscape.api.RoadApi;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import com.wsteam.wandscape.content.road.core.RoadEdge;
import com.wsteam.wandscape.content.road.engine.RoadSavedData;

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: the construction-site panel's withdraw button was clicked for a road.
 * Server cancels the edge's build task, refunds materials, clears its blocks, and
 * removes the edge (idempotent + no double refund — see {@link RoadApi#cancelEdge}).
 */
public record RoadWithdrawPacket(UUID edgeId) implements CustomPacketPayload {

    private static final String TAG = "RoadWithdrawPacket";

    public static final Type<RoadWithdrawPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "road_withdraw"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoadWithdrawPacket> STREAM_CODEC =
            StreamCodec.of(RoadWithdrawPacket::write, RoadWithdrawPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    public static void handleServer(RoadWithdrawPacket packet, ServerPlayer player) {
        if (player == null || player.level() == null) return;

        RoadApi roadApi = WandscapeApis.getRoadApi();
        if (roadApi == null) {
            Log.warn(TAG, "RoadApi unavailable — cannot withdraw road");
            return;
        }
        RoadSavedData data = RoadSavedData.getOrCreate(player.serverLevel());
        RoadEdge edge = data.getNetwork().getEdge(packet.edgeId());
        if (edge == null) {
            Log.info(TAG, "[Withdraw] Road edge {} not found", packet.edgeId());
            return;
        }

        UUID edgeColonyId = edge.getColonyId();
        if (!com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.isOwn(edgeColonyId, player)) {
            com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.deny(player, "道路");
            return;
        }

        UUID colonyId = com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.ownColony(player);
        if (colonyId == null) {
            colonyId = edgeColonyId;
        }
        boolean ok = roadApi.cancelEdge(colonyId, packet.edgeId());

        ScreenFeedbackPacket.send(player, I18n.name(
                ok ? "message.wandscape.road.withdraw.success" : "message.wandscape.road.withdraw.failed",
                ok ? "§a已撤回道路" : "§c无法撤回该道路"), true);
        Log.info(TAG, "[Withdraw] edge {} → {}", packet.edgeId(), ok ? "withdrawn" : "skipped");
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, RoadWithdrawPacket pkt) {
        buf.writeUUID(pkt.edgeId);
    }

    static RoadWithdrawPacket read(RegistryFriendlyByteBuf buf) {
        return new RoadWithdrawPacket(buf.readUUID());
    }
}
