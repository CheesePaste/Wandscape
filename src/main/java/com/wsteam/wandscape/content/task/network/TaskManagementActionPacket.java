package com.wsteam.wandscape.content.task.network;

import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.impl.WandscapeEngine;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: User actions from the task management panel (cancel task, rush priority, adjust priority).
 */
public record TaskManagementActionPacket(
        long taskId,
        String action, // "CANCEL", "RUSH", "SET_PRIORITY"
        int value
) implements CustomPacketPayload {

    private static final String TAG = "TaskManagementAction";

    public static final String ACTION_CANCEL = "CANCEL";
    public static final String ACTION_RUSH = "RUSH";
    public static final String ACTION_SET_PRIORITY = "SET_PRIORITY";

    public static final Type<TaskManagementActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "task_management_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TaskManagementActionPacket> STREAM_CODEC =
            StreamCodec.of(TaskManagementActionPacket::write, TaskManagementActionPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void write(RegistryFriendlyByteBuf buf, TaskManagementActionPacket pkt) {
        buf.writeVarLong(pkt.taskId);
        buf.writeUtf(pkt.action != null ? pkt.action : "");
        buf.writeVarInt(pkt.value);
    }

    static TaskManagementActionPacket read(RegistryFriendlyByteBuf buf) {
        return new TaskManagementActionPacket(
                buf.readVarLong(),
                buf.readUtf(),
                buf.readVarInt()
        );
    }

    public static void handleServer(TaskManagementActionPacket packet, ServerPlayer player) {
        if (player == null || player.isRemoved()) return;

        World world = WandscapeEngine.getWorld();
        if (world == null || world.taskPool == null) return;

        long taskId = packet.taskId();
        String action = packet.action();

        switch (action) {
            case ACTION_CANCEL -> {
                long releasedNpc = world.taskPool.cancelTask(taskId, world);
                Log.info(TAG, "Player {} cancelled task #{} (released NPC: {})",
                        player.getName().getString(), taskId, releasedNpc);
                TaskPanelSyncTracker.markDirty();
            }
            case ACTION_RUSH -> {
                boolean success = world.taskPool.updatePriority(taskId, 100);
                Log.info(TAG, "Player {} rushed task #{} (success: {})",
                        player.getName().getString(), taskId, success);
                TaskPanelSyncTracker.markDirty();
            }
            case ACTION_SET_PRIORITY -> {
                boolean success = world.taskPool.updatePriority(taskId, packet.value());
                Log.info(TAG, "Player {} set priority of task #{} to {} (success: {})",
                        player.getName().getString(), taskId, packet.value(), success);
                TaskPanelSyncTracker.markDirty();
            }
            default -> Log.warn(TAG, "Unknown task action: {}", action);
        }
    }
}
