package com.wsteam.wandscape.task.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.op.api.AtomicOp;
import com.wsteam.wandscape.task.engine.dsl.Blueprint;
import com.wsteam.wandscape.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.task.engine.dsl.BlueprintSteps;
import com.wsteam.wandscape.task.runtime.TaskSequence;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link EventDrivenTaskSource#registerDefaultBlueprints}: the auto-registered
 * gather blueprints must produce a ResourceRequestOp of the right resource, followed by
 * a TransformOp, with a default amount of 16 (overridable via the "amount" param).
 */
class EventDrivenTaskSourceTest {

    private static final String[] GATHER_IDS = {
            "gather:wood", "gather:stone_bricks", "gather:stone",
            "gather:glass", "gather:iron_ingot", "gather:wheat"
    };

    @Test
    void registerDefaultBlueprints_registersAllGatherBlueprints() {
        BlueprintRegistry registry = new BlueprintRegistry();
        EventDrivenTaskSource.registerDefaultBlueprints(registry);
        for (String id : GATHER_IDS) {
            assertTrue(registry.has(id), "missing default blueprint: " + id);
        }
    }

    @Test
    void gatherWood_defaultAmountIs16() {
        TaskSequence seq = generate("gather:wood", Map.of());
        assertEquals("Gather wood x16", seq.label());
        assertEquals(2, seq.size(), "ResourceRequestOp + TransformOp");
        assertEquals(ResourceId.WOOD, requestResource(seq));
        assertEquals(16, requestAmount(seq));
    }

    @Test
    void gatherAmount_paramOverridesDefault() {
        TaskSequence seq = generate("gather:stone", Map.of("amount", new JsonPrimitive(8)));
        assertEquals("Gather stone x8", seq.label());
        assertEquals(8, requestAmount(seq));
    }

    @Test
    void gatherSteps_locationFromParams() {
        Map<String, JsonElement> params = Map.of(
                "amount", new JsonPrimitive(4),
                "x", new JsonPrimitive(5),
                "y", new JsonPrimitive(64),
                "z", new JsonPrimitive(-3));
        TaskSequence seq = generate("gather:glass", params);
        assertEquals("Gather glass x4", seq.label());
        assertEquals(4, requestAmount(seq));
    }

    @Test
    void gatherSteps_withoutLocation_fallsBackToOriginNotNil() {
        TaskSequence seq = generate("gather:wood", Map.of("amount", new JsonPrimitive(2)));
        assertNotNull(seq, "missing coords must fall back to ORIGIN, never null");
        assertEquals("Gather wood x2", seq.label());
        assertEquals(2, requestAmount(seq));
    }

    private static TaskSequence generate(String id, Map<String, JsonElement> params) {
        BlueprintRegistry registry = new BlueprintRegistry();
        EventDrivenTaskSource.registerDefaultBlueprints(registry);
        Blueprint bp = registry.get(id);
        assertNotNull(bp, "blueprint not registered: " + id);
        BlueprintSteps steps = bp.steps();
        return steps.generate(params);
    }

    private static ResourceId requestResource(TaskSequence seq) {
        AtomicOp.ResourceRequestOp op = (AtomicOp.ResourceRequestOp) seq.steps().get(0);
        return op.items().get(0).resource();
    }

    private static int requestAmount(TaskSequence seq) {
        AtomicOp.ResourceRequestOp op = (AtomicOp.ResourceRequestOp) seq.steps().get(0);
        return op.items().get(0).amount();
    }
}
