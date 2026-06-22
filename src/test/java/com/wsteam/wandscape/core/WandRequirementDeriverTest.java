package com.wsteam.wandscape.core;

import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.system.WandRequirementDeriver;
import com.wsteam.wandscape.core.task.TaskSequence;
import com.wsteam.wandscape.core.types.*;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.wsteam.wandscape.core.types.BehaviourTag.*;
import static org.junit.jupiter.api.Assertions.*;

class WandRequirementDeriverTest {

    @Test
    void transformOpRequiresBuilding() {
        TaskSequence seq = TaskSequence.of("test",
                AtomicOp.TransformOp.place(new GridPos(1, 2, 3), BlockType.STONE_BRICKS));
        Map<BehaviourTag, BehaviourLevel> reqs = WandRequirementDeriver.derive(seq);
        assertEquals(Map.of(BUILDING, BehaviourLevel.of(1)), reqs);
    }

    @Test
    void gatherBlockInteractRequiresGathering() {
        TaskSequence seq = TaskSequence.of("test",
                new AtomicOp.BlockInteractOp(new GridPos(0, 0, 0),
                        new InteractAction("gather")));
        Map<BehaviourTag, BehaviourLevel> reqs = WandRequirementDeriver.derive(seq);
        assertEquals(Map.of(GATHERING, BehaviourLevel.of(1)), reqs);
    }

    @Test
    void craftingActionsRequireCrafting() {
        for (String action : List.of("decompose", "synthesize", "craft_wand", "brew_potion")) {
            TaskSequence seq = TaskSequence.of("test",
                    new AtomicOp.BlockInteractOp(new GridPos(0, 0, 0),
                            new InteractAction(action)));
            Map<BehaviourTag, BehaviourLevel> reqs = WandRequirementDeriver.derive(seq);
            assertEquals(Map.of(CRAFTING, BehaviourLevel.of(1)),
                    reqs, "action=" + action);
        }
    }

    @Test
    void ritualRequiresRitualWithCorrectLevel() {
        TaskSequence seq = TaskSequence.of("test",
                new AtomicOp.RitualOp(new RitualId("portal_gate"), GridPos.ORIGIN));
        Map<BehaviourTag, BehaviourLevel> reqs = WandRequirementDeriver.derive(seq);
        assertEquals(Map.of(RITUAL, BehaviourLevel.of(3)), reqs);
    }

    @Test
    void basicRitualRequiresRitual1() {
        TaskSequence seq = TaskSequence.of("test",
                new AtomicOp.RitualOp(new RitualId("self_teleport"), GridPos.ORIGIN));
        Map<BehaviourTag, BehaviourLevel> reqs = WandRequirementDeriver.derive(seq);
        assertEquals(Map.of(RITUAL, BehaviourLevel.of(1)), reqs);
    }

    @Test
    void resourceRequestRequiresNothing() {
        TaskSequence seq = TaskSequence.of("test",
                new AtomicOp.ResourceRequestOp(new ResourceStack(ResourceId.WOOD, 10)));
        Map<BehaviourTag, BehaviourLevel> reqs = WandRequirementDeriver.derive(seq);
        assertTrue(reqs.isEmpty());
    }

    @Test
    void emitEventRequiresNothing() {
        TaskSequence seq = TaskSequence.of("test",
                new AtomicOp.EmitEventOp("test_event", Collections.emptyMap()));
        Map<BehaviourTag, BehaviourLevel> reqs = WandRequirementDeriver.derive(seq);
        assertTrue(reqs.isEmpty());
    }

    @Test
    void wandOpsRequireNothing() {
        TaskSequence seq = TaskSequence.of("test",
                new AtomicOp.WandEquipOp("wandscape:builder_wand"));
        Map<BehaviourTag, BehaviourLevel> reqs = WandRequirementDeriver.derive(seq);
        assertTrue(reqs.isEmpty());
    }

    @Test
    void multipleOpsMergeMaxLevel() {
        // Two rituals of different levels → max should win
        TaskSequence seq = TaskSequence.of("test",
                new AtomicOp.RitualOp(new RitualId("self_teleport"), GridPos.ORIGIN), // RITUAL:1
                new AtomicOp.RitualOp(new RitualId("portal_gate"), GridPos.ORIGIN));   // RITUAL:3
        Map<BehaviourTag, BehaviourLevel> reqs = WandRequirementDeriver.derive(seq);
        assertEquals(BehaviourLevel.of(3), reqs.get(RITUAL));
    }

    @Test
    void mixedOpsDeriveAllTags() {
        TaskSequence seq = TaskSequence.of("test",
                AtomicOp.TransformOp.place(new GridPos(1, 2, 3), BlockType.STONE),
                new AtomicOp.BlockInteractOp(new GridPos(4, 5, 6),
                        new InteractAction("gather")));
        Map<BehaviourTag, BehaviourLevel> reqs = WandRequirementDeriver.derive(seq);
        assertEquals(2, reqs.size());
        assertEquals(BehaviourLevel.of(1), reqs.get(BUILDING));
        assertEquals(BehaviourLevel.of(1), reqs.get(GATHERING));
    }

    @Test
    void entityInteractRequiresEntityInteraction() {
        TaskSequence seq = TaskSequence.of("test",
                new AtomicOp.EntityInteractOp(
                        new EntityId(123L),
                        new EffectId("damage"), 5, 100));
        Map<BehaviourTag, BehaviourLevel> reqs = WandRequirementDeriver.derive(seq);
        assertEquals(Map.of(ENTITY_INTERACTION, BehaviourLevel.of(1)), reqs);
    }
}
