package com.wsteam.wandscape.npc.network;

import static com.wsteam.wandscape.Wandscape.MODID;

import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client→Server packet: update NPC mode flags (following, peaceful).
 */
public record NpcModePacket(int entityId, boolean isFollowing, boolean isPeaceful) implements CustomPacketPayload {

    public static final Type<NpcModePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "npc_mode"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcModePacket> STREAM_CODEC =
            StreamCodec.of(NpcModePacket::write, NpcModePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void write(RegistryFriendlyByteBuf buf, NpcModePacket pkt) {
        buf.writeInt(pkt.entityId);
        buf.writeBoolean(pkt.isFollowing);
        buf.writeBoolean(pkt.isPeaceful);
    }

    static NpcModePacket read(RegistryFriendlyByteBuf buf) {
        return new NpcModePacket(buf.readInt(), buf.readBoolean(), buf.readBoolean());
    }

    private static final String TAG = "NpcModePacket";

    public static void handleServer(NpcModePacket packet, ServerPlayer player) {
        if (player == null || player.isRemoved()) return;

        var level = player.serverLevel();
        var entity = level.getEntity(packet.entityId());
        if (!(entity instanceof WandscapeNpc npc)) {
            Log.warn(TAG, "Mode target entity {} is not a WandscapeNpc", packet.entityId());
            return;
        }

        npc.setFollowing(packet.isFollowing(), player.getUUID());
        npc.setPeaceful(packet.isPeaceful());
        Log.info(TAG, "NPC {} mode updated: following={}, peaceful={}",
                npc.getUUID().toString().substring(0, 8), packet.isFollowing(), packet.isPeaceful());
    }
}
