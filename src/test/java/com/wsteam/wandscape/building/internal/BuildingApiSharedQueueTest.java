package com.wsteam.wandscape.building.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.content.building.internal.BuildingApiImpl;
import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.shared.data.WorkItem;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Unit tests for the shared-queue routing helpers (pure logic — no MC runtime).
 */
class BuildingApiSharedQueueTest {

    private static final UUID ID = UUID.randomUUID();

    private static WorkItem production(String blueprint, String recipe, int count) {
        Map<String, JsonElement> params = new LinkedHashMap<>();
        params.put("anchor", jsonArray(1, 2, 3));
        params.put("recipe_id", new JsonPrimitive(recipe));
        params.put("count", new JsonPrimitive(count));
        params.put("channel_ticks", new JsonPrimitive(count * 5));
        return new WorkItem(blueprint, params, 80);
    }

    private static JsonArray jsonArray(int x, int y, int z) {
        JsonArray arr = new JsonArray();
        arr.add(x);
        arr.add(y);
        arr.add(z);
        return arr;
    }

    private static BuildingState state(String typeId, String category) {
        return new BuildingState(ID, typeId, category, BlockPos.ZERO,
                new BoundingBox(0, 0, 0, 3, 3, 3), 1, 1, 1);
    }

    // ── Role predicates ──

    @Test
    void isProductionWork_matchesProductionBlueprintsOnly() {
        assertTrue(BuildingApiImpl.isProductionWork(production("production:synthesize", "x", 1)));
        assertTrue(BuildingApiImpl.isProductionWork(production("production:decompose", "x", 1)));
        assertFalse(BuildingApiImpl.isProductionWork(production("node:gather", "x", 1)));
        assertFalse(BuildingApiImpl.isProductionWork(production("build:place_structure", "x", 1)));
        assertFalse(BuildingApiImpl.isProductionWork(null));
    }

    @Test
    void isGatherWork_matchesNodeGatherOnly() {
        assertTrue(BuildingApiImpl.isGatherWork(production("node:gather", "x", 1)));
        assertFalse(BuildingApiImpl.isGatherWork(production("production:synthesize", "x", 1)));
        assertFalse(BuildingApiImpl.isGatherWork(null));
    }

    // ── rebindAnchor ──

    @Test
    void rebindAnchor_replacesAnchorAndPreservesOtherParamsAndPriority() {
        WorkItem original = production("production:synthesize", "minecraft:bread", 5);

        WorkItem rebound = BuildingApiImpl.rebindAnchor(original, new BlockPos(9, 8, 7));

        assertNotSame(original, rebound, "must return a NEW WorkItem");
        assertEquals("production:synthesize", rebound.blueprintId());
        assertEquals(80, rebound.priority());

        JsonElement reboundAnchor = rebound.params().get("anchor");
        assertTrue(reboundAnchor.isJsonArray());
        assertEquals(9, reboundAnchor.getAsJsonArray().get(0).getAsInt());
        assertEquals(8, reboundAnchor.getAsJsonArray().get(1).getAsInt());
        assertEquals(7, reboundAnchor.getAsJsonArray().get(2).getAsInt());

        // Other params preserved
        assertEquals("minecraft:bread", rebound.params().get("recipe_id").getAsString());
        assertEquals(5, rebound.params().get("count").getAsInt());

        // Original untouched
        JsonElement origAnchor = original.params().get("anchor");
        assertEquals(1, origAnchor.getAsJsonArray().get(0).getAsInt());
        assertEquals(2, origAnchor.getAsJsonArray().get(1).getAsInt());
        assertEquals(3, origAnchor.getAsJsonArray().get(2).getAsInt());
    }

    // ── Shared-category / group-key ──

    @Test
    void isSharedQueueCategory_coversStationsAndNodes() {
        assertTrue(BuildingSavedData.isSharedQueueCategory("workstation"));
        assertTrue(BuildingSavedData.isSharedQueueCategory("crafting_station"));
        assertTrue(BuildingSavedData.isSharedQueueCategory("magic_station"));
        assertTrue(BuildingSavedData.isSharedQueueCategory("node"));
        assertFalse(BuildingSavedData.isSharedQueueCategory("shop"));
        assertFalse(BuildingSavedData.isSharedQueueCategory("storage"));
        assertFalse(BuildingSavedData.isSharedQueueCategory("government"));
    }

    @Test
    void groupKeyFor_workstationFamily_returnsTypeId() {
        assertEquals("workstation1", BuildingSavedData.groupKeyFor(state("workstation1", "workstation")));
        assertEquals("craftstation1", BuildingSavedData.groupKeyFor(state("craftstation1", "crafting_station")));
    }

    @Test
    void groupKeyFor_nonSharedCategory_returnsNull() {
        assertNull(BuildingSavedData.groupKeyFor(state("warehouse1", "storage")));
        assertNull(BuildingSavedData.groupKeyFor(state("townhall1", "government")));
        assertNull(BuildingSavedData.groupKeyFor(null));
    }

    @Test
    void groupKeyFor_nodeWithoutResolvableConfig_returnsNull() {
        // No node_config registered in the loader in a bare test JVM → unresolvable element → null.
        assertNull(BuildingSavedData.groupKeyFor(state("nodewood", "node")));
    }

    // ── dequeueWorkEligible's scan: pollFirstEligible ──

    private static String recipeId(WorkItem item) {
        return item.params().get("recipe_id").getAsString();
    }

    @Test
    void pollFirstEligible_returnsFirstAcceptedAndLeavesRejectedInPlace() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(production("production:synthesize", "a", 1));
        queue.addLast(production("production:synthesize", "b", 1));
        queue.addLast(production("production:synthesize", "c", 1));

        WorkItem picked = BuildingApiImpl.pollFirstEligible(queue, w -> "b".equals(recipeId(w)));

        assertNotNull(picked);
        assertEquals("b", recipeId(picked));
        // "a" (rejected) stays at the head, "c" untouched — the queue keeps its order.
        assertEquals(2, queue.size());
        assertEquals("a", recipeId(queue.peekFirst()));
        assertEquals("c", recipeId(new ArrayList<>(queue).get(1)));
    }

    @Test
    void pollFirstEligible_skipsIneligibleHeadForLaterAffordableItem() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(production("production:synthesize", "a", 1));
        queue.addLast(production("production:synthesize", "b", 1));

        WorkItem picked = BuildingApiImpl.pollFirstEligible(queue, w -> "b".equals(recipeId(w)));

        assertEquals("b", recipeId(picked));
        assertEquals(1, queue.size());
        assertEquals("a", recipeId(queue.peekFirst()));
    }

    @Test
    void pollFirstEligible_returnsNullWhenNothingAcceptedAndQueueUntouched() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(production("production:synthesize", "a", 1));
        queue.addLast(production("production:synthesize", "b", 1));

        assertNull(BuildingApiImpl.pollFirstEligible(queue, w -> false));
        assertEquals(2, queue.size());
    }

    @Test
    void pollFirstEligible_acceptsFirstMatchWhenHeadMatches() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(production("production:synthesize", "a", 1));
        queue.addLast(production("production:synthesize", "b", 1));

        WorkItem picked = BuildingApiImpl.pollFirstEligible(queue, w -> "a".equals(recipeId(w)));

        assertEquals("a", recipeId(picked));
        assertEquals(1, queue.size());
        assertEquals("b", recipeId(queue.peekFirst()));
    }
}
