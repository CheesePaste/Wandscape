package com.wsteam.wandscape.content.task.runtime;
import com.wsteam.wandscape.content.task.component.Position;

import com.wsteam.wandscape.content.task.types.GridPos;
import com.wsteam.wandscape.content.task.op.api.AtomicOp;

import javax.annotation.Nullable;
/**
 * A self-contained unit of work for an NPC.
 * Carries its own execution position (stance) so the NPC can navigate
 * between packages correctly — e.g. resume a building task at its original
 * location after an emergency task pulled the NPC elsewhere.
 */
public record NpcTaskPackage(
        String source,
        TaskSequence sequence,
        @Nullable GridPos stance,
        int priority,
        int startStepIndex
) {

    /** Create a new package starting from step 0. */
    public static NpcTaskPackage of(String source, TaskSequence sequence,
                                    @Nullable GridPos stance, int priority) {
        return new NpcTaskPackage(source, sequence, stance, priority, 0);
    }

    /** Create a package that resumes execution from a saved stepIndex. */
    public static NpcTaskPackage resumeFrom(String source, TaskSequence sequence,
                                            @Nullable GridPos stance, int priority,
                                            int startStepIndex) {
        return new NpcTaskPackage(source, sequence, stance, priority, startStepIndex);
    }

    /** Create a single-op system package (wand equip, teleport, etc.). */
    public static NpcTaskPackage system(String source, AtomicOp op,
                                        @Nullable GridPos stance, int priority) {
        var seq = TaskSequence.of(source, op);
        return new NpcTaskPackage(source, seq, stance, priority, 0);
    }

    public int size() {
        return sequence.size();
    }

    public boolean isComplete(int stepIndex) {
        return sequence.isComplete(stepIndex);
    }

    public AtomicOp get(int stepIndex) {
        return sequence.get(stepIndex);
    }
}
