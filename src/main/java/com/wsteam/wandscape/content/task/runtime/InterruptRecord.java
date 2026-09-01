package com.wsteam.wandscape.content.task.runtime;

/** Records when an NPC interrupted a task (V2). */
public record InterruptRecord(long npcId, long timestamp, int atStepIndex) {}
