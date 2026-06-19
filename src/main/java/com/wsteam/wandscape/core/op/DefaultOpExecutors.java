package com.wsteam.wandscape.core.op;

import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.Log;
import com.wsteam.wandscape.core.TemplateResolver;
import com.wsteam.wandscape.core.boundary.BlockOps;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.boundary.EntityOps;
import com.wsteam.wandscape.core.boundary.RitualOps;
import com.wsteam.wandscape.core.component.Inventory;
import com.wsteam.wandscape.core.component.Position;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.event.CustomEvent;
import com.wsteam.wandscape.core.types.BlockType;

import java.util.HashMap;
import java.util.Map;

/**
 * Default OpExecutor implementations that delegate to the boundary interfaces.
 * Registered during engine bootstrap.
 */
public final class DefaultOpExecutors {

    private DefaultOpExecutors() {}

    /** Register all default executors and condition evaluators into the given registry. */
    public static void registerAll(OpExecutorRegistry registry) {
        registry.register(new TransformExecutor());
        registry.register(new BlockInteractExecutor());
        registry.register(new EntityInteractExecutor());
        registry.register(new RitualExecutor());
        registry.register(new ResourceRequestExecutor());
        registry.register(new EmitEventExecutor());
        registry.register(new IfConditionExecutor());

        // Register built-in condition evaluators
        registry.registerCondition("resource_below", new ResourceBelowCondition());
        registry.registerCondition("inventory_has", new InventoryHasCondition());
        registry.registerCondition("inventory_full", new InventoryFullCondition());
    }

    // ---- Implementations ----

    private static final String TAG = "OpExec";

    static class TransformExecutor implements OpExecutor<AtomicOp.TransformOp> {
        @Override
        public Class<AtomicOp.TransformOp> opType() {
            return AtomicOp.TransformOp.class;
        }

        @Override
        public OpResult execute(AtomicOp.TransformOp op, World world, long npcId) {
            BlockOps blockOps = world.blockOps;
            if (blockOps == null) return OpResult.DONE;

            Log.debug(TAG, "TransformOp %s: %s → %s (NPC %d)", op.target(), op.from().id(), op.to().id(), npcId);

            if (!op.from().equals(BlockType.AIR) && !blockOps.isAir(op.target())) {
                Log.debug(TAG, "TransformOp block mismatch at %s: expected %s", op.target(), op.from().id());
            }

            blockOps.setBlock(op.target(), op.to());
            return OpResult.DONE;
        }
    }

    static class BlockInteractExecutor implements OpExecutor<AtomicOp.BlockInteractOp> {
        @Override
        public Class<AtomicOp.BlockInteractOp> opType() {
            return AtomicOp.BlockInteractOp.class;
        }

        @Override
        public OpResult execute(AtomicOp.BlockInteractOp op, World world, long npcId) {
            BlockOps blockOps = world.blockOps;
            if (blockOps == null) return OpResult.DONE;

            Log.debug(TAG, "BlockInteractOp %s: %s (NPC %d)", op.target(), op.action().id(), npcId);

            switch (op.action().id()) {
                case "toggle" -> blockOps.toggle(op.target());
                case "activate" -> blockOps.activate(op.target());
                case "open_gui" -> blockOps.openGui(op.target());
            }
            return OpResult.DONE;
        }
    }

    static class EntityInteractExecutor implements OpExecutor<AtomicOp.EntityInteractOp> {
        @Override
        public Class<AtomicOp.EntityInteractOp> opType() {
            return AtomicOp.EntityInteractOp.class;
        }

        @Override
        public OpResult execute(AtomicOp.EntityInteractOp op, World world, long npcId) {
            EntityOps entityOps = world.entityOps;
            if (entityOps == null) return OpResult.DONE;

            Log.debug(TAG, "EntityInteractOp %s: %s strength=%d duration=%d (NPC %d)",
                    op.target(), op.effect().id(), op.strength(), op.duration(), npcId);

            entityOps.applyEffect(op.target(), op.effect(), op.strength(), op.duration());
            return OpResult.DONE;
        }
    }

    static class RitualExecutor implements OpExecutor<AtomicOp.RitualOp> {
        @Override
        public Class<AtomicOp.RitualOp> opType() {
            return AtomicOp.RitualOp.class;
        }

        @Override
        public OpResult execute(AtomicOp.RitualOp op, World world, long npcId) {
            RitualOps ritualOps = world.ritualOps;
            if (ritualOps == null) return OpResult.DONE;

            Log.debug(TAG, "RitualOp %s: %s target=%s (NPC %d)", op.ritual().id(), op.ritual().id(), op.target(), npcId);

            ritualOps.beginRitual(op.ritual(), op.target(), world, npcId);
            return ritualOps.pollRitual(op.ritual(), op.target(), world, npcId);
        }
    }

    /** Fallback: ResourceRequestOp is handled inline by TaskExecutionSystem. */
    static class ResourceRequestExecutor implements OpExecutor<AtomicOp.ResourceRequestOp> {
        @Override
        public Class<AtomicOp.ResourceRequestOp> opType() {
            return AtomicOp.ResourceRequestOp.class;
        }

        @Override
        public OpResult execute(AtomicOp.ResourceRequestOp op, World world, long npcId) {
            return OpResult.WAITING;
        }
    }

    // ---- Pure op executors ----

    /** Executes EmitEventOp: resolves templates, queues a CustomEvent. */
    static class EmitEventExecutor implements OpExecutor<AtomicOp.EmitEventOp> {
        @Override
        public Class<AtomicOp.EmitEventOp> opType() {
            return AtomicOp.EmitEventOp.class;
        }

