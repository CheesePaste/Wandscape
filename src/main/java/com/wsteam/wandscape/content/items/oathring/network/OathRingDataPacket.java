package com.wsteam.wandscape.content.items.oathring.network;

import com.wsteam.wandscape.content.items.oathring.client.OathRingClientData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;

/** Server→Client: 玩家盟誓戒指共享空间的已占槽位掩码（bit i = 槽 i 已占）。 */
public record OathRingDataPacket(byte occupancyMask) implements CustomPacketPayload {

    public static final Type<OathRingDataPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "oath_ring_data"));

    public static final StreamCodec<FriendlyByteBuf, OathRingDataPacket> STREAM_CODEC =
            StreamCodec.of(OathRingDataPacket::write, OathRingDataPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(OathRingDataPacket packet) {
        OathRingClientData.setOccupancy(packet.occupancyMask);
    }

    private static void write(FriendlyByteBuf buf, OathRingDataPacket pkt) {
        buf.writeByte(pkt.occupancyMask);
    }

    private static OathRingDataPacket read(FriendlyByteBuf buf) {
        return new OathRingDataPacket(buf.readByte());
    }
}