package com.wsteam.wandscape.op.api;

import com.wsteam.wandscape.core.types.*;
import com.wsteam.wandscape.op.executor.ResourceShortageException;

import javax.annotation.Nullable;
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
                AtomicOp.AltarCastOp,
                AtomicOp.ResourceRequestOp,
                AtomicOp.EmitEventOp,
                AtomicOp.IfConditionOp,
                AtomicOp.ParallelOp,
                AtomicOp.AttackMonsterOp,
                AtomicOp.SelfDefenseOp,
                AtomicOp.SpawnDecorationOp {

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
        public GridPos target() {
            return target;
        }
    }

    /**
     * Interact with a block — sync (toggle/activate/open_gui) or async (gather/decompose/synthesize).
     * Async actions use channelTicks for timing and params for action-specific data.
     * channelTicks is configurable from the blueprint (unlike RitualOp).
     */
    record BlockInteractOp(GridPos target, InteractAction action,
                           Map<String, String> params, int channelTicks) implements AtomicOp {
        public BlockInteractOp {
            if (params == null) params = Collections.emptyMap();
        }

        @Override
        public GridPos target() {
            return target;
        }
    }

    /** Apply an effect to a non-NPC entity. */
    record EntityInteractOp(EntityId entityId, EffectId effect, int strength, int duration) implements AtomicOp {
        @Override
        public GridPos target() {
            return null; // targets an entity by ID, not a grid position
        }
    }

    /**
     * Guard combat: cast a magic circle + beam at the nearest hostile within a defended
     * building zone. Positionless — the caster does NOT walk; the executor re-scans the
     * zone each cycle and casts at whatever it can see. 施法视觉（法阵/颜色）由 beam
     * 魔法的 MagicDef 定义，不随任务参数传递。
     *
     * @param attackRange  horizontal X/Z expansion where monsters are attacked (Y unchanged)
     * @param releaseRange horizontal X/Z expansion; the guard task completes only when no
     *                     monster remains inside it (hysteresis, >= attackRange)
     */
    record AttackMonsterOp(int attackRange, int releaseRange) implements AtomicOp {
        @Override
        public GridPos target() {
            return null; // no stance / no navigation — cast from current position
        }
    }

    /**
     * NPC self-defense: cast a magic circle + beam at a hostile near the NPC itself
     * (not a building zone). Independent of the guard task system — injected into the
     * NPC's private task queue, preempting the current task and resuming after.
     * Positionless — the caster does NOT walk; the executor re-scans around the NPC
     * each cycle and prioritizes a hated attacker (a non-player that hurt the NPC).
     * 施法视觉（法阵/颜色）由 beam 魔法的 MagicDef 定义，不随任务参数传递。
     *
     * @param radius   spherical distance around the NPC where hostile mobs are attacked
     */
    record SelfDefenseOp(int radius) implements AtomicOp {
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
        public GridPos target() {
            return target;
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
    }

    /**
     * 祭坛施法：NPC 走到祭坛旁（target = 祭坛中心），引导 {@code duration} tick 后释放魔法效果。
     * 蓝耗扣在**接取该任务的 NPC** 身上；冷却按祭坛（params["altar"] = building UUID）独立存放
     * （AltarCastState），与 NPC 自身每魔法 CD 解耦。
     *
     * @param target  祭坛包围盒中心（stance/寻路锚点）
     * @param magicId 要施放的魔法 id（MagicDef，须 altarOnly）
     * @param params  altar=<buildingId>、duration、mana_cost（调度器分派门槛读 mana_cost）
     */
    record AltarCastOp(GridPos target, String magicId,
                       Map<String, String> params) implements AtomicOp {
        public AltarCastOp {
            if (params == null) params = Collections.emptyMap();
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
        public GridPos target() {
            return null; // conditional logic, no world position
        }
    }

    /**
     * Spawn a decoration entity (item frame, painting) during building construction.
     * The entity is rebuilt from trimmed NBT at the target block cell.
     *
     * @param entityType entity registry id (e.g. "minecraft:item_frame")
     * @param facing     Direction name (e.g. "north"), may be empty to keep NBT's embedded facing
     * @param nbtBase64  base64-encoded compressed entity NBT (position-rebased, relative to anchor)
     */
    record SpawnDecorationOp(GridPos target, String entityType, String facing,
                             @Nullable String nbtBase64) implements AtomicOp {
        @Override
        public GridPos target() {
            return target;
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
        public GridPos target() {
            return null; // positionless meta-op
        }
    }
}
