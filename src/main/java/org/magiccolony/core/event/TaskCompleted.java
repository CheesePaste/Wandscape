package org.magiccolony.core.event;

/** Emitted when a global task completes successfully. */
public record TaskCompleted(long taskId, long completedByNpcId) {
    @Override public String toString() { return "TaskCompleted[#" + taskId + " by NPC " + completedByNpcId + "]"; }
}
