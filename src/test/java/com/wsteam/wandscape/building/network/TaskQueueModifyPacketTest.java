package com.wsteam.wandscape.building.network;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.content.building.network.TaskQueueModifyPacket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 队列条目分类/图标 id 提取/摘要：纯逻辑，无 MC 运行时依赖。
 * 守护 craft_spell 归为 transcribe（而非 other）、配方类任务优先用 output_item 做图标。
 */
@DisplayName("TaskQueueModifyPacket: 队列条目分类与图标 id")
class TaskQueueModifyPacketTest {

    private static Map<String, JsonElement> params(Object... kv) {
        Map<String, JsonElement> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], jsonOf(kv[i + 1]));
        }
        return map;
    }

    /** Numbers stay numeric (paramInt 依赖 isNumber() 的原语)，其余转字符串。 */
    private static JsonElement jsonOf(Object v) {
        if (v instanceof Number n) return new JsonPrimitive(n);
        return new JsonPrimitive(String.valueOf(v));
    }

    // ── categorize ──

    @Test
    @DisplayName("categorize: 各 blueprint 映射到分类，craft_spell→transcribe，未知→other")
    void categorizeMappings() {
        assertEquals("decompose", TaskQueueModifyPacket.categorize("production:decompose"));
        assertEquals("synthesize", TaskQueueModifyPacket.categorize("production:synthesize"));
        assertEquals("craft", TaskQueueModifyPacket.categorize("production:craft"));
        assertEquals("transcribe", TaskQueueModifyPacket.categorize("production:craft_spell"));
        assertEquals("build", TaskQueueModifyPacket.categorize("build:town_hall"));
        assertEquals("gather", TaskQueueModifyPacket.categorize("node:gather"));
        assertEquals("other", TaskQueueModifyPacket.categorize("some:unknown"));
    }

    // ── extractItemId ──

    @Test
    @DisplayName("extractItemId: 配方类任务优先返回 output_item（已注册物品，图标可渲染）")
    void recipeBasedPrefersOutputItem() {
        assertEquals("wandscape:wand",
                TaskQueueModifyPacket.extractItemId("production:craft",
                        params("recipe_id", "apprentice_wand", "output_item", "wandscape:wand")));
        assertEquals("wandscape:spell_scroll",
                TaskQueueModifyPacket.extractItemId("production:craft_spell",
                        params("recipe_id", "scroll_heal", "output_item", "wandscape:spell_scroll")));
        assertEquals("wandscape:peace_wand",
                TaskQueueModifyPacket.extractItemId("production:craft",
                        params("recipe_id", "peace_wand", "output_item", "wandscape:peace_wand")));
    }

    @Test
    @DisplayName("extractItemId: 无 output_item 的旧任务回退 recipe_id；craft_spell 与其余配方同类处理")
    void recipeBasedFallsBackToRecipeId() {
        assertEquals("scroll_heal",
                TaskQueueModifyPacket.extractItemId("production:craft_spell",
                        params("recipe_id", "scroll_heal")));
        assertEquals("apprentice_wand",
                TaskQueueModifyPacket.extractItemId("production:craft",
                        params("recipe_id", "apprentice_wand")));
    }

    @Test
    @DisplayName("extractItemId: decompose 用 item_id，build/gather 用各自参数，未知为空")
    void otherBlueprints() {
        assertEquals("minecraft:oak_log",
                TaskQueueModifyPacket.extractItemId("production:decompose",
                        params("item_id", "minecraft:oak_log")));
        assertEquals("town_hall",
                TaskQueueModifyPacket.extractItemId("build:build",
                        params("name", "town_hall")));
        assertEquals("fire",
                TaskQueueModifyPacket.extractItemId("node:gather",
                        params("element", "fire")));
        assertEquals("", TaskQueueModifyPacket.extractItemId("unknown:x", params()));
    }

    // ── summarizeWorkItem ──

    @Test
    @DisplayName("summarizeWorkItem: craft_spell 摘要为 Transcribe <recipe> xN")
    void summarizeCraftSpell() {
        assertEquals("Transcribe scroll_heal x3",
                TaskQueueModifyPacket.summarizeWorkItem("production:craft_spell",
                        params("recipe_id", "scroll_heal", "count", 3)));
    }
}
