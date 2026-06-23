package com.wsteam.wandscape.shared.bridge;

import com.wsteam.wandscape.shared.data.BehaviorType;
import com.wsteam.wandscape.shared.data.TaskStatus;

import net.minecraft.core.BlockPos;

import com.wsteam.wandscape.core.task.TaskState;
import com.wsteam.wandscape.core.types.BehaviourTag;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.ResourceId;

/**
 * Bidirectional type mapping between core engine (com.wsteam.wandscape.core)
 * and wandscape MC layer (com.wsteam.wandscape).
 *
 * <p>Core engine types are pure Java, zero MC dependencies.
 * Wandscape types are MC-contextual (BlockPos, string IDs for serialization).
 * This bridge is the single place where both packages are imported together.
 */
public final class TypeBridge {
    private TypeBridge() {}

    // ---- BehaviourTag ↔ BehaviorType ----

    public static BehaviourTag toBehaviourTag(BehaviorType bt) {
        return BehaviourTag.valueOf(bt.name());
    }

    public static BehaviorType toBehaviorType(BehaviourTag tag) {
        return BehaviorType.valueOf(tag.name());
    }

    // ---- TaskState ↔ TaskStatus ----

    public static TaskState toTaskState(TaskStatus status) {
        return switch (status) {
            case PENDING_APPROVAL -> TaskState.PENDING_APPROVAL;
            case PENDING_ASSIGN -> TaskState.PENDING_ASSIGN;
            case IN_PROGRESS -> TaskState.IN_PROGRESS;
            case AWAITING_MATERIALS -> TaskState.AWAITING_RESOURCES;
            case INTERRUPTED -> TaskState.INTERRUPTED;
            case COMPLETED -> TaskState.COMPLETED;
            case FAILED -> TaskState.FAILED;
        };
    }

    public static TaskStatus toTaskStatus(TaskState state) {
        return switch (state) {
            case PENDING_APPROVAL -> TaskStatus.PENDING_APPROVAL;
            case PENDING_ASSIGN -> TaskStatus.PENDING_ASSIGN;
            case IN_PROGRESS -> TaskStatus.IN_PROGRESS;
            case AWAITING_RESOURCES -> TaskStatus.AWAITING_MATERIALS;
            case INTERRUPTED -> TaskStatus.INTERRUPTED;
            case COMPLETED -> TaskStatus.COMPLETED;
            case FAILED -> TaskStatus.FAILED;
        };
    }

    // ---- GridPos ↔ BlockPos ----

    public static GridPos toGridPos(BlockPos pos) {
        return new GridPos(pos.getX(), pos.getY(), pos.getZ());
    }

    public static BlockPos toBlockPos(GridPos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    // ---- ResourceId helpers ----

    public static ResourceId elementResourceId(String elementId) {
        return new ResourceId("element:" + elementId);
    }

    public static ResourceId itemResourceId(String itemId) {
        return new ResourceId("item:" + itemId);
    }

    public static boolean isElementResource(ResourceId id) {
        return id.id().startsWith("element:");
    }

    public static boolean isItemResource(ResourceId id) {
        return id.id().startsWith("item:");
    }
}
