package com.wsteam.wandscape.citizen;

import net.minecraft.core.BlockPos;
import javax.annotation.Nullable;

/**
 * Snapshot of a citizen's state while despawned (WORKING or SLEEPING).
 * Held in CitizenManager to respawn the entity when the schedule
 * transitions to a visible state.
 */
public record StoredCitizen(
        String name,
        Profession profession,
        int mood,
        @Nullable BlockPos workplace,
        @Nullable BlockPos home,
        @Nullable BlockPos bed,
        CitizenState storedState
) {}
