package com.wsteam.wandscape.shared.data;

import java.util.List;
import java.util.UUID;

public interface TaskData {
    UUID getTaskId();
    TaskStatus getStatus();
    int getPriority();
    BehaviorType getRequiredBehavior();
    int getRequiredLevel();
    List<AtomicStep> getSteps();
    int getCurrentStepIndex();
    UUID getAssignedNpcId();
    UUID getOwnerBuildingId();
    List<InterruptRecord> getInterruptHistory();
}
