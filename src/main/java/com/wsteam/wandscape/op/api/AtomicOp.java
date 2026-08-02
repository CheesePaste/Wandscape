package com.wsteam.wandscape.op.api;

import com.wsteam.wandscape.core.types.*;
import com.wsteam.wandscape.op.executor.ResourceShortageException;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Sealed hierarchy of atomic operations that NPCs can perform.
 * Each variant carries the data needed by its corresponding OpExecutor.
 */
public sealed interface AtomicOp
        permits AtomicOp.TransformOp,
                AtomicOp.BlockInteractOp,
                AtomicOp.EntityInteractOp,
                AtomicOp.RitualOp,
                AtomicOp.ResourceRequestOp,
                AtomicOp.EmitEventOp,
                AtomicOp.IfConditionOp,
                AtomicOp.ParallelOp,
                AtomicOp.AttackMonsterOp {

    /** Base mana cost for this operation (before wand efficiency). */
    float baseManaCost();

    /**
     * The world position this operation acts on, or {@code null} if positionless
     * (e.g. event emission, conditional branching, entity targeting by ID).
     */
    @Nullable
    GridPos target();

    // ---- Variants ----

    /** Place / break / convert a block. */
    record TransformOp(
            GridPos target,
            BlockType from,
            BlockType to,
            boolean consumeSource,
            List<ResourceStack> drops,
            ResourceStack consumable,
            @Nullable String blockNbtBase64
    ) implements AtomicOp {
        public TransformOp {
            if (drops == null) drops = Collections.emptyList();
        }

        // Convenience constructors
        public static TransformOp place(GridPos target, BlockType to) {
            return new TransformOp(target, BlockType.AIR, to, false, Collections.emptyList(), null, null);
        }

        public static TransformOp place(GridPos target, BlockType to, ResourceStack consumable) {
            return new TransformOp(target, BlockType.AIR, to, false, Collections.emptyList(), consumable, null);
        }

        public static TransformOp place(GridPos target, BlockType to, @Nullable String blockNbtBase64) {
            return new TransformOp(target, BlockType.AIR, to, false, Collections.emptyList(), null, blockNbtBase64);
        }

        public static TransformOp place(GridPos target, BlockType to, ResourceStack consumable, @Nullable String blockNbtBase64) {
            return new TransformOp(target, BlockType.AIR, to, false, Collections.emptyList(), consumable, blockNbtBase64);
        }

        public static TransformOp remove(GridPos target, BlockType from, List<ResourceStack> drops) {
            return new TransformOp(target, from, BlockType.AIR, true, drops, null, null);
        }

        public static TransformOp convert(GridPos target, BlockType from, BlockType to) {
            return new TransformOp(target, from, to, true, Collections.emptyList(), null, null);
        }

        @Override
        public float baseManaCost() {
            return 0.2f;
        }

        @Override
        public GridPos target() {
            return target;
        }
    }

    /**
     * Interact with a block — sync (toggle/activate/open_gui) or async (gather/decompose/synthesize).
     * Async actions use channelTicks for timing and params for action-specific data.
     * Mana cost and channelTicks are configurable from the blueprint (unlike RitualOp).
     */
    record BlockInteractOp(GridPos target, InteractAction action,
                           Map<String, String> params, int channelTicks,
                           float manaCost) implements AtomicOp {
        public BlockInteractOp {
            if (params == null) params = Collections.emptyMap();
        }

        @Override
        public float baseManaCost() {
            return manaCost;
        }

        @Override
        public GridPos target() {
            return target;
        }
    }

    /** Apply an effect to a non-NPC entity. */
    record EntityInteractOp(EntityId entityId, EffectId effect, int strength, int duration) implements AtomicOp {
        @Override
        public float baseManaCost() {
            return 1.0f;
        }

        @Override
        public GridPos target() {
            return null; // targets an entity by ID, not a grid position
        }
    }

    /**
     * Guard combat: cast a magic circle + beam at the nearest hostile within a defended
     * building zone. Positionless — the caster does NOT walk; the executor re-scans the
     * zone each cycle and casts at whatever it can see.
     *
     * @param attackRange  horizontal X/Z expansion where monsters are attacked (Y unchanged)
     * @param releaseRange horizontal X/Z expansion; the guard task completes only when no
     *                     monster remains inside it (hysteresis, >= attackRange)
     * @param circleId     magic circle spec id for the cast visual
     * @param color        beam color (ARGB)
     */
    record AttackMonsterOp(int attackRange, int releaseRange, String circleId, int color) implements AtomicOp {
        @Override
        public float baseManaCost() {
            return 0f; // guard casts are mana-free initially (M6 knob later)
        }

        @Override
        public GridPos target() {
            return null; // no stance / no navigation — cast from current position
        }
    }

    /** Perform a ritual (may involve channeling over multiple ticks). */
    record RitualOp(RitualId ritual, GridPos target,
                    Map<String, String> params) implements AtomicOp {
        public RitualOp {
            if (params == null) params = Collections.emptyMap();
        }

        @Override
        public float baseManaCost() {
            return switch (ritual.id()) {
                case "self_teleport", "item_teleport", "player_summon" -> 0;
                case "warding" -> 15f;
                case "group_vigor" -> 20f;
                case "rain_call", "clear_weather" -> 30f;
                case "portal_gate" -> 45f;
                default -> 15f;
            };
        }

        /** Channeling duration in ticks (0 = instant). Hardcoded per ritual type. */
        public int channelTicks() {
            return switch (ritual.id()) {
                case "self_teleport", "item_teleport", "player_summon" -> 600;
                case "warding" -> 200;
                case "group_vigor" -> 400;
                case "rain_call", "clear_weather" -> 1200;
                case "portal_gate" -> 1800;
                default -> 0;
            };
        }

        @Override
        public GridPos target() {
            return target;
        }
    }

    /**
     * Request resources from the colony warehouse. The executor resolves this inline.
     *
     * <p>All-or-nothing semantics: the executor checks every item against the warehouse
     * before sending any. If any item is short, the entire request fails with a
     * {@link ResourceShortageException} — no partial items leak into the NPC inventory.
     *
     * <p>Blueprint authors should group all needed resources into a single
     * {@code ResourceRequestOp} rather than using multiple ops or {@code ParallelOp},
     * to guarantee atomic fulfillment.
     *
     * @param items one or more resource stacks to request (must not be empty)
     */
    record ResourceRequestOp(List<ResourceStack> items) implements AtomicOp {
        public ResourceRequestOp {
            items = List.copyOf(items);
            if (items.isEmpty()) {
                throw new IllegalArgumentException("ResourceRequestOp items must not be empty");
            }
        }

        @Override
        public float baseManaCost() {
            return 0.0f; // Teleportation cost handled by the ritual inserted into private queue
        }

        @Override
        public GridPos target() {
            return null; // warehouse request, no world position
        }
    }

    /**
     * Emit a custom event during task execution.
     * Template params ({{variable}}) are resolved at execution time.
     * Pure op — event is only queued, dispatched at tick end.
     */
    record EmitEventOp(String eventName, Map<String, String> templateParams) implements AtomicOp {
        public EmitEventOp {
            if (templateParams == null) templateParams = Collections.emptyMap();
        }

        @Override
        public float baseManaCost() {
            return 0;
        }

        @Override
        public GridPos target() {
            return null; // pure event emission, no world position
        }
    }

    /**
     * Runtime conditional step-skipping.
     * Evaluates {@link #conditionName} against world state; if the condition
     * matches the polarity implied by {@link #elseSkip}, advances stepIndex
     * by {@link #skipCount}+1 instead of +1.
     *
     * <p>Pure op — only reads world state.
     *
     * @param conditionName name of the condition evaluator registered in OpExecutorRegistry
     * @param params        parameters passed to the evaluator
     * @param skipCount     how many steps to skip when the condition triggers
     * @param elseSkip      when true, condition=false triggers the skip (semantic inversion)
     */
    record IfConditionOp(String conditionName, Map<String, String> params,
                         int skipCount, boolean elseSkip) implements AtomicOp {
        public IfConditionOp {
            if (params == null) params = Collections.emptyMap();
        }

        @Override
        public float baseManaCost() {
            return 0;
        }

        @Override
        public GridPos target() {
            return null; // conditional logic, no world position
        }
    }

    /**
     * Execute multiple sub-ops concurrently and await all results.
     *
     * <p>The engine launches every sub-op in parallel (matching executor per type),
     * consumes the aggregate mana upfront, and advances the step counter by 1
     * (past the entire group) once all sub-futures resolve.
     *
     * <p>Use in blueprints via {@code "type": "parallel"} with nested {@code steps}.
     * Typical use-case: requesting multiple resource types simultaneously so
     * all transport ItemEntities fly at once rather than one-by-one.
     */
    record ParallelOp(List<AtomicOp> steps) implements AtomicOp {
        public ParallelOp {
            steps = List.copyOf(steps);
        }

        @Override
        public float baseManaCost() {
            return 0; // each sub-op carries its own mana cost
        }

        @Override
        public GridPos target() {
            return null; // positionless meta-op
        }
    }
}
