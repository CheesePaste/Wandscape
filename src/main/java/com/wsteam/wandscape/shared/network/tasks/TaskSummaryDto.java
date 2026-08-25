package com.wsteam.wandscape.shared.network.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * Compact DTO representing a task for the Global Task & Mage Management panel.
 */
public record TaskSummaryDto(
        long taskId,
        String category,
        String title,
        String blueprintId,
        @Nullable UUID buildingId,
        String buildingName,
        String state, // "IN_PROGRESS", "AWAITING_RESOURCES", "PENDING_ASSIGN", "QUEUED"
        int priority,
        int stepIndex,
        int totalSteps,
        int channelRemainingTicks,
        int channelTotalTicks,
        long assignedNpcId,
        @Nullable UUID assignedNpcUuid,
        String assignedNpcName,
        List<ResourceShortageDto> shortages,
        boolean hasTargetPos,
        int targetX,
        int targetY,
        int targetZ,
        String blockerReason // "WAITING_NPC", "WAITING_MANA", "OCCUPIED_POS", "MISSING_RESOURCES", "QUEUED_STAGE", etc.
) {

    public float getProgress() {
        if (channelTotalTicks > 0) {
            int elapsed = channelTotalTicks - Math.max(0, channelRemainingTicks);
            return Math.clamp((float) elapsed / channelTotalTicks, 0f, 1f);
        }
        if (totalSteps > 0) {
            return Math.clamp((float) stepIndex / totalSteps, 0f, 1f);
        }
        return 0f;
    }

    public static void write(RegistryFriendlyByteBuf buf, TaskSummaryDto dto) {
        buf.writeVarLong(dto.taskId);
        buf.writeUtf(dto.category != null ? dto.category : "task");
        buf.writeUtf(dto.title != null ? dto.title : "");
        buf.writeUtf(dto.blueprintId != null ? dto.blueprintId : "");
        buf.writeBoolean(dto.buildingId != null);
        if (dto.buildingId != null) buf.writeUUID(dto.buildingId);
        buf.writeUtf(dto.buildingName != null ? dto.buildingName : "");
        buf.writeUtf(dto.state != null ? dto.state : "PENDING_ASSIGN");
        buf.writeVarInt(dto.priority);
        buf.writeVarInt(dto.stepIndex);
        buf.writeVarInt(dto.totalSteps);
        buf.writeVarInt(dto.channelRemainingTicks);
        buf.writeVarInt(dto.channelTotalTicks);
        buf.writeVarLong(dto.assignedNpcId);
        buf.writeBoolean(dto.assignedNpcUuid != null);
        if (dto.assignedNpcUuid != null) buf.writeUUID(dto.assignedNpcUuid);
        buf.writeUtf(dto.assignedNpcName != null ? dto.assignedNpcName : "");
        
        buf.writeVarInt(dto.shortages != null ? dto.shortages.size() : 0);
        if (dto.shortages != null) {
            for (ResourceShortageDto shortage : dto.shortages) {
                ResourceShortageDto.write(buf, shortage);
            }
        }

        buf.writeBoolean(dto.hasTargetPos);
        if (dto.hasTargetPos) {
            buf.writeVarInt(dto.targetX);
            buf.writeVarInt(dto.targetY);
            buf.writeVarInt(dto.targetZ);
        }
        buf.writeUtf(dto.blockerReason != null ? dto.blockerReason : "");
    }

    public static TaskSummaryDto read(RegistryFriendlyByteBuf buf) {
        long taskId = buf.readVarLong();
        String category = buf.readUtf();
        String title = buf.readUtf();
        String blueprintId = buf.readUtf();
        UUID buildingId = buf.readBoolean() ? buf.readUUID() : null;
        String buildingName = buf.readUtf();
        String state = buf.readUtf();
        int priority = buf.readVarInt();
        int stepIndex = buf.readVarInt();
        int totalSteps = buf.readVarInt();
        int channelRemainingTicks = buf.readVarInt();
        int channelTotalTicks = buf.readVarInt();
        long assignedNpcId = buf.readVarLong();
        UUID assignedNpcUuid = buf.readBoolean() ? buf.readUUID() : null;
        String assignedNpcName = buf.readUtf();

        int shortageCount = buf.readVarInt();
        List<ResourceShortageDto> shortages = new ArrayList<>(shortageCount);
        for (int i = 0; i < shortageCount; i++) {
            shortages.add(ResourceShortageDto.read(buf));
        }

        boolean hasTargetPos = buf.readBoolean();
        int targetX = 0, targetY = 0, targetZ = 0;
        if (hasTargetPos) {
            targetX = buf.readVarInt();
            targetY = buf.readVarInt();
            targetZ = buf.readVarInt();
        }
        String blockerReason = buf.readUtf();

        return new TaskSummaryDto(
                taskId, category, title, blueprintId, buildingId, buildingName,
                state, priority, stepIndex, totalSteps,
                channelRemainingTicks, channelTotalTicks,
                assignedNpcId, assignedNpcUuid, assignedNpcName,
                shortages, hasTargetPos, targetX, targetY, targetZ,
                blockerReason
        );
    }
}
