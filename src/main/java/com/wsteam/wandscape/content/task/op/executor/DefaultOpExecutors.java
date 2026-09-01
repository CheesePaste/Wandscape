package com.wsteam.wandscape.content.task.op.executor;
import com.wsteam.wandscape.content.task.boundary.ResourceRequestExecutor;
import com.wsteam.wandscape.content.task.boundary.WandscapeBlockInteractExecutor;
import com.wsteam.wandscape.content.warehouse.transport.ItemTransportManager;
import com.wsteam.wandscape.content.task.boundary.AsyncTransformExecutor;
import com.wsteam.wandscape.content.task.boundary.EventBus;
import com.wsteam.wandscape.content.task.types.EntityId;

import com.google.gson.JsonElement;
import com.wsteam.wandscape.impl.TemplateResolver;
import com.wsteam.wandscape.content.task.boundary.BlockOps;
import com.wsteam.wandscape.content.task.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.content.task.boundary.EntityOps;
import com.wsteam.wandscape.content.task.boundary.RitualOps;
import com.wsteam.wandscape.content.task.component.NpcInventory;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.component.TaskExecutor;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.event.CustomEvent;
import com.wsteam.wandscape.content.task.types.ResourceId;
import com.wsteam.wandscape.content.task.types.ResourceStack;
import com.wsteam.wandscape.content.task.op.api.AtomicOp;
import com.wsteam.wandscape.content.task.op.api.ConditionEvaluator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
        registry.register(new SpawnDecorationExecutor());
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
            // Consumable check: remove from NPC inventory before placing
            if (op.consumable() != null) {
                NpcInventory inv = world.get(npcId, NpcInventory.class);
                if (inv == null || !inv.hasEnough(op.consumable().resource(),
                        op.consumable().amount())) {
                    return CompletableFuture.failedFuture(
                            new ResourceShortageException(List.of(op.consumable())));
                }
                inv.remove(op.consumable().resource(), op.consumable().amount());
            }
            BlockOps blockOps = world.blockOps;
            if (blockOps != null) {
                blockOps.setBlock(op.target(), op.to());
                blockOps.setBlockEntityData(op.target(), op.blockNbtBase64());
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Spawn a decoration entity (item frame / painting) from trimmed NBT.
     * Sync — runs on the server thread at op execution.
     */
    static class SpawnDecorationExecutor implements OpExecutor<AtomicOp.SpawnDecorationOp> {
        @Override public Class<AtomicOp.SpawnDecorationOp> opType() { return AtomicOp.SpawnDecorationOp.class; }

        @Override
        public CompletableFuture<Void> execute(AtomicOp.SpawnDecorationOp op, World world, long npcId) {
            EntityOps entityOps = world.entityOps;
            if (entityOps != null) {
                entityOps.spawnDecoration(op.target(), op.entityType(), op.facing(), op.nbtBase64());
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Default BlockInteractExecutor — sync-only fallback for toggle/activate/open_gui.
     * Replaced by {@code WandscapeBlockInteractExecutor} at bootstrap
     * which also handles async actions (gather/decompose/synthesize).
     */
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
                    default -> { /* async actions handled by WandscapeBlockInteractExecutor */ }
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
            return ritualOps.beginRitual(op.ritual(), op.target(), world, npcId,
                    op.params());
        }
    }

    /**
     * Default ResourceRequestExecutor — sync, no visual transport.
     * Replaced by engine-layer {@code ResourceRequestExecutor} which adds
     * visual item flight via {@code ItemTransportManager}.
     *
     * <p>All-or-nothing: computes shortfall for every item first,
     * then checks warehouse for each. If any item is short, fails the
     * entire request without reserving or deducting anything — no partial
     * fulfillment. On success, deducts all reserved materials in one batch
     * (construction-start charge); items never enter the NPC inventory.
     */
    static class ResourceRequestExecutor implements OpExecutor<AtomicOp.ResourceRequestOp> {
        @Override public Class<AtomicOp.ResourceRequestOp> opType() { return AtomicOp.ResourceRequestOp.class; }

        @Override
        public CompletableFuture<Void> execute(AtomicOp.ResourceRequestOp op, World world, long npcId) {
            ColonyResourceAccess resources = world.colonyResources;
            NpcInventory inv = world.get(npcId, NpcInventory.class);
            List<ResourceStack> items = op.items();

            // ── Phase 1: compute shortfalls (NPC inventory offsets) ──
            List<ResourceStack> needs = new ArrayList<>();
            for (ResourceStack item : items) {
                int alreadyHas = inv != null ? inv.count(item.resource()) : 0;
                int shortfall = item.amount() - alreadyHas;
                if (shortfall > 0) {
                    needs.add(item.withAmount(shortfall));
                }
            }

            if (needs.isEmpty()) {
                // Everything already in NPC inventory
                return CompletableFuture.completedFuture(null);
            }

            // ── Phase 2: check ALL warehouse stock before reserving any ──
            for (ResourceStack need : needs) {
                if (!resources.hasEnough(need.resource(), need.amount())) {
                    return CompletableFuture.failedFuture(
                            new ResourceShortageException(items));
                }
            }

            // ── Phase 3: reserve ALL ──
            for (ResourceStack need : needs) {
                if (!resources.reserve(need.resource(), need.amount())) {
                    // Roll back previously reserved items
                    for (int j = 0; j < needs.indexOf(need); j++) {
                        resources.release(needs.get(j).resource(), needs.get(j).amount());
                    }
                    return CompletableFuture.failedFuture(
                            new ResourceShortageException(items));
                }
            }

            // ── Phase 4: deduct ALL (construction start, bulk) ──
            // Materials never enter the NPC backpack — a full inventory can't
            // block the charge.
            for (ResourceStack need : needs) {
                if (!resources.commit(need.resource(), need.amount())) {
                    resources.release(need.resource(), need.amount());
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Resource commit failed for " + need));
                }
            }
            return CompletableFuture.completedFuture(null);
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
            boolean shouldSkip = op.elseSkip() != conditionTrue;
            int advance = shouldSkip ? op.skipCount() + 1 : 1;
            advanceAfterPureOp(world, npcId, advance);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static void advanceAfterPureOp(World world, long npcId, int advance) {
        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        if (exec == null) return;
        var queue = exec.npcQueue;
        if (queue.currentPackage() != null) {
            for (int i = 0; i < advance; i++) {
                queue.advanceStep();
                if (queue.isCurrentPackageDone()) break;
            }
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
            NpcInventory inv = world.get(npcId, NpcInventory.class);
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
            NpcInventory inv = world.get(npcId, NpcInventory.class);
            return inv != null && inv.isFull();
        }
    }
}
