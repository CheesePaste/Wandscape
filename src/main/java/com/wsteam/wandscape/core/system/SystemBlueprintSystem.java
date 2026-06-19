package com.wsteam.wandscape.core.system;

import com.wsteam.wandscape.core.Log;
import com.wsteam.wandscape.core.ecs.System;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.op.OpExecutor;
import com.wsteam.wandscape.core.op.OpExecutorRegistry;
import com.wsteam.wandscape.core.op.OpResult;
import com.wsteam.wandscape.core.task.Blueprint;
import com.wsteam.wandscape.core.task.TaskSequence;

import java.util.*;

/**
 * Drives system blueprint steps via heartbeat.
 * Runs after ManaRegenSystem, before TaskSourcePoller.
 *
 * <p>Each system blueprint with steps has its execution tracked by a local
 * stepIndex (no NPC context). Pure ops batch continuously; side-effect ops
 * execute one per tick. WAITING is not supported for system blueprints
 * (there is no NPC to retry on).
 */
public class SystemBlueprintSystem implements System {

    private static final String TAG = "SysBlueprintExec";

    private final SystemBlueprintRegistry registry;
    private final Map<String, Integer> stepIndices = new HashMap<>();

    public SystemBlueprintSystem(SystemBlueprintRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void update(World world, float delta) {
        OpExecutorRegistry opRegistry = world.opExecutors;
        if (opRegistry == null) return;

        for (Blueprint bp : registry.all()) {
            if (bp.steps() == null) continue;

            // Compile fresh each tick (system blueprints may be dynamic)
            TaskSequence seq;
            try {
                seq = bp.steps().generate(Collections.emptyMap());
            } catch (Exception e) {
                Log.debug(TAG, "system blueprint '%s' failed to compile: %s", bp.id(), e.getMessage());
                continue;
            }
            if (seq == null || seq.size() == 0) continue;

            int stepIndex = stepIndices.getOrDefault(bp.id(), 0);
            if (seq.isComplete(stepIndex)) {
                stepIndices.remove(bp.id());
                continue;
            }

            // Batch pure ops, one side-effect op per tick
            while (!seq.isComplete(stepIndex)) {
                AtomicOp op = seq.get(stepIndex);
                boolean isPure = op instanceof AtomicOp.EmitEventOp
                        || op instanceof AtomicOp.IfConditionOp;

                if (isPure) {
                    @SuppressWarnings("unchecked")
                    OpExecutor<AtomicOp> executor =
                            (OpExecutor<AtomicOp>) (Object) opRegistry.get(op.getClass());
                    if (executor != null) {
                        executor.execute(op, world, 0); // npcId=0 for system
                    }
                    stepIndex++;
                    stepIndices.put(bp.id(), stepIndex);
                    // Continue batch
                } else {
                    // Side-effect op: execute once then yield
                    @SuppressWarnings("unchecked")
                    OpExecutor<AtomicOp> executor =
                            (OpExecutor<AtomicOp>) (Object) opRegistry.get(op.getClass());
                    if (executor != null) {
                        OpResult result = executor.execute(op, world, 0);
                        if (result == OpResult.DONE) {
                            stepIndex++;
                            stepIndices.put(bp.id(), stepIndex);
                        }
                        // WAITING: don't advance, retry next tick
                    }
                    break; // One side-effect per tick
                }
            }

            if (seq.isComplete(stepIndex)) {
                stepIndices.remove(bp.id());
                Log.debug(TAG, "system blueprint '%s' complete", bp.id());
            }
        }
    }

    /** Reset execution state for a specific blueprint (for testing). */
    public void reset(String blueprintId) {
        stepIndices.remove(blueprintId);
    }

    /** Reset all execution state. */
    public void resetAll() {
        stepIndices.clear();
    }
}
