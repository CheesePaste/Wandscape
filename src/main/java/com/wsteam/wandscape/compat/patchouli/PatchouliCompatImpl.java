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
        // start
        register("intro_0_guide", "start");
        register("intro_0_5_guide", "start");
        // playstyle
        register("track_tourist_guide", "playstyle");
        // system
        register("economy_guide", "system");
        register("panel_guide", "system");
        register("mages_guide", "system");
        register("tourists_guide", "system");

        // 为原有通用界面入口提供别名映射
        DOC_TO_ENTRY.put("overview_guide", DOC_TO_ENTRY.get("panel_guide"));
        DOC_TO_ENTRY.put("overview", DOC_TO_ENTRY.get("panel_guide"));
        DOC_TO_ENTRY.put("npc_guide", DOC_TO_ENTRY.get("mages_guide"));
        DOC_TO_ENTRY.put("npc", DOC_TO_ENTRY.get("mages_guide"));
        DOC_TO_ENTRY.put("tourist_guide", DOC_TO_ENTRY.get("tourists_guide"));
        DOC_TO_ENTRY.put("tourist", DOC_TO_ENTRY.get("tourists_guide"));
    }

    private PatchouliCompatImpl() {}

    static void initClient() {
        PatchouliBookRenderer.init();
    }

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
