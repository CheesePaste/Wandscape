package com.wsteam.wandscape.core.op;

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
import com.wsteam.wandscape.core.types.ResourceId;

import com.google.gson.JsonElement;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Default OpExecutor implementations — all synchronous (return completed futures).
 * Async executors (e.g. AsyncTransformExecutor) replace these at bootstrap.
 */
public final class DefaultOpExecutors {

    private DefaultOpExecutors() {}

    /** Register all default executors and condition evaluators. */
    public static void registerAll(OpExecutorRegistry registry) {
        registry.register(new TransformExecutor());
        registry.register(new BlockInteractExecutor());
        registry.register(new EntityInteractExecutor());
        registry.register(new RitualExecutor());
        registry.register(new ResourceRequestExecutor());
        registry.register(new EmitEventExecutor());
        registry.register(new IfConditionExecutor());

        registry.registerCondition("resource_below", new ResourceBelowCondition());
        registry.registerCondition("inventory_has", new InventoryHasCondition());
        registry.registerCondition("inventory_full", new InventoryFullCondition());
    }

    // ================================================================
    // Sync executors — return CompletableFuture.completedFuture(null)
    // ================================================================

    static class TransformExecutor implements OpExecutor<AtomicOp.TransformOp> {
        @Override public Class<AtomicOp.TransformOp> opType() { return AtomicOp.TransformOp.class; }

