package com.wsteam.wandscape.content.task;

import com.wsteam.wandscape.content.task.runtime.NpcTaskPackage;
/**
 * Snapshot of a suspended task package, stored on the NPC's suspension stack.
 * When the interruption is resolved, the NPC resumes from this point
 * and navigates back to the package's stance.
 */
public record SuspensionContext(
        NpcTaskPackage pkg,
        int stepIndex,
        long suspendedAtTick
) {}
