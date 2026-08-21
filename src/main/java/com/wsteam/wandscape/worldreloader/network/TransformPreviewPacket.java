package com.wsteam.wandscape.worldreloader.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Packet sent from server to client to display a ghost preview of the completed terrain transformation.
 * Transparency of the ghost increases over durationTicks before actual blocks are transformed.
 */
public record TransformPreviewPacket(
        BlockPos center,
        int radius,
        int durationTicks,
        List<PreviewBlock> blocks
) implements CustomPacketPayload {

    public static final Type<TransformPreviewPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "transform_preview"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TransformPreviewPacket> STREAM_CODEC =
            StreamCodec.of(TransformPreviewPacket::write, TransformPreviewPacket::read);

    public record PreviewBlock(short dx, short dy, short dz, int stateId) {
        public BlockState state() {
            return Block.stateById(stateId);
        }
    }

    private static Consumer<TransformPreviewPacket> clientHandler = packet -> {};
    public static void setClientHandler(Consumer<TransformPreviewPacket> handler) {
        clientHandler = handler;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(TransformPreviewPacket packet) {
        clientHandler.accept(packet);
    }

    static void write(RegistryFriendlyByteBuf buf, TransformPreviewPacket pkt) {
        buf.writeBlockPos(pkt.center());
        buf.writeVarInt(pkt.radius());
        buf.writeVarInt(pkt.durationTicks());
        buf.writeVarInt(pkt.blocks().size());
        for (PreviewBlock b : pkt.blocks()) {
            buf.writeShort(b.dx());
            buf.writeShort(b.dy());
            buf.writeShort(b.dz());
            buf.writeVarInt(b.stateId());
        }
    }

    static TransformPreviewPacket read(RegistryFriendlyByteBuf buf) {
        BlockPos center = buf.readBlockPos();
        int radius = buf.readVarInt();
        int durationTicks = buf.readVarInt();
        int count = buf.readVarInt();
        List<PreviewBlock> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            short dx = buf.readShort();
            short dy = buf.readShort();
            short dz = buf.readShort();
            int stateId = buf.readVarInt();
            blocks.add(new PreviewBlock(dx, dy, dz, stateId));
        }
        return new TransformPreviewPacket(center, radius, durationTicks, blocks);
    }
}
