package com.wsteam.wandscape.content.task.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Notifies the server when the player opens or closes the Task Management submode
 * so the server only broadcasts live task data to subscribed players.
 */
public record TaskPanelSubscribePacket(boolean subscribe) implements CustomPacketPayload {

    public static final Type<TaskPanelSubscribePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "task_panel_subscribe"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TaskPanelSubscribePacket> STREAM_CODEC =
            StreamCodec.of(TaskPanelSubscribePacket::write, TaskPanelSubscribePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void write(RegistryFriendlyByteBuf buf, TaskPanelSubscribePacket pkt) {
        buf.writeBoolean(pkt.subscribe);
    }

    static TaskPanelSubscribePacket read(RegistryFriendlyByteBuf buf) {
        return new TaskPanelSubscribePacket(buf.readBoolean());
    }

    public static void handleServer(TaskPanelSubscribePacket packet, ServerPlayer player) {
        if (player == null || player.isRemoved()) return;
        if (packet.subscribe()) {
            TaskPanelSyncTracker.subscribe(player);
        } else {
            TaskPanelSyncTracker.unsubscribe(player);
        }
    }
}
