package com.wsteam.wandscape.content.colony.exploration.network;

import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.networking.ClientPayloadDispatcher;
import com.wsteam.wandscape.foundation.networking.Net;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→Client packet driving the exploration chest HUD card. Two shapes share the card:
 * a payout (region display name, gained colony experience, element rewards) and a plain
 * {@code message} for chest outcomes that pay nothing — e.g. a wild chest opened before the
 * player owns a town.
 *
 * <p>The card, not chat or the action bar, is the point: it renders above any open screen,
 * so it is still readable over the chest GUI that opening one puts on the screen.
 * A packet never carries both shapes — {@code message} non-null means the payout fields
 * are unused (empty), and vice versa.
 */
public record ExplorationRewardPacket(String regionName, int exp, Map<ElementType, Long> elements,
                                      Component message)
        implements CustomPacketPayload {

    public static final Type<ExplorationRewardPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "exploration_reward"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ExplorationRewardPacket> STREAM_CODEC =
            StreamCodec.of(ExplorationRewardPacket::encode, ExplorationRewardPacket::decode);

    public ExplorationRewardPacket {
        elements = Collections.unmodifiableMap(new LinkedHashMap<>(elements));
    }

    /** Payout packet: the card shows the EXP and element gains. */
    public ExplorationRewardPacket(String regionName, int exp, Map<ElementType, Long> elements) {
        this(regionName, exp, elements, null);
    }

    /** Notice packet: the card shows a message instead of a payout. */
    public static ExplorationRewardPacket notice(Component message) {
        return new ExplorationRewardPacket("", 0, Map.of(), message);
    }

    public static void encode(RegistryFriendlyByteBuf buf, ExplorationRewardPacket packet) {
        boolean hasMessage = packet.message() != null;
        buf.writeBoolean(hasMessage);
        if (hasMessage) {
            ComponentSerialization.STREAM_CODEC.encode(buf, packet.message());
            return;
        }
        buf.writeUtf(packet.regionName());
        buf.writeVarInt(packet.exp());
        buf.writeVarInt(packet.elements().size());
        for (Map.Entry<ElementType, Long> entry : packet.elements().entrySet()) {
            buf.writeUtf(entry.getKey().getId());
            buf.writeVarLong(entry.getValue());
        }
    }

    public static ExplorationRewardPacket decode(RegistryFriendlyByteBuf buf) {
        if (buf.readBoolean()) {
            return notice(ComponentSerialization.STREAM_CODEC.decode(buf));
        }
        String regionName = buf.readUtf();
        int exp = buf.readVarInt();
        int size = buf.readVarInt();
        Map<ElementType, Long> elements = new LinkedHashMap<>(size);
        for (int i = 0; i < size; i++) {
            String typeId = buf.readUtf();
            long val = buf.readVarLong();
            try {
                elements.put(ElementType.fromId(typeId), val);
            } catch (Exception ignored) {}
        }
        return new ExplorationRewardPacket(regionName, exp, elements);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Helper to dispatch reward notification to a player. */
    public static void send(ServerPlayer player, String regionName, int exp, Map<ElementType, Long> elements) {
        if (player != null && !player.isRemoved()) {
            Net.toPlayer(player, new ExplorationRewardPacket(regionName, exp, elements));
        }
    }

    /** Helper to dispatch a chest notice (no payout) to a player. */
    public static void sendNotice(ServerPlayer player, Component message) {
        if (player != null && !player.isRemoved()) {
            Net.toPlayer(player, notice(message));
        }
    }

    // ── Client handler (injected by WandscapeClient) ──



    public static void handleClient(ExplorationRewardPacket packet) {
        ClientPayloadDispatcher.dispatch(packet);
    }
}
