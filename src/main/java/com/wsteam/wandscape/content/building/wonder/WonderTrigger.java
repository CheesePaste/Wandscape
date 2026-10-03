package com.wsteam.wandscape.content.building.wonder;

/**
 * Functional interface / strategy for wonder completion triggers.
 *
 * <p>Implemented to execute special features, grant custom perks, unlock mechanics,
 * or broadcast milestones when a wonder building completes construction.
 */
@FunctionalInterface
public interface WonderTrigger {

    /**
     * Executes the special effect or feature when the wonder completes.
     *
     * @param context contextual information about the completed wonder
     */
    void onComplete(WonderTriggerContext context);

    /**
     * Predicate checking if this trigger should handle the given completed wonder.
     * Default implementation returns true.
     *
     * @param context contextual information about the completed wonder
     * @return true if this trigger should execute for the context
     */
    default boolean matches(WonderTriggerContext context) {
        return true;
    }
}
