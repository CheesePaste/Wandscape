package com.wsteam.wandscape.content.building.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Consumer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * One chunk of one building config, sent over the wire as a compressed byte slice.
 *
 * <p>Each building JSON is zlib-compressed then split into {@code totalChunks} chunks
 * of {@link #CHUNK_BYTES}. The client reassembles chunks per {@code configIndex} and
 * registers the config once all chunks arrive. Sending raw bytes (writeByteArray) avoids
 * the 262144-char Utf8String field limit that the old whole-config string hit.
 */
public record BuildingConfigSyncChunkPacket(
        int configIndex,
        int chunkIndex,
        int totalChunks,
        int totalConfigs,
        byte[] payload
) implements CustomPacketPayload {

    /** Chunk size: far below both the 2MB byte-array limit and the 262144-char string limit. */
    public static final int CHUNK_BYTES = 16384;

    public static final Type<BuildingConfigSyncChunkPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "building_config_sync_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingConfigSyncChunkPacket> STREAM_CODEC =
            StreamCodec.of(BuildingConfigSyncChunkPacket::write, BuildingConfigSyncChunkPacket::read);

    private static Consumer<BuildingConfigSyncChunkPacket> clientHandler = packet -> {};
    public static void setClientHandler(Consumer<BuildingConfigSyncChunkPacket> handler) { clientHandler = handler; }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(BuildingConfigSyncChunkPacket packet) {
        clientHandler.accept(packet);
    }

    static void write(RegistryFriendlyByteBuf buf, BuildingConfigSyncChunkPacket pkt) {
        buf.writeVarInt(pkt.configIndex());
        buf.writeVarInt(pkt.chunkIndex());
        buf.writeVarInt(pkt.totalChunks());
        buf.writeVarInt(pkt.totalConfigs());
        buf.writeByteArray(pkt.payload());
    }

    static BuildingConfigSyncChunkPacket read(RegistryFriendlyByteBuf buf) {
        return new BuildingConfigSyncChunkPacket(
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readByteArray());
    }
}
