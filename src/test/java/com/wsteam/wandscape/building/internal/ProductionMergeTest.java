package com.wsteam.wandscape.building.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.shared.data.WorkItem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Adjacent same-recipe production task merging in {@link BuildingApiImpl} —
 * keeps restock x1/x2 tasks from flooding the workstation queue.
 */
@DisplayName("BuildingApiImpl.mergeSameRecipeTail")
class ProductionMergeTest {

    private static JsonElement anchor() {
        JsonArray arr = new JsonArray();
        arr.add(100);
        arr.add(200);
        arr.add(300);
        return arr;
    }

    private static WorkItem synthesize(String recipe, int count) {
        Map<String, JsonElement> params = new LinkedHashMap<>();
        params.put("anchor", anchor());
        params.put("recipe_id", new JsonPrimitive(recipe));
        params.put("count", new JsonPrimitive(count));
        params.put("channel_ticks", new JsonPrimitive(10 * count));
        return new WorkItem("production:synthesize", params, 10);
    }

    private static WorkItem decompose(String item, int count) {
        Map<String, JsonElement> params = new LinkedHashMap<>();
        params.put("anchor", anchor());
        params.put("item_id", new JsonPrimitive(item));
        params.put("count", new JsonPrimitive(count));
        params.put("channel_ticks", new JsonPrimitive(10 * count));
        return new WorkItem("production:decompose", params, 10);
    }

    private static WorkItem build() {
        Map<String, JsonElement> params = new LinkedHashMap<>();
        params.put("anchor", anchor());
        params.put("name", new JsonPrimitive("house"));
        return new WorkItem("build:place_structure", params, 5);
    }

    private static WorkItem synthesizeAt(JsonElement stationAnchor, String recipe, int count) {
        Map<String, JsonElement> params = new LinkedHashMap<>();
        params.put("anchor", stationAnchor);
        params.put("recipe_id", new JsonPrimitive(recipe));
        params.put("count", new JsonPrimitive(count));
        params.put("channel_ticks", new JsonPrimitive(10 * count));
        return new WorkItem("production:synthesize", params, 10);
    }

    /** Mirrors {@code BuildingApiImpl.enqueueWork}'s tail logic: merge, else append. */
    private static void enqueue(Deque<WorkItem> queue, WorkItem work) {
        enqueue(queue, work, false);
    }

    /** Mirrors {@code BuildingApiImpl.enqueueWork}'s merge-or-place logic with explicit placement. */
    private static void enqueue(Deque<WorkItem> queue, WorkItem work, boolean atFront) {
        if (!atFront && BuildingApiImpl.mergeSameRecipeTail(queue, work)) return;
        if (atFront) queue.addFirst(work); else queue.addLast(work);
    }

    private static int count(WorkItem w) {
        return w.params().get("count").getAsInt();
    }

    private static int channel(WorkItem w) {
        return w.params().get("channel_ticks").getAsInt();
    }

    @Test
    void mergesAdjacentSameRecipe() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(synthesize("bread", 1));
        enqueue(queue, synthesize("bread", 2));
        assertEquals(1, queue.size());
        assertEquals(3, count(queue.getLast()));
        assertEquals(30, channel(queue.getLast()));
    }

    @Test
    void mergesRepeatedlyAccumulatingCount() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(synthesize("bread", 1));
        enqueue(queue, synthesize("bread", 1));
        enqueue(queue, synthesize("bread", 1));
        assertEquals(1, queue.size());
        assertEquals(3, count(queue.getLast()));
    }

    @Test
    void doesNotMergeDifferentRecipe() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(synthesize("bread", 1));
        enqueue(queue, synthesize("stone_bricks", 1));
        assertEquals(2, queue.size());
        assertEquals(1, count(queue.getFirst()));
        assertEquals(1, count(queue.getLast()));
    }

    @Test
    void doesNotMergeDifferentBlueprint() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(synthesize("bread", 1));
        enqueue(queue, decompose("bread", 1));
        assertEquals(2, queue.size());
        assertEquals("production:decompose", queue.getLast().blueprintId());
    }

    @Test
    void doesNotMergeAcrossDifferentAnchor() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(synthesizeAt(anchor(), "bread", 1));
        JsonElement other = new JsonArray();
        other.getAsJsonArray().add(900);
        other.getAsJsonArray().add(800);
        other.getAsJsonArray().add(700);
        enqueue(queue, synthesizeAt(other, "bread", 1));
        assertEquals(2, queue.size());
    }

    @Test
    void doesNotMergeAcrossBuildTask() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(build());
        enqueue(queue, synthesize("bread", 1));
        assertEquals(2, queue.size());
        assertEquals("production:synthesize", queue.getLast().blueprintId());
    }

    @Test
    void doesNotMergeNonProduction() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(build());
        enqueue(queue, build());
        assertEquals(2, queue.size());
    }

    @Test
    void mergesOnlyIntoTail_ignoresEarlierSameRecipe() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(synthesize("bread", 1));
        queue.addLast(build());
        // Tail is a build task → incoming synthesize appends, does not touch the earlier bread.
        enqueue(queue, synthesize("bread", 1));
        assertEquals(3, queue.size());
        assertEquals(1, count(queue.getFirst()));
    }

    @Test
    void keepsTailPriority() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(synthesize("bread", 1));
        enqueue(queue, synthesize("bread", 1));
        assertEquals(10, queue.getLast().priority());
    }

    @Test
    void mergesDecomposeTasksOfSameItem() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(decompose("minecraft:oak_log", 4));
        enqueue(queue, decompose("minecraft:oak_log", 6));
        assertEquals(1, queue.size());
        assertEquals(10, count(queue.getLast()));
        assertEquals(100, channel(queue.getLast()));
    }

    // ── Front placement (urgent shop-restock supply) ──

    @Test
    void frontPlacesAheadOfExistingTasks() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(synthesize("stone_bricks", 8));
        queue.addLast(synthesize("glass", 4));
        enqueue(queue, synthesize("bread", 2), true);
        assertEquals(3, queue.size());
        assertEquals("bread", recipeOf(queue.getFirst()));
    }

    @Test
    void frontPlacementSkipsTailMerge() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(synthesize("bread", 1));
        enqueue(queue, synthesize("bread", 2), true);
        // Front placement must NOT fold into the same-recipe tail — the whole point is
        // jumping the line, and countSynthesizeInFlight already dedups same-item shortfalls.
        assertEquals(2, queue.size());
        assertEquals(2, count(queue.getFirst()));
        assertEquals(1, count(queue.getLast()));
    }

    @Test
    void frontPlacementOnEmptyQueue() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        enqueue(queue, synthesize("bread", 2), true);
        assertEquals(1, queue.size());
        assertEquals("production:synthesize", queue.getFirst().blueprintId());
        assertEquals(2, count(queue.getFirst()));
    }

    @Test
    void frontPlacementPreservesExistingTail() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(synthesize("bread", 1));
        enqueue(queue, synthesize("stone_bricks", 8), true);
        assertEquals(2, queue.size());
        assertEquals("stone_bricks", recipeOf(queue.getFirst()));
        assertEquals("bread", recipeOf(queue.getLast()));
    }

    private static String recipeOf(WorkItem w) {
        return w.params().get("recipe_id").getAsString();
    }
}
