package com.wsteam.wandscape.core.op;

import com.wsteam.wandscape.core.types.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.types.GridPos;
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
                AtomicOp.WandEquipOp,
                AtomicOp.WandReturnOp,
                AtomicOp.ParallelOp {

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
            ResourceStack consumable
    ) implements AtomicOp {
        public TransformOp {
            if (drops == null) drops = Collections.emptyList();
        }

        // Convenience constructors
        public static TransformOp place(GridPos target, BlockType to) {
            return new TransformOp(target, BlockType.AIR, to, false, Collections.emptyList(), null);
        }

        public static TransformOp place(GridPos target, BlockType to, ResourceStack consumable) {
            return new TransformOp(target, BlockType.AIR, to, false, Collections.emptyList(), consumable);
        }

        public static TransformOp remove(GridPos target, BlockType from, List<ResourceStack> drops) {
            return new TransformOp(target, from, BlockType.AIR, true, drops, null);
        }

        public static TransformOp convert(GridPos target, BlockType from, BlockType to) {
            return new TransformOp(target, from, to, true, Collections.emptyList(), null);
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

        /** Backward-compat constructor for sync actions (toggle/activate/open_gui). */
        public BlockInteractOp(GridPos target, InteractAction action) {
            this(target, action, Collections.emptyMap(), 0, 1.0f);
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

    /** Perform a ritual (may involve channeling over multiple ticks). */
    record RitualOp(RitualId ritual, GridPos target,
                    Map<String, String> params) implements AtomicOp {
        public RitualOp {
            if (params == null) params = Collections.emptyMap();
        }

        /** Convenience constructor without params (backward-compat). */
        public RitualOp(RitualId ritual, GridPos target) {
            this(ritual, target, Collections.emptyMap());
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

        /** Convenience constructor for a single resource (backward-compat). */
        public ResourceRequestOp(ResourceStack single) {
            this(List.of(single));
        }

        /** Backward-compat accessor for single-item requests. */
        public ResourceStack requested() {
            return items.get(0);
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
     * Fetch a wand from the colony warehouse and equip it on the NPC.
     * The executor resolves the nearest storage building.
     * Zero mana cost — this is a preparatory step, not spellcasting.
     */
    record WandEquipOp(String wandItemId) implements AtomicOp {
        @Override
        public float baseManaCost() {
            return 0;
        }

        @Override
        public GridPos target() {
            return null; // executor resolves nearest storage building
        }
    }

    /**
     * Return an equipped wand to the colony warehouse.
     * The executor resolves the nearest storage building.
     * Zero mana cost — this is cleanup, not spellcasting.
     */
    record WandReturnOp(String wandItemId) implements AtomicOp {
        @Override
        public float baseManaCost() {
            return 0;
        }

        @Override
        public GridPos target() {
            return null; // executor resolves nearest storage building
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
