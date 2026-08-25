package com.wsteam.wandscape.tourist.network;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;

public record TouristBubblePacket(
        int entityId,
        @Nullable String iconId,
        int count
) implements CustomPacketPayload {

    public static final Type<TouristBubblePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "tourist_bubble"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TouristBubblePacket> STREAM_CODEC =
            StreamCodec.of(TouristBubblePacket::write, TouristBubblePacket::read);

    private static Consumer<TouristBubblePacket> clientHandler = packet -> {};
    public static void setClientHandler(Consumer<TouristBubblePacket> handler) { clientHandler = handler; }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(TouristBubblePacket packet) {
        clientHandler.accept(packet);
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, TouristBubblePacket pkt) {
        buf.writeInt(pkt.entityId);
        buf.writeUtf(pkt.iconId != null ? pkt.iconId : "");
        buf.writeInt(pkt.count);
    }

    static TouristBubblePacket read(RegistryFriendlyByteBuf buf) {
        int entityId = buf.readInt();
        String iconId = buf.readUtf();
        int count = buf.readInt();
        return new TouristBubblePacket(entityId, iconId.isEmpty() ? null : iconId, count);
    }
}
