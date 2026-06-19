package org.magiccolony.core.op;

import org.magiccolony.core.types.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

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
                AtomicOp.IfConditionOp {

    /** Base mana cost for this operation (before wand efficiency). */
    int baseManaCost();

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
        public int baseManaCost() {
            return 5;
        }
    }

    /** Interact with a block (toggle, activate, open GUI). */
    record BlockInteractOp(GridPos target, InteractAction action) implements AtomicOp {
        @Override
        public int baseManaCost() {
            return 2;
        }
    }

    /** Apply an effect to a non-NPC entity. */
    record EntityInteractOp(EntityId target, EffectId effect, int strength, int duration) implements AtomicOp {
        @Override
        public int baseManaCost() {
            return 3;
        }
    }

    /** Perform a ritual (may involve channeling over multiple ticks). */
    record RitualOp(RitualId ritual, GridPos target, int channelTicks) implements AtomicOp {
        @Override
        public int baseManaCost() {
            // Rituals vary; base cost reflects minimum
            return switch (ritual.id()) {
                case "item_teleport" -> 3;
                case "player_summon" -> 5;
                case "warding" -> 10;
                case "group_vigor" -> 15;
                case "rain_call", "clear_weather" -> 20;
                case "portal_gate" -> 30;
                default -> 10;
            };
        }
    }

    /** Request resources from the colony warehouse. The executor resolves this inline. */
    record ResourceRequestOp(ResourceStack requested) implements AtomicOp {
        @Override
        public int baseManaCost() {
            return 1; // Teleportation cost handled by the ritual inserted into private queue
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
        public int baseManaCost() {
            return 0;
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
        public int baseManaCost() {
            return 0;
        }
    }
}
