package com.wsteam.wandscape.building.network;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.editor.BuildingEditorNetwork;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Player exits building editor mode.
 * Server removes the player from the editing set.
 */
public record BuildingEditorExitPacket() implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<BuildingEditorExitPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "build_editor_exit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingEditorExitPacket> STREAM_CODEC =
            StreamCodec.of(BuildingEditorExitPacket::write, BuildingEditorExitPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    public static void handleServer(BuildingEditorExitPacket packet, ServerPlayer player) {
        BuildingEditorNetwork.removeEditing(player);
        player.displayClientMessage(
                Component.literal("[BuildEditor] Exited editor mode"), false);
        LOGGER.info("[BuildEditor] Player {} exited editor", player.getGameProfile().getName());
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, BuildingEditorExitPacket pkt) {
        // Empty payload
    }

    static BuildingEditorExitPacket read(RegistryFriendlyByteBuf buf) {
        return new BuildingEditorExitPacket();
    }
}
