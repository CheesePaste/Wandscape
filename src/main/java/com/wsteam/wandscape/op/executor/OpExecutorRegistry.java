package com.wsteam.wandscape.op.executor;

import com.wsteam.wandscape.op.api.AtomicOp;
import com.wsteam.wandscape.op.api.ConditionEvaluator;

import java.util.HashMap;
import java.util.Map;
/**
 * Registry mapping AtomicOp subclasses to their OpExecutor, and
 * condition names to their {@link ConditionEvaluator}.
 * TaskExecutionSystem uses this to dispatch ops polymorphically.
 */
public class OpExecutorRegistry {

    private final Map<Class<? extends AtomicOp>, OpExecutor<?>> executors = new HashMap<>();
    private final Map<String, ConditionEvaluator> conditions = new HashMap<>();

    /** Register an executor. Overwrites any previous executor for the same op type. */
    public <T extends AtomicOp> void register(OpExecutor<T> executor) {
        executors.put(executor.opType(), executor);
    }

    /** Get the executor for a specific AtomicOp variant, or null. */
    @SuppressWarnings("unchecked")
    public <T extends AtomicOp> OpExecutor<T> get(Class<T> opType) {
        return (OpExecutor<T>) executors.get(opType);
    }

    /** Register a condition evaluator by name. */
    public void registerCondition(String name, ConditionEvaluator evaluator) {
        conditions.put(name, evaluator);
    }

    /** Get the condition evaluator for the given name, or null. */
    public ConditionEvaluator getCondition(String name) {
        return conditions.get(name);
    }
}
