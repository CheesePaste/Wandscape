package com.wsteam.wandscape.foundation.registry.dataconfig.internal;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Consumer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * 通用 datapack 数据同步分块（专用服务器 → 客户端）。
 *
 * <p>魔法定/法杖预设/生产配方/元素映射等 {@code WandscapeDataLoader} 数据只随服务端 reload
 * 从 {@code data/} 加载；客户端 reload 只扫 {@code assets/}，专用服务器客户端进程拿不到，
 * 导致 JEI 无配方、创造栏 NBT 变体缺失。此包把"一个类目的原始 JSON"压缩后分块发给客户端，
 * 客户端整包后用 {@link WandscapeDataLoader#applyCategoryFrom} 灌回对应 registry。
 * 建筑配置已有独立同步（BuildingConfigSyncChunkPacket），不走这里。
 *
 * <p>结构仿 BuildingConfigSyncChunkPacket：整类数据合并成一个 payload，zlib 压缩后按
 * {@link #CHUNK_BYTES} 切块。总文件数 = 需要同步的类目数。
 */
public record DatapackDataSyncChunkPacket(
        int fileIndex,
        int chunkIndex,
        int totalChunks,
        int totalFiles,
        byte[] payload
) implements CustomPacketPayload {

    /** 分块大小：远低于 2MB byte 数组限制，与服务端建筑同步同量级。 */
    public static final int CHUNK_BYTES = 16384;

    public static final Type<DatapackDataSyncChunkPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "datapack_data_sync_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DatapackDataSyncChunkPacket> STREAM_CODEC =
            StreamCodec.of(DatapackDataSyncChunkPacket::write, DatapackDataSyncChunkPacket::read);

    private static Consumer<DatapackDataSyncChunkPacket> clientHandler = packet -> {};

    public static void setClientHandler(Consumer<DatapackDataSyncChunkPacket> handler) {
        clientHandler = handler;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(DatapackDataSyncChunkPacket packet) {
        clientHandler.accept(packet);
    }

    static void write(RegistryFriendlyByteBuf buf, DatapackDataSyncChunkPacket pkt) {
        buf.writeVarInt(pkt.fileIndex());
        buf.writeVarInt(pkt.chunkIndex());
        buf.writeVarInt(pkt.totalChunks());
        buf.writeVarInt(pkt.totalFiles());
        buf.writeByteArray(pkt.payload());
    }

    static DatapackDataSyncChunkPacket read(RegistryFriendlyByteBuf buf) {
        return new DatapackDataSyncChunkPacket(
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readByteArray());
    }
}
