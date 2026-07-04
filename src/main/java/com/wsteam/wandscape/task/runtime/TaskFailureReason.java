package com.wsteam.wandscape.task.runtime;

/**
 * Encodes why a task entered {@link TaskState#FAILED}.
 * Extensible via sealed interface — add new permitted subtypes for new failure modes.
 */
public interface TaskFailureReason {
    // Future failure reason records go here
}
