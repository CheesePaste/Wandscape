package com.wsteam.wandscape.road.network;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Player toggles road editor mode.
 *
 * <p>Mirrors {@code /wandscape road edit} command logic.
 * If the player is currently editing, exit edit mode.
 * If not editing, enter edit mode (sync network to player).
 */
public record RoadEditorTogglePacket() implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<RoadEditorTogglePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "road_editor_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoadEditorTogglePacket> STREAM_CODEC =
            StreamCodec.of(RoadEditorTogglePacket::write, RoadEditorTogglePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    public static void handleServer(RoadEditorTogglePacket packet, ServerPlayer player) {
        if (RoadEditorNetwork.isEditing(player)) {
            // Exit edit mode
            RoadEditorNetwork.removeEditing(player);
            RoadEditorNetwork.sendExitToPlayer(player);
            player.displayClientMessage(
                    Component.literal("§eRoad edit mode: §cOFF"), true);
            LOGGER.info("[RoadEditor] Toggle: exit for {}", player.getGameProfile().getName());
        } else {
            // Enter edit mode
            RoadEditorNetwork.addEditing(player);
            RoadEditorNetwork.sendSyncToPlayer(player);
            player.displayClientMessage(
                    Component.literal("§aRoad edit mode: §2ON"), true);
            LOGGER.info("[RoadEditor] Toggle: enter for {}", player.getGameProfile().getName());
        }
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, RoadEditorTogglePacket pkt) {
        // Empty payload
    }

    static RoadEditorTogglePacket read(RegistryFriendlyByteBuf buf) {
        return new RoadEditorTogglePacket();
    }
}
