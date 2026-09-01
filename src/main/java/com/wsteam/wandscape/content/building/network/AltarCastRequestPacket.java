package com.wsteam.wandscape.content.building.network;

import com.wsteam.wandscape.content.building.internal.AltarCastHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Client→server packet: player selects an altar-only spell in the Altar GUI.
 * Server validates (altarOnly + per-altar cooldown + colony mage mana) then
 * enqueues the altar-cast task.
 */
public record AltarCastRequestPacket(UUID buildingId, String magicId)
        implements CustomPacketPayload {

    public static final Type<AltarCastRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "altar_cast_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AltarCastRequestPacket> STREAM_CODEC =
            StreamCodec.of(AltarCastRequestPacket::write, AltarCastRequestPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleServer(AltarCastRequestPacket packet, ServerPlayer player) {
        AltarCastHandler.onCastRequest(player, packet.buildingId, packet.magicId());
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, AltarCastRequestPacket pkt) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("building", pkt.buildingId);
        tag.putString("magic", pkt.magicId);
        buf.writeNbt(tag);
    }

    static AltarCastRequestPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        if (tag == null) {
            return new AltarCastRequestPacket(new UUID(0, 0), "");
        }
        return new AltarCastRequestPacket(tag.getUUID("building"), tag.getString("magic"));
    }
}