        @Override
        public CompletableFuture<Void> execute(AtomicOp.TransformOp op, World world, long npcId) {
            BlockOps blockOps = world.blockOps;
            if (blockOps != null) {
                blockOps.setBlock(op.target(), op.to());
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    static class BlockInteractExecutor implements OpExecutor<AtomicOp.BlockInteractOp> {
        @Override public Class<AtomicOp.BlockInteractOp> opType() { return AtomicOp.BlockInteractOp.class; }

        @Override
        public CompletableFuture<Void> execute(AtomicOp.BlockInteractOp op, World world, long npcId) {
            BlockOps blockOps = world.blockOps;
            if (blockOps != null) {
                switch (op.action().id()) {
                    case "toggle"   -> blockOps.toggle(op.target());
                    case "activate" -> blockOps.activate(op.target());
                    case "open_gui" -> blockOps.openGui(op.target());
                }
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    static class EntityInteractExecutor implements OpExecutor<AtomicOp.EntityInteractOp> {
        @Override public Class<AtomicOp.EntityInteractOp> opType() { return AtomicOp.EntityInteractOp.class; }

        @Override
        public CompletableFuture<Void> execute(AtomicOp.EntityInteractOp op, World world, long npcId) {
            EntityOps entityOps = world.entityOps;
            if (entityOps != null) {
                entityOps.applyEffect(op.entityId(), op.effect(), op.strength(), op.duration());
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    static class RitualExecutor implements OpExecutor<AtomicOp.RitualOp> {
        @Override public Class<AtomicOp.RitualOp> opType() { return AtomicOp.RitualOp.class; }

        @Override
        public CompletableFuture<Void> execute(AtomicOp.RitualOp op, World world, long npcId) {
            RitualOps ritualOps = world.ritualOps;
            if (ritualOps == null) return CompletableFuture.completedFuture(null);
            return ritualOps.beginRitual(op.ritual(), op.target(), world, npcId);
        }
    }

    /** Fallback: ResourceRequestOp is handled inline by TaskExecutionSystem. */
    static class ResourceRequestExecutor implements OpExecutor<AtomicOp.ResourceRequestOp> {
        @Override public Class<AtomicOp.ResourceRequestOp> opType() { return AtomicOp.ResourceRequestOp.class; }

        @Override
        public CompletableFuture<Void> execute(AtomicOp.ResourceRequestOp op, World world, long npcId) {
            return new CompletableFuture<>(); // never completes — handled inline
        }
    }

    // ================================================================
    // Pure op executors (self-advancing)
    // ================================================================

    static class EmitEventExecutor implements OpExecutor<AtomicOp.EmitEventOp> {
        @Override public Class<AtomicOp.EmitEventOp> opType() { return AtomicOp.EmitEventOp.class; }

        @Override
        public CompletableFuture<Void> execute(AtomicOp.EmitEventOp op, World world, long npcId) {
            Map<String, String> vars = buildVarMap(world, npcId);
            Map<String, String> resolved = TemplateResolver.resolveMap(op.templateParams(), vars);
            world.eventBus.emit(new CustomEvent(op.eventName(), resolved));
            advanceAfterPureOp(world, npcId, 1);
            return CompletableFuture.completedFuture(null);
        }

        private Map<String, String> buildVarMap(World world, long npcId) {
            Map<String, String> vars = new HashMap<>();
            TaskExecutor exec = world.get(npcId, TaskExecutor.class);
            if (exec != null) {
                vars.put("taskId", exec.globalTaskId != null ? String.valueOf(exec.globalTaskId) : "0");
                vars.put("npcId", String.valueOf(npcId));
                if (exec.taskParams != null) {
                    for (var entry : exec.taskParams.entrySet()) {
                        vars.put("task.params." + entry.getKey(),
                                jsonElementToString(entry.getValue()));
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

        /** Convert a JsonElement to a string suitable for template resolution. */
        private static String jsonElementToString(JsonElement el) {
            if (el.isJsonPrimitive()) return el.getAsString();
            return el.toString(); // JsonArray or JsonObject → its JSON representation
        }
    }

    static class IfConditionExecutor implements OpExecutor<AtomicOp.IfConditionOp> {
        @Override public Class<AtomicOp.IfConditionOp> opType() { return AtomicOp.IfConditionOp.class; }

        @Override
        public CompletableFuture<Void> execute(AtomicOp.IfConditionOp op, World world, long npcId) {
            ConditionEvaluator evaluator = world.opExecutors.getCondition(op.conditionName());
            boolean conditionTrue = evaluator != null && evaluator.evaluate(op.params(), world, npcId);
            boolean shouldSkip = op.elseSkip() ? !conditionTrue : conditionTrue;
            int advance = shouldSkip ? op.skipCount() + 1 : 1;
            advanceAfterPureOp(world, npcId, advance);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static void advanceAfterPureOp(World world, long npcId, int advance) {
        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        if (exec == null) return;
        if (!exec.isPrivateQueueEmpty()) {
            exec.popPrivate();
            for (int i = 1; i < advance && !exec.isPrivateQueueEmpty(); i++) exec.popPrivate();
        } else if (exec.globalTaskId != null) {
            exec.stepIndex += advance;
        }
    }

    // ================================================================
    // Built-in condition evaluators
    // ================================================================

    static class ResourceBelowCondition implements ConditionEvaluator {
        @Override public boolean evaluate(Map<String, String> params, World world, long npcId) {
            ColonyResourceAccess resources = world.colonyResources;
            if (resources == null) return false;
            String resourceName = params.get("resource");
            String thresholdStr = params.get("threshold");
            if (resourceName == null || thresholdStr == null) return false;
            try { return resources.available(new ResourceId(resourceName)) < Integer.parseInt(thresholdStr); }
            catch (NumberFormatException e) { return false; }
        }
    }

    static class InventoryHasCondition implements ConditionEvaluator {
        @Override public boolean evaluate(Map<String, String> params, World world, long npcId) {
            Inventory inv = world.get(npcId, Inventory.class);
            if (inv == null) return false;
            String resourceName = params.get("resource");
            if (resourceName == null) return false;
            int amount = 1;
            try { amount = params.get("amount") != null ? Integer.parseInt(params.get("amount")) : 1; }
            catch (NumberFormatException e) { return false; }
            return inv.count(new ResourceId(resourceName)) >= amount;
        }
    }

    static class InventoryFullCondition implements ConditionEvaluator {
        @Override public boolean evaluate(Map<String, String> params, World world, long npcId) {
            Inventory inv = world.get(npcId, Inventory.class);
            return inv != null && inv.isFull();
        }
    }
}
