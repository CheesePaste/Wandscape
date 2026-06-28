package com.wsteam.wandscape.core.op;

import com.wsteam.wandscape.core.ecs.World;

import java.util.Map;
/**
 * Evaluates a named condition for {@link AtomicOp.IfConditionOp}.
 * Each evaluator is registered by name in {@link OpExecutorRegistry#registerCondition(String, ConditionEvaluator)}.
 */
@FunctionalInterface
public interface ConditionEvaluator {
    /** Evaluate the condition against current world/NPC state. */
    boolean evaluate(Map<String, String> params, World world, long npcId);
}
