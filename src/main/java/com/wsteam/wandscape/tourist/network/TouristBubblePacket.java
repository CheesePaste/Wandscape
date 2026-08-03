package com.wsteam.wandscape.tourist.network;

import javax.annotation.Nullable;

import com.wsteam.wandscape.shared.client.bubble.TransientBubbleStore;

import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→client packet: notify players near a tourist that it made a shop
 * purchase or used a service, so a transient bubble + satisfaction bar can be
 * shown above it. Icon kinds mirror {@link TransientBubbleStore#ICON_ITEM} /
 * {@link TransientBubbleStore#ICON_ELEMENT} / {@link TransientBubbleStore#ICON_NONE}.
 */
public record TouristBubblePacket(
        int entityId,
        int iconKind,
        @Nullable String iconId,
        int count,
        int satBefore,
        int satAfter
) implements CustomPacketPayload {

    public static final Type<TouristBubblePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "tourist_bubble"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TouristBubblePacket> STREAM_CODEC =
            StreamCodec.of(TouristBubblePacket::write, TouristBubblePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(TouristBubblePacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity e = mc.level.getEntity(packet.entityId());
        if (e == null) return;
        TransientBubbleStore.trigger(e.getUUID(), packet.iconKind(), packet.iconId(),
                packet.count(), packet.satBefore(), packet.satAfter(), e.tickCount);
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, TouristBubblePacket pkt) {
        buf.writeInt(pkt.entityId);
        buf.writeVarInt(pkt.iconKind);
        buf.writeUtf(pkt.iconId != null ? pkt.iconId : "");
        buf.writeInt(pkt.count);
        buf.writeInt(pkt.satBefore);
        buf.writeInt(pkt.satAfter);
    }

    static TouristBubblePacket read(RegistryFriendlyByteBuf buf) {
        int entityId = buf.readInt();
        int iconKind = buf.readVarInt();
        String iconId = buf.readUtf();
        int count = buf.readInt();
        int satBefore = buf.readInt();
        int satAfter = buf.readInt();
        return new TouristBubblePacket(entityId, iconKind, iconId.isEmpty() ? null : iconId,
                count, satBefore, satAfter);
    }
}
