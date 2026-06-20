package com.wsteam.wandscape.core.task;

import java.util.List;
import java.util.Map;

/**
 * One step in a {@link BlueprintDefinition}'s {@code steps} array.
 * Each variant corresponds to a JSON step type.
 *
 * <p>Steps are interpreted at runtime into zero or more {@link com.wsteam.wandscape.core.op.AtomicOp}s.
 */
public sealed interface StepNode {

    // ── AtomicOp-mapped steps ──

    /**
     * Place a block. JSON type: {@code "place"}.
     * → {@link com.wsteam.wandscape.core.op.AtomicOp.TransformOp#place}
     */
    record PlaceStep(ExprNode at, ExprNode block) implements StepNode {}

    /**
     * Remove/break a block. JSON type: {@code "remove"}.
     * → {@link com.wsteam.wandscape.core.op.AtomicOp.TransformOp#remove}
     */
    record RemoveStep(ExprNode at, ExprNode from) implements StepNode {}

    /**
     * Convert one block to another. JSON type: {@code "convert"}.
     * → {@link com.wsteam.wandscape.core.op.AtomicOp.TransformOp#convert}
     */
    record ConvertStep(ExprNode at, ExprNode from, ExprNode to) implements StepNode {}

    /**
     * Interact with a block (toggle/activate/open GUI). JSON type: {@code "block_interact"}.
     * → {@link com.wsteam.wandscape.core.op.AtomicOp.BlockInteractOp}
     */
    record BlockInteractStep(ExprNode at, String action) implements StepNode {}

    /**
     * Interact with an entity. JSON type: {@code "entity_interact"}.
     * → {@link com.wsteam.wandscape.core.op.AtomicOp.EntityInteractOp}
     */
    record EntityInteractStep(ExprNode target, ExprNode effect,
                               ExprNode strength, ExprNode duration) implements StepNode {}

    /**
     * Perform a ritual. JSON type: {@code "ritual"}.
     * → {@link com.wsteam.wandscape.core.op.AtomicOp.RitualOp}
     */
    record RitualStep(ExprNode ritual, ExprNode at, ExprNode channelTicks) implements StepNode {}

    /**
     * Request resources from colony warehouse. JSON type: {@code "request_resource"}.
     * → {@link com.wsteam.wandscape.core.op.AtomicOp.ResourceRequestOp}
     */
    record RequestResourceStep(ExprNode resource, ExprNode amount) implements StepNode {}

    /**
     * Emit a named event with data. JSON type: {@code "emit_event"}.
     * → {@link com.wsteam.wandscape.core.op.AtomicOp.EmitEventOp}
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
     * Log a message. JSON type: {@code "log"}.
     * Does not generate an AtomicOp — executed immediately at interpret time.
     */
    record LogStep(String level, ExprNode text) implements StepNode {}
}
