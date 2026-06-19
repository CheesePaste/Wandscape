package com.wsteam.wandscape.shared.data;

import java.util.List;

public record TaskTemplate(
    BehaviorType requiredBehavior,
    int requiredLevel,
    List<AtomicStep> steps,
    int priority
) {}
