package com.wsteam.wandscape.building.network;

import com.wsteam.wandscape.building.editor.BuildingEditorNetwork;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Client→Server: Player exits building editor mode.
 * Server removes the player from the editing set.
 */
public record BuildingEditorExitPacket() implements CustomPacketPayload {

    private static final String TAG = "BuildingEditorExitPacket";

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
        Log.info(TAG, "[BuildEditor] Player {} exited editor", player.getGameProfile().getName());
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, BuildingEditorExitPacket pkt) {
        // Empty payload
    }

    static BuildingEditorExitPacket read(RegistryFriendlyByteBuf buf) {
        return new BuildingEditorExitPacket();
    }
}
