package com.wsteam.wandscape.core.task;

import com.wsteam.wandscape.core.types.BehaviourLevel;
import com.wsteam.wandscape.core.types.BehaviourTag;

import java.util.Map;

/**
 * Encodes why a task entered {@link TaskState#FAILED}.
 * Extensible via sealed interface — add new permitted subtypes for new failure modes.
 */
public sealed interface TaskFailureReason
        permits TaskFailureReason.WandRequirementUnmet,
                TaskFailureReason.ColonyEvaluationTooLow {

    /** No NPC satisfies the wand capability requirements and no matching wand in warehouse. */
    record WandRequirementUnmet(Map<BehaviourTag, BehaviourLevel> requirements)
            implements TaskFailureReason {
        public WandRequirementUnmet {
            requirements = Map.copyOf(requirements);
        }
    }

    /**
     * The colony's Comfort/Magic/Wonder values are insufficient to unlock the
     * wand preset needed to recover the failed task. Analysis should stop — no
     * point retrying until the colony's evaluation improves.
     *
     * @param presetId          the wand preset that couldn't be crafted
     * @param requiredComfort   minimum comfort needed
     * @param requiredMagic     minimum magic needed
     * @param requiredWonder    minimum wonder needed
     * @param currentComfort    current colony comfort
     * @param currentMagic      current colony magic
     * @param currentWonder     current colony wonder
     */
    record ColonyEvaluationTooLow(
            String presetId,
            int requiredComfort, int requiredMagic, int requiredWonder,
            int currentComfort, int currentMagic, int currentWonder
    ) implements TaskFailureReason {}
}
