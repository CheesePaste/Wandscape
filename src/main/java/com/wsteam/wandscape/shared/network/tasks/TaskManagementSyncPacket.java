package com.wsteam.wandscape.shared.network.tasks;

import static com.wsteam.wandscape.Wandscape.MODID;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.shared.ui.panel.TaskManagementClientState;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server→Client: Full live snapshot for the Task Management Drawer.
 */
public record TaskManagementSyncPacket(
        UUID colonyId,
        List<TaskSummaryDto> tasks,
        List<MageSummaryDto> mages,
        int totalActiveTasks,
        int idleMageCount,
        int totalMageCount
) implements CustomPacketPayload {

    public static final Type<TaskManagementSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "task_management_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TaskManagementSyncPacket> STREAM_CODEC =
            StreamCodec.of(TaskManagementSyncPacket::write, TaskManagementSyncPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(TaskManagementSyncPacket packet) {
        TaskManagementClientState.update(packet);
    }

    static void write(RegistryFriendlyByteBuf buf, TaskManagementSyncPacket pkt) {
        buf.writeBoolean(pkt.colonyId != null);
        if (pkt.colonyId != null) buf.writeUUID(pkt.colonyId);

        buf.writeVarInt(pkt.tasks != null ? pkt.tasks.size() : 0);
        if (pkt.tasks != null) {
            for (TaskSummaryDto task : pkt.tasks) {
                TaskSummaryDto.write(buf, task);
            }
        }

        buf.writeVarInt(pkt.mages != null ? pkt.mages.size() : 0);
        if (pkt.mages != null) {
            for (MageSummaryDto mage : pkt.mages) {
                MageSummaryDto.write(buf, mage);
            }
        }

        buf.writeVarInt(pkt.totalActiveTasks);
        buf.writeVarInt(pkt.idleMageCount);
        buf.writeVarInt(pkt.totalMageCount);
    }

    static TaskManagementSyncPacket read(RegistryFriendlyByteBuf buf) {
        UUID colonyId = buf.readBoolean() ? buf.readUUID() : null;

        int taskCount = buf.readVarInt();
        List<TaskSummaryDto> tasks = new ArrayList<>(taskCount);
        for (int i = 0; i < taskCount; i++) {
            tasks.add(TaskSummaryDto.read(buf));
        }

        int mageCount = buf.readVarInt();
        List<MageSummaryDto> mages = new ArrayList<>(mageCount);
        for (int i = 0; i < mageCount; i++) {
            mages.add(MageSummaryDto.read(buf));
        }

        int totalActiveTasks = buf.readVarInt();
        int idleMageCount = buf.readVarInt();
        int totalMageCount = buf.readVarInt();

        return new TaskManagementSyncPacket(colonyId, tasks, mages, totalActiveTasks, idleMageCount, totalMageCount);
    }
}
