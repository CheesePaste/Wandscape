package com.wsteam.wandscape.shared.bridge;

import com.wsteam.wandscape.shared.data.BehaviorType;

import net.minecraft.core.BlockPos;

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
