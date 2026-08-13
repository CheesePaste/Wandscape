package com.wsteam.wandscape.building.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.shared.data.WorkItem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Priority-band queue behavior in {@link BuildingApiImpl}: a new task joins the tail
 * of its own priority band, merges into an adjacent same-recipe production task at that
 * band's tail, and higher-priority bands always run first. Keeps restock x1/x2 tasks
 * from flooding the workstation queue while player tasks jump the line.
 */
@DisplayName("BuildingApiImpl.mergeBandTail/insertByPriority")
class ProductionMergeTest {

    private static JsonElement anchor() {
        JsonArray arr = new JsonArray();
        arr.add(100);
        arr.add(200);
        arr.add(300);
        return arr;
    }

    private static WorkItem synthesize(String recipe, int count) {
        return synthesize(recipe, count, 10);
    }

    private static WorkItem synthesize(String recipe, int count, int priority) {
        Map<String, JsonElement> params = new LinkedHashMap<>();
        params.put("anchor", anchor());
        params.put("recipe_id", new JsonPrimitive(recipe));
        params.put("count", new JsonPrimitive(count));
        params.put("channel_ticks", new JsonPrimitive(10 * count));
        return new WorkItem("production:synthesize", params, priority);
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

    /** Mirrors {@code BuildingApiImpl.enqueueWork}: band-tail merge, else priority insert. */
    private static void enqueue(Deque<WorkItem> queue, WorkItem work) {
        if (BuildingApiImpl.mergeBandTail(queue, work)) return;
        BuildingApiImpl.insertByPriority(queue, work);
    }

    private static int count(WorkItem w) {
        return w.params().get("count").getAsInt();
    }

    private static int channel(WorkItem w) {
        return w.params().get("channel_ticks").getAsInt();
    }

    private static String recipeOf(WorkItem w) {
        return w.params().get("recipe_id").getAsString();
    }

    /** Deque has no indexed access; expose one for order assertions. */
    private static WorkItem at(Deque<WorkItem> queue, int index) {
        return new ArrayList<>(queue).get(index);
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
    void higherPrioritySynthesizeJumpsAboveBuildTask() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(build());
        enqueue(queue, synthesize("bread", 1));
        assertEquals(2, queue.size());
        // A production task (priority 10) is served before a build task (priority 5).
        assertEquals("production:synthesize", queue.getFirst().blueprintId());
    }

    @Test
    void doesNotMergeNonProduction() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(build());
        enqueue(queue, build());
        assertEquals(2, queue.size());
    }

    @Test
    void mergesIntoBandTail_acrossLowerPriorityTask() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(synthesize("bread", 1));
        queue.addLast(build()); // lower-priority band below the bread
        // A new bread merges into the bread at the top band tail — the lower build task
        // below it does not block the merge (it is a different, lower band).
        enqueue(queue, synthesize("bread", 1));
        assertEquals(2, queue.size());
        assertEquals(2, count(queue.getFirst()));
        assertEquals("production:synthesize", queue.getFirst().blueprintId());
    }

    @Test
    void keepsBandTailPriority() {
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

    // ── Priority-band ordering (player > restock > auto) ──

    @Test
    void playerTaskInsertsAboveLowerPriorityBands() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(synthesize("glass", 4, 40));   // auto-craft
        queue.addLast(synthesize("stone", 8, 40));   // auto-craft
        enqueue(queue, synthesize("bread", 2, 80));  // player task
        assertEquals(3, queue.size());
        assertEquals("bread", recipeOf(queue.getFirst()));
        assertEquals(80, queue.getFirst().priority());
    }

    @Test
    void restockInsertsBetweenPlayerAndAuto() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(synthesize("bread", 2, 80));   // player task
        queue.addLast(synthesize("stone", 8, 40));   // auto-craft
        enqueue(queue, synthesize("glass", 4, 60));  // shop restock
        assertEquals(3, queue.size());
        assertEquals("bread", recipeOf(queue.getFirst()));
        assertEquals("glass", recipeOf(at(queue, 1)));
        assertEquals(60, at(queue, 1).priority());
        assertEquals("stone", recipeOf(queue.getLast()));
    }

    @Test
    void samePriorityNewTaskGoesToBandTail() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(synthesize("a", 1, 80));
        queue.addLast(synthesize("b", 1, 80));
        enqueue(queue, synthesize("c", 1, 80));
        assertEquals(3, queue.size());
        assertEquals("a", recipeOf(at(queue, 0)));
        assertEquals("b", recipeOf(at(queue, 1)));
        assertEquals("c", recipeOf(at(queue, 2)));
    }

    @Test
    void restockDoesNotMergeIntoAutoBand() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(synthesize("bread", 5, 40));   // auto-craft bread
        enqueue(queue, synthesize("bread", 3, 60));  // restock bread — different band
        assertEquals(2, queue.size());
        assertEquals(5, count(queue.getLast()));      // auto-craft untouched
        assertEquals(60, queue.getFirst().priority());
        assertEquals(3, count(queue.getFirst()));
    }

    @Test
    void autoDoesNotMergeIntoPlayerBand() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(synthesize("bread", 5, 80));   // player bread
        enqueue(queue, synthesize("bread", 3, 40));  // auto-craft bread — different band
        assertEquals(2, queue.size());
        assertEquals(5, count(queue.getFirst()));     // player task untouched
        assertEquals(40, queue.getLast().priority());
        assertEquals(3, count(queue.getLast()));
    }

    @Test
    void playerMergesAtOwnBandTail_acrossLowerPriority() {
        Deque<WorkItem> queue = new ArrayDeque<>();
        queue.addLast(synthesize("bread", 1, 80));   // player bread
        queue.addLast(synthesize("stone", 8, 40));   // auto-craft
        enqueue(queue, synthesize("bread", 2, 80));  // another player bread → merges
        assertEquals(2, queue.size());
        assertEquals("bread", recipeOf(queue.getFirst()));
        assertEquals(3, count(queue.getFirst()));
    }
}
