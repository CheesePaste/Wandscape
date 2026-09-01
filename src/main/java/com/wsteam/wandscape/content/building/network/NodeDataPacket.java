package com.wsteam.wandscape.content.building.network;

import com.wsteam.wandscape.shared.log.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Consumer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→client packet carrying node building data for the {@code NodeScreen}:
 * the harvested element and per-harvest stats (amount, channel ticks).
 */
public record NodeDataPacket(
    BlockPos nodePos,
    String buildingTypeId,
    String element,
    int amountPerHarvest,
    int channelTicks,
    String creator
) implements CustomPacketPayload {

    private static final String TAG = "NodeDataPacket";

    public static final Type<NodeDataPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "node_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NodeDataPacket> STREAM_CODEC =
            StreamCodec.of(NodeDataPacket::write, NodeDataPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static Consumer<NodeDataPacket> clientHandler;

    public static void setClientHandler(Consumer<NodeDataPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(NodeDataPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        } else {
            Log.warn(TAG, "NodeDataPacket: no client handler registered");
        }
    }

    static void write(RegistryFriendlyByteBuf buf, NodeDataPacket pkt) {
        buf.writeBlockPos(pkt.nodePos);
        buf.writeUtf(pkt.buildingTypeId);
        buf.writeUtf(pkt.element);
        buf.writeVarInt(pkt.amountPerHarvest);
        buf.writeVarInt(pkt.channelTicks);
        buf.writeUtf(pkt.creator != null ? pkt.creator : "");
    }

    static NodeDataPacket read(RegistryFriendlyByteBuf buf) {
        return new NodeDataPacket(
                buf.readBlockPos(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readUtf()
        );
    }
}
