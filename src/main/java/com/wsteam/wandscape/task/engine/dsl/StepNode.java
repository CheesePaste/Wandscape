package com.wsteam.wandscape.task.engine.dsl;

import com.wsteam.wandscape.op.api.AtomicOp;

import java.util.List;
import java.util.Map;
/**
 * One step in a {@link BlueprintDefinition}'s {@code steps} array.
 * Each variant corresponds to a JSON step type.
 *
 * <p>Steps are interpreted at runtime into zero or more {@link AtomicOp}s.
 */
public sealed interface StepNode {

    // ── AtomicOp-mapped steps ──

    /**
     * Place a block. JSON type: {@code "place"}.
     * → {@link AtomicOp.TransformOp#place}
     *
     * @param consumable optional expression evaluating to the resource to consume
     *                   from NPC inventory (e.g. "minecraft:stone_bricks").
     *                   {@code null} means free placement (no consumption).
     * @param nbt        optional expression evaluating to a base64-encoded
     *                   compressed NBT string for BlockEntity data restoration.
     *                   {@code null} means no BlockEntity data.
     */
    record PlaceStep(ExprNode at, ExprNode block,
                     @javax.annotation.Nullable ExprNode consumable,
                     @javax.annotation.Nullable ExprNode nbt) implements StepNode {
    }

    /**
     * Remove/break a block. JSON type: {@code "remove"}.
     * → {@link AtomicOp.TransformOp#remove}
     */
    record RemoveStep(ExprNode at, ExprNode from) implements StepNode {}

    /**
     * Convert one block to another. JSON type: {@code "convert"}.
     * → {@link AtomicOp.TransformOp#convert}
     */
    record ConvertStep(ExprNode at, ExprNode from, ExprNode to) implements StepNode {}

    /**
     * Interact with a block. JSON type: {@code "block_interact"}.
     * → {@link AtomicOp.BlockInteractOp}
     *
     * @param action       the interaction type (toggle/activate/open_gui/gather/decompose/synthesize)
     * @param params       action-specific key-value pairs (e.g. element, amount)
     * @param channelTicks channeling duration in ticks (0 = instant for sync actions)
     * @param manaCost     mana consumed by this interaction (configurable, unlike RitualOp)
     */
    record BlockInteractStep(ExprNode at, String action,
                             Map<String, ExprNode> params,
                             ExprNode channelTicks, ExprNode manaCost) implements StepNode {
        public BlockInteractStep {
            if (params == null) params = Map.of();
        }

        /** Backward-compat constructor for sync actions without params/channelTicks/manaCost. */
        public BlockInteractStep(ExprNode at, String action) {
            this(at, action, Map.of(), new ExprNode.LiteralInt(0), new ExprNode.LiteralInt(1));
        }
    }

    /**
     * Interact with an entity. JSON type: {@code "entity_interact"}.
     * → {@link AtomicOp.EntityInteractOp}
     */
    record EntityInteractStep(ExprNode target, ExprNode effect,
                               ExprNode strength, ExprNode duration) implements StepNode {}

    /**
     * Perform a ritual. JSON type: {@code "ritual"}.
     * → {@link AtomicOp.RitualOp}
     * Channel ticks and mana cost are hardcoded in RitualOp per ritual type.
     */
    record RitualStep(ExprNode ritual, ExprNode at,
                      Map<String, ExprNode> params) implements StepNode {
        public RitualStep {
            if (params == null) params = Map.of();
        }

        /** Backward-compat constructor without params. */
        public RitualStep(ExprNode ritual, ExprNode at) {
            this(ritual, at, Map.of());
        }
    }

    /**
     * Request resources from colony warehouse. JSON type: {@code "request_resource"}.
     * → {@link AtomicOp.ResourceRequestOp}
     *
     * @param items        static resource+amount entries (may be empty if dynamicItems is set)
     * @param dynamicItems expression that evaluates to {@code [{resource, amount}, ...]} at interpret time;
     *                     mutually exclusive with non-empty items
     */
    record RequestResourceStep(List<ResourceEntry> items,
                               @javax.annotation.Nullable ExprNode dynamicItems) implements StepNode {
        public RequestResourceStep {
            if (items == null) items = List.of();
            if (items.isEmpty() && dynamicItems == null) {
                throw new IllegalArgumentException("RequestResourceStep must have items or dynamicItems");
            }
        }

        /** Static items convenience (no dynamic expression). */
        public RequestResourceStep(List<ResourceEntry> items) {
            this(items, null);
        }

        /** Backward-compat: single resource + amount. */
        public RequestResourceStep(ExprNode resource, ExprNode amount) {
            this(List.of(new ResourceEntry(resource, amount)), null);
        }

        /** A single resource+amount entry within a request_resource step. */
        public record ResourceEntry(ExprNode resource, ExprNode amount) {}
    }

    /**
     * Emit a named event with data. JSON type: {@code "emit_event"}.
     * → {@link AtomicOp.EmitEventOp}
     */
    record EmitEventStep(ExprNode event, Map<String, ExprNode> data) implements StepNode {}

    // ── Control-flow steps ──

    /**
     * Loop: evaluate {@code list}, bind each element to {@code var}, expand {@code steps}.
     * JSON type: {@code "for_each"}.
     */
    record ForEachStep(ExprNode list, String var, List<StepNode> steps) implements StepNode {}

    /**
     * Condition: evaluate {@code condition}, expand {@code thenSteps} or {@code elseSteps}.
     * JSON type: {@code "if"}.
     *
     * @param condition  expression evaluating to the condition name (string)
     * @param params     key-value expression pairs passed to the condition evaluator
     * @param elseInvert if true, swap then/else semantics
     */
    record IfStep(ExprNode condition, Map<String, ExprNode> params, boolean elseInvert,
                  List<StepNode> thenSteps, List<StepNode> elseSteps) implements StepNode {}

    /**
     * Macro-expand another blueprint's steps inline. JSON type: {@code "call"}.
     * The referenced blueprint is loaded from the global registry at interpret time.
     */
    record CallStep(ExprNode blueprintId, Map<String, ExprNode> with) implements StepNode {}

    /**
     * Execute multiple steps in parallel. JSON type: {@code "parallel"}.
     * Expands each child step, wraps all resulting AtomicOps in a single ParallelOp.
     * All sub-ops launch concurrently; the engine waits for all to complete.
     *
     * <p>Use for concurrent resource requests: request stone + wood + iron in one tick
     * rather than serially, so all ItemEntity transports fly simultaneously.
     */
    record ParallelStep(List<StepNode> steps) implements StepNode {}

    /**
     * Log a message. JSON type: {@code "log"}.
     * Does not generate an AtomicOp — executed immediately at interpret time.
     */
    record LogStep(String level, ExprNode text) implements StepNode {}
}
