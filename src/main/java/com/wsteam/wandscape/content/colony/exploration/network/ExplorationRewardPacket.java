package com.wsteam.wandscape.content.colony.exploration.network;

import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.networking.ClientPayloadDispatcher;
import com.wsteam.wandscape.foundation.networking.Net;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→Client packet driving the exploration chest HUD card: the region display name, the
 * colony experience gained and the element rewards deposited.
 *
 * <p>The card, not chat or the action bar, is the point: it renders above any open screen,
 * so it is still readable over the chest GUI that opening one puts on the screen.
 */
public record ExplorationRewardPacket(String regionName, int exp, Map<ElementType, Long> elements)
        implements CustomPacketPayload {

    public static final Type<ExplorationRewardPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "exploration_reward"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ExplorationRewardPacket> STREAM_CODEC =
            StreamCodec.of(ExplorationRewardPacket::encode, ExplorationRewardPacket::decode);

    public ExplorationRewardPacket {
        elements = Collections.unmodifiableMap(new LinkedHashMap<>(elements));
    }

    public static void encode(RegistryFriendlyByteBuf buf, ExplorationRewardPacket packet) {
        buf.writeUtf(packet.regionName());
        buf.writeVarInt(packet.exp());
        buf.writeVarInt(packet.elements().size());
        for (Map.Entry<ElementType, Long> entry : packet.elements().entrySet()) {
            buf.writeUtf(entry.getKey().getId());
            buf.writeVarLong(entry.getValue());
        }
    }

    public static ExplorationRewardPacket decode(RegistryFriendlyByteBuf buf) {
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

    // ── Client handler (injected by WandscapeClient) ──



    public static void handleClient(ExplorationRewardPacket packet) {
        ClientPayloadDispatcher.dispatch(packet);
    }
}
