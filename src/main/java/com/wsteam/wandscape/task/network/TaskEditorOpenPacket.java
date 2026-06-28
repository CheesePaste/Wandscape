package com.wsteam.wandscape.task.network;

import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Client→Server packet signaling that a player opened the task editor.
 * The server responds with {@link BlueprintListResponsePacket}.
 */
public record TaskEditorOpenPacket() implements CustomPacketPayload {

    public static final Type<TaskEditorOpenPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "task_editor_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TaskEditorOpenPacket> STREAM_CODEC =
            StreamCodec.of(TaskEditorOpenPacket::write, TaskEditorOpenPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handle on server: send the blueprint list back to the player. */
    public static void handleServer(TaskEditorOpenPacket packet, ServerPlayer player) {
        TaskNetworkHandler.sendBlueprintList(player);
    }

    // ── StreamCodec (empty payload) ──

    static void write(RegistryFriendlyByteBuf buf, TaskEditorOpenPacket pkt) {
        // empty — no fields to write
    }

    static TaskEditorOpenPacket read(RegistryFriendlyByteBuf buf) {
        return new TaskEditorOpenPacket();
    }
}
