package com.wsteam.wandscape.shared.data;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
public interface BuildingData {
    UUID getBuildingId();
    UUID getColonyId();
    String getBuildingTypeId();
    String getCategory();
    BlockPos getPosition();
    boolean isShutdown();
    int getComfort();
    int getMagic();
    int getWonder();
    int getQueueCapacity();
    boolean isStructureIntact();

    /**
     * Whether the building has ever completed construction. Sticky — stays true
     * even if the building later becomes damaged. Drives the client construction
     * ghost: only buildings that never completed show a footprint.
     */
    default boolean hasEverCompleted() {
        return isStructureIntact();
    }

    /**
     * Whether construction work has begun placing blocks. Sticky — once an NPC
     * claims a {@code build:place_structure} task the building is "under
     * construction" and never reverts to "waiting for materials". Reused by the
     * anomaly report and debug overlay to label a not-yet-completed building's
     * phase.
     */
    default boolean isConstructionStarted() {
        return hasEverCompleted();
    }

    /** Building's world-space bounding box, for FX placement (particles/sounds). */
    @Nullable
    default BoundingBox getBounds() { return null; }

    /** Whether the building is being demolished by an NPC task. */
    default boolean isDemolishing() { return false; }

    /** Reason for shutdown, or empty string if not shut down. */
    default String getShutdownReason() { return ""; }

    /** Number of 90° CCW rotation steps applied to the building (0-3). */
    default int getRotationSteps() { return 0; }
}