        @Override
        public OpResult execute(AtomicOp.EmitEventOp op, World world, long npcId) {
            Map<String, String> vars = buildVarMap(world, npcId);
            Map<String, String> resolved = TemplateResolver.resolveMap(op.templateParams(), vars);

            world.eventBus.emit(new CustomEvent(op.eventName(), resolved));
            Log.debug(TAG, "EmitEventOp %s params=%s (NPC %d)", op.eventName(), resolved, npcId);

            // Self-advance (pure op contract)
            advanceAfterPureOp(world, npcId, 1);
            return OpResult.DONE;
        }

        private Map<String, String> buildVarMap(World world, long npcId) {
            Map<String, String> vars = new HashMap<>();
            TaskExecutor exec = world.get(npcId, TaskExecutor.class);
            if (exec != null) {
                vars.put("taskId", exec.globalTaskId != null ? String.valueOf(exec.globalTaskId) : "0");
                vars.put("npcId", String.valueOf(npcId));
                if (exec.taskParams != null) {
                    for (var entry : exec.taskParams.entrySet()) {
                        vars.put("task.params." + entry.getKey(), entry.getValue());
                    }
                }
            }
            Position pos = world.get(npcId, Position.class);
            if (pos != null) {
                vars.put("pos.x", String.valueOf(pos.pos().x()));
                vars.put("pos.y", String.valueOf(pos.pos().y()));
                vars.put("pos.z", String.valueOf(pos.pos().z()));
            }
            return vars;
        }
    }

    /** Executes IfConditionOp: evaluates condition, advances stepIndex accordingly. */
    static class IfConditionExecutor implements OpExecutor<AtomicOp.IfConditionOp> {
        @Override
        public Class<AtomicOp.IfConditionOp> opType() {
            return AtomicOp.IfConditionOp.class;
        }

        @Override
        public OpResult execute(AtomicOp.IfConditionOp op, World world, long npcId) {
            ConditionEvaluator evaluator = world.opExecutors.getCondition(op.conditionName());
            boolean conditionTrue;
            if (evaluator != null) {
                conditionTrue = evaluator.evaluate(op.params(), world, npcId);
            } else {
                Log.debug(TAG, "IfConditionOp unknown condition '%s' — defaulting to false",
                        op.conditionName());
                conditionTrue = false;
            }

            boolean shouldSkip = op.elseSkip() ? !conditionTrue : conditionTrue;
            int advance = shouldSkip ? op.skipCount() + 1 : 1;

            Log.debug(TAG, "IfConditionOp %s → %s (skip=%s advance=%d) (NPC %d)",
                    op.conditionName(), conditionTrue, shouldSkip, advance, npcId);

            // Self-advance (pure op contract)
            advanceAfterPureOp(world, npcId, advance);
            return OpResult.DONE;
        }
    }

    /** Advance the NPC's current task by N steps. Handles both private queue and global sequence. */
    private static void advanceAfterPureOp(World world, long npcId, int advance) {
        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        if (exec == null) return;

        if (!exec.isPrivateQueueEmpty()) {
            // Private queue: pop current + (advance-1) more
            exec.popPrivate();
            for (int i = 1; i < advance && !exec.isPrivateQueueEmpty(); i++) {
                exec.popPrivate();
            }
        } else if (exec.globalTaskId != null) {
            // Global sequence: advance stepIndex (keep task pool in sync)
            int nextStep = exec.stepIndex + advance;
            exec.stepIndex = nextStep;
            if (world.taskPool != null) {
                world.taskPool.advanceStep(exec.globalTaskId, nextStep);
            }
            Log.debug(TAG, "advanceAfterPureOp global #%d → step %d/%d",
                    exec.globalTaskId, nextStep,
                    exec.currentSequence != null ? exec.currentSequence.size() : -1);
        }
    }

    // ---- Built-in condition evaluators ----

    static class ResourceBelowCondition implements ConditionEvaluator {
        @Override
        public boolean evaluate(Map<String, String> params, World world, long npcId) {
            ColonyResourceAccess resources = world.colonyResources;
            if (resources == null) return false;

            String resourceName = params.get("resource");
            String thresholdStr = params.get("threshold");
            if (resourceName == null || thresholdStr == null) return false;

            int threshold;
            try {
                threshold = Integer.parseInt(thresholdStr);
            } catch (NumberFormatException e) {
                return false;
            }

            int available = resources.available(new ResourceId(resourceName));
            return available < threshold;
        }
    }

    static class InventoryHasCondition implements ConditionEvaluator {
        @Override
        public boolean evaluate(Map<String, String> params, World world, long npcId) {
            Inventory inv = world.get(npcId, Inventory.class);
            if (inv == null) return false;

            String resourceName = params.get("resource");
            String amountStr = params.get("amount");
            if (resourceName == null) return false;

            int amount = 1;
            if (amountStr != null) {
                try {
                    amount = Integer.parseInt(amountStr);
                } catch (NumberFormatException e) {
                    return false;
                }
            }

            return inv.count(new ResourceId(resourceName)) >= amount;
        }
    }

    static class InventoryFullCondition implements ConditionEvaluator {
        @Override
        public boolean evaluate(Map<String, String> params, World world, long npcId) {
            Inventory inv = world.get(npcId, Inventory.class);
            return inv != null && inv.isFull();
        }
    }
}
