package com.wsteam.wandscape.engine.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.wsteam.wandscape.road.core.SplineLeg;
import com.wsteam.wandscape.road.core.SplineModel;
import com.wsteam.wandscape.road.core.SplinePoint;
import com.wsteam.wandscape.road.core.SplineVec3;
import com.wsteam.wandscape.road.core.TransportRoute;
import com.wsteam.wandscape.shared.data.ItemKey;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * S→C: spawn a flying item visual along a multi-leg {@link TransportRoute}.
 */
public record TransportStartPacket(ItemKey itemKey, int count, BlockPos from, TransportRoute route) implements CustomPacketPayload {

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

        TransportRoute r = pkt.route();
        if (r == null || r.isEmpty()) {
            buf.writeInt(0);
        } else {
            buf.writeInt(r.legs().size());
            for (SplineLeg leg : r.legs()) {
                buf.writeDouble(leg.uStart());
                buf.writeDouble(leg.uEnd());
                buf.writeBoolean(leg.offRoad());

                SplineModel spline = leg.spline();
                if (spline == null || spline.getPoints().isEmpty()) {
                    buf.writeInt(0);
                } else {
                    buf.writeInt(spline.getPoints().size());
                    for (SplinePoint sp : spline.getPoints()) {
                        buf.writeDouble(sp.getAnchor().x());
                        buf.writeDouble(sp.getAnchor().y());
                        buf.writeDouble(sp.getAnchor().z());

                        buf.writeDouble(sp.getControlPrev().x());
                        buf.writeDouble(sp.getControlPrev().y());
                        buf.writeDouble(sp.getControlPrev().z());

                        buf.writeDouble(sp.getControlNext().x());
                        buf.writeDouble(sp.getControlNext().y());
                        buf.writeDouble(sp.getControlNext().z());

                        buf.writeBoolean(sp.isLocked());
                    }
                }
            }
        }
    }

    static TransportStartPacket read(RegistryFriendlyByteBuf buf) {
        String itemId = buf.readUtf();
        CompoundTag nbt = buf.readBoolean() ? buf.readNbt() : null;
        ItemKey key = new ItemKey(itemId, nbt);
        int count = buf.readInt();
        BlockPos from = buf.readBlockPos();

        int legCount = buf.readInt();
        List<SplineLeg> legs = new ArrayList<>(legCount);
        for (int i = 0; i < legCount; i++) {
            double uStart = buf.readDouble();
            double uEnd = buf.readDouble();
            boolean offRoad = buf.readBoolean();

            int pointCount = buf.readInt();
            SplineModel spline = new SplineModel();
            for (int j = 0; j < pointCount; j++) {
                SplineVec3 anchor = new SplineVec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
                SplineVec3 prev = new SplineVec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
                SplineVec3 next = new SplineVec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
                boolean locked = buf.readBoolean();
                spline.getPoints().add(new SplinePoint(anchor, prev, next, locked));
            }
            legs.add(new SplineLeg(spline, uStart, uEnd, offRoad));
        }

        TransportRoute route = new TransportRoute(legs);
        return new TransportStartPacket(key, count, from, route);
    }
}
