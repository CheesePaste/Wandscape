package org.magiccolony.core.task;

import java.util.Map;

/**
 * Functional interface that generates a {@link TaskSequence} from task parameters.
 * This is the "generator" half of a {@link Blueprint} — the other half is the
 * list of {@link TriggerDeclaration}s produced at compile time.
 *
 * <p>Location (when needed) is carried in params as {@code x}/{@code y}/{@code z} keys;
 * the blueprint is responsible for parsing them.
 */
@FunctionalInterface
public interface BlueprintSteps {
    TaskSequence generate(Map<String, String> params);
}
