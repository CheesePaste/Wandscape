package com.wsteam.wandscape.engine.transport;

import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.wsteam.wandscape.shared.data.ItemKey;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * S→C: spawn a flying item visual from {@code from} to {@code to} over
 * {@code duration} ticks. {@code onRoad} selects the arc/flat flight look.
 */
public record TransportStartPacket(ItemKey itemKey, int count, BlockPos from, BlockPos to,
                                   int duration, boolean onRoad) implements CustomPacketPayload {

    public static final Type<TransportStartPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "transport_start"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TransportStartPacket> STREAM_CODEC =
            StreamCodec.of(TransportStartPacket::write, TransportStartPacket::read);

    private static Consumer<TransportStartPacket> clientHandler = packet -> {};
    public static void setClientHandler(Consumer<TransportStartPacket> handler) { clientHandler = handler; }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(TransportStartPacket packet) {
        clientHandler.accept(packet);
    }

    static void write(RegistryFriendlyByteBuf buf, TransportStartPacket pkt) {
        buf.writeUtf(pkt.itemKey().itemId());
        buf.writeBoolean(pkt.itemKey().nbt() != null);
        if (pkt.itemKey().nbt() != null) {
            buf.writeNbt(pkt.itemKey().nbt());
        }
        buf.writeInt(pkt.count());
        buf.writeBlockPos(pkt.from());
        buf.writeBlockPos(pkt.to());
        buf.writeInt(pkt.duration());
        buf.writeBoolean(pkt.onRoad());
    }

    static TransportStartPacket read(RegistryFriendlyByteBuf buf) {
        String itemId = buf.readUtf();
        CompoundTag nbt = buf.readBoolean() ? buf.readNbt() : null;
        ItemKey key = new ItemKey(itemId, nbt);
        int count = buf.readInt();
        BlockPos from = buf.readBlockPos();
        BlockPos to = buf.readBlockPos();
        int duration = buf.readInt();
        boolean onRoad = buf.readBoolean();

        return new TransportStartPacket(key, count, from, to, duration, onRoad);
    }
}
