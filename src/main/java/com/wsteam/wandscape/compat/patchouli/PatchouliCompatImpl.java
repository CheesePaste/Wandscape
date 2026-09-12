package com.wsteam.wandscape.compat.patchouli;

import net.minecraft.resources.ResourceLocation;
import vazkii.patchouli.api.PatchouliAPI;

import java.util.HashMap;
import java.util.Map;

/**
 * 帕秋莉手册客户端调用隔离实现。
 * 仅在 PatchouliCompat.isLoaded() 为真时由 JVM 解析装载。
 */
final class PatchouliCompatImpl {

    private static final Map<String, ResourceLocation> DOC_TO_ENTRY = new HashMap<>();

    static {
        // 与 gen_patchouli.py 生成的分类与条目保持一致
        register("index_guide", "contents");
        register("getting_started_guide", "start");
        register("overview_guide", "town");
        register("scanner_guide", "town");
        register("creative_scanner_guide", "town");
        register("road_guide", "road");
        register("road_replace_guide", "road");
        register("road_fill_guide", "road");
        register("road_spline_guide", "road");
        register("townhall_guide", "building");
        register("warehouse_guide", "building");
        register("crafting_guide", "building");
        register("magic_station_guide", "building");
        register("workstation_guide", "building");
        register("node_guide", "building");
        register("altar_guide", "building");
        register("mage_hut_guide", "building");
        register("tavern_guide", "building");
        register("shop_guide", "building");
        register("hotel_guide", "building");
        register("npc_guide", "npc");
        register("strategy_guide", "npc");
        register("tourist_guide", "npc");
        register("anomaly_guide", "ops");
        register("magic_circle_editor_guide", "creator");
        register("test_guide", "creator");
        register("commands_guide", "reference");
        register("creators_guide", "about");
    }

    private PatchouliCompatImpl() {}

    private static void register(String docName, String category) {
        ResourceLocation entryId = ResourceLocation.fromNamespaceAndPath("wandscape", category + "/" + docName);
        DOC_TO_ENTRY.put(docName, entryId);
        if (docName.endsWith("_guide")) {
            String alias = docName.substring(0, docName.length() - "_guide".length());
            DOC_TO_ENTRY.put(alias, entryId);
        }
    }

    static void openBook(String docPath) {
        if (PatchouliAPI.get().isStub()) {
            return;
        }

        if (docPath == null || docPath.isBlank() || "index_guide".equals(docPath) || "index".equals(docPath)) {
            PatchouliAPI.get().openBookGUI(PatchouliCompat.BOOK_ID);
            return;
        }

        ResourceLocation entryId = DOC_TO_ENTRY.get(docPath);
        if (entryId != null) {
            PatchouliAPI.get().openBookEntry(PatchouliCompat.BOOK_ID, entryId, 0);
        } else {
            // 未知文档名降级为打开手册主页
            PatchouliAPI.get().openBookGUI(PatchouliCompat.BOOK_ID);
        }
    }

    static boolean isBookOpen() {
        if (PatchouliAPI.get().isStub()) {
            return false;
        }
        ResourceLocation openGui = PatchouliAPI.get().getOpenBookGui();
        return PatchouliCompat.BOOK_ID.equals(openGui);
    }
}
