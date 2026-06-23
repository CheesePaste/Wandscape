package com.wsteam.wandscape.core.system;

import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.task.TaskSequence;
import com.wsteam.wandscape.core.types.BehaviourLevel;
import com.wsteam.wandscape.core.types.BehaviourTag;

import java.util.HashMap;
import java.util.Map;

import static com.wsteam.wandscape.core.types.BehaviourTag.*;

/**
 * Derives {@link BehaviourTag} requirements from a {@link TaskSequence}.
 *
 * <p>Scans all AtomicOps in the sequence and computes the strictest
 * (highest-level) requirement per tag. Ops that don't need a wand
 * (ResourceRequestOp, EmitEventOp, IfConditionOp, WandEquipOp, WandReturnOp)
 * contribute nothing.
 *
 * <p>Pure core — zero MC dependencies. Unit-testable.
 */
public final class WandRequirementDeriver {

    private WandRequirementDeriver() {}

    /**
     * Derive wand requirements from all ops in the sequence.
     * Returns the most strict (max level) per tag.
     * Returns empty map if no wand is needed.
     */
    public static Map<BehaviourTag, BehaviourLevel> derive(TaskSequence seq) {
        Map<BehaviourTag, BehaviourLevel> result = new HashMap<>();

        for (int i = 0; i < seq.size(); i++) {
            Map<BehaviourTag, BehaviourLevel> opReqs = deriveFromOp(seq.get(i));
            for (var entry : opReqs.entrySet()) {
                BehaviourLevel existing = result.get(entry.getKey());
                if (existing == null || entry.getValue().value() > existing.value()) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return result;
    }

    /** Derive requirements for a single op. */
    static Map<BehaviourTag, BehaviourLevel> deriveFromOp(AtomicOp op) {
        return switch (op) {
            case AtomicOp.TransformOp t       -> Map.of(BUILDING, BehaviourLevel.of(1));
            case AtomicOp.BlockInteractOp b   -> deriveFromAction(b.action().id());
            case AtomicOp.RitualOp r          -> deriveFromRitual(r.ritual().id());
            case AtomicOp.EntityInteractOp e  -> Map.of(ENTITY_INTERACTION, BehaviourLevel.of(1));
            case AtomicOp.ResourceRequestOp r -> Map.of();
            case AtomicOp.EmitEventOp e       -> Map.of();
            case AtomicOp.IfConditionOp i     -> Map.of();
            case AtomicOp.WandEquipOp w       -> Map.of();
            case AtomicOp.WandReturnOp w      -> Map.of();
        };
    }

    private static Map<BehaviourTag, BehaviourLevel> deriveFromAction(String action) {
        return switch (action) {
            case "gather" -> Map.of(); // basic gathering needs no wand — level 0
            case "decompose", "brew_potion"
                    -> Map.of(CRAFTING, BehaviourLevel.of(1));
            // synthesize and craft_wand: wand-level is recipe-driven, not action-driven.
            // A synthesize recipe with wand_level {"crafting": 0} means any NPC can attempt;
            // wand_level {"crafting": 1} requires a crafting wand.  The per-recipe wand_level
            // override (passed via WorkItem overrides) takes priority over this default.
            case "synthesize", "craft_wand" -> Map.of();
            default -> Map.of(); // toggle, activate, open_gui — no wand needed
        };
    }

    private static Map<BehaviourTag, BehaviourLevel> deriveFromRitual(String ritualId) {
        return switch (ritualId) {
            case "warding", "self_teleport", "item_teleport", "player_summon"
                    -> Map.of(RITUAL, BehaviourLevel.of(1));
            case "group_vigor", "rain_call", "clear_weather"
                    -> Map.of(RITUAL, BehaviourLevel.of(2));
            case "portal_gate" -> Map.of(RITUAL, BehaviourLevel.of(3));
            default -> Map.of(RITUAL, BehaviourLevel.of(1));
        };
    }
}
