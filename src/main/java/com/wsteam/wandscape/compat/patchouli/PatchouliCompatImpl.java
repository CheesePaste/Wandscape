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
        // 与 gen_patchouli.py 生成的分类与条目保持一致。
        // 有几篇 md 同时登记在「通用功能」和细分类别下（生成两份同名条目），这里只登记**首次**
        // 出现的那一份——/wandscape guide <doc> 打开哪一份内容都一样，取先写的那个即可。
        // playstyle
        register("index_guide", "playstyle");
        register("intro_0_guide", "playstyle");
        register("intro_0_5_guide", "playstyle");
        register("track_tourist_guide", "playstyle");
        // system（大功能入口）
        register("economy_guide", "system");
        register("panel_guide", "system");
        register("mages_guide", "system");
        register("tourists_guide", "system");
        register("town_level_guide", "system");
        register("casting_guide", "system");
        register("buildings_guide", "system");
        register("equipment_guide", "system");
        register("custom_guide", "system");
        // buildings（每类建筑一条，与 buildings/*.json 的 category 一一对应）
        register("townhall_guide", "buildings");
        register("warehouse_guide", "buildings");
        register("workstation_guide", "buildings");
        register("crafting_guide", "buildings");
        register("magic_station_guide", "buildings");
        register("tavern_guide", "buildings");
        register("altar_guide", "buildings");
        register("mage_hut_guide", "buildings");
        register("node_guide", "buildings");
        register("decoration_guide", "buildings");
        register("shop_guide", "buildings");
        register("service_guide", "buildings");
        register("relax_guide", "buildings");
        register("atm_guide", "buildings");
        register("anomaly_guide", "buildings");
        register("building_scanner_guide", "buildings");
        // management（面板 + 四个子模式）
        register("panel_build_guide", "management");
        register("panel_road_guide", "management");
        register("panel_tasks_guide", "management");
        register("panel_settings_guide", "management");
        // magic（每条一个魔法，正文与 JEI 卷轴信息页同文）
        register("magic_beam_guide", "magic");
        register("magic_meteor_guide", "magic");
        register("magic_desperation_guide", "magic");
        register("magic_enfeeble_field_guide", "magic");
        register("magic_conversion_guide", "magic");
        register("magic_petrification_guide", "magic");
        register("magic_fortification_guide", "magic");
        register("magic_heal_guide", "magic");
        register("magic_teleport_guide", "magic");
        register("magic_revive_guide", "magic");
        // items（小道具按类归并成条，法杖整族一条）
        register("wand_guide", "items");
        register("oath_ring_guide", "items");
        register("scepter_guide", "items");
        register("magic_compass_guide", "items");
        register("warehouse_terminal_guide", "items");
        // custom
        register("custom_buildings_guide", "custom");
        register("custom_packs_guide", "custom");
        register("custom_elements_guide", "custom");
        // compat（每个第三方模组一条）
        register("curios_guide", "compat");
        register("irons_spells_guide", "compat");
        register("goety_guide", "compat");
        register("tlm_guide", "compat");

        // 为原有通用界面入口提供别名映射
        DOC_TO_ENTRY.put("overview_guide", DOC_TO_ENTRY.get("panel_guide"));
        DOC_TO_ENTRY.put("overview", DOC_TO_ENTRY.get("panel_guide"));
        DOC_TO_ENTRY.put("npc_guide", DOC_TO_ENTRY.get("mages_guide"));
        DOC_TO_ENTRY.put("npc", DOC_TO_ENTRY.get("mages_guide"));
        DOC_TO_ENTRY.put("tourist_guide", DOC_TO_ENTRY.get("tourists_guide"));
        DOC_TO_ENTRY.put("tourist", DOC_TO_ENTRY.get("tourists_guide"));
        DOC_TO_ENTRY.put("buildings", DOC_TO_ENTRY.get("buildings_guide"));
        // 「旅馆与服务建筑」已改名为「服务设施」：旅馆是 service 类别下的一类，旧名仍指向同一页
        DOC_TO_ENTRY.put("hotel_guide", DOC_TO_ENTRY.get("service_guide"));
        DOC_TO_ENTRY.put("hotel", DOC_TO_ENTRY.get("service_guide"));
        DOC_TO_ENTRY.put("casting", DOC_TO_ENTRY.get("casting_guide"));
        DOC_TO_ENTRY.put("equipment", DOC_TO_ENTRY.get("equipment_guide"));
        DOC_TO_ENTRY.put("custom", DOC_TO_ENTRY.get("custom_guide"));
    }

    private PatchouliCompatImpl() {}

    static void initClient() {
        PatchouliBookRenderer.init();
    }

    private static void register(String docName, String category) {
        // 同一篇 md 可能挂在多个分类下；取先登记的那一份，后面的忽略
        if (DOC_TO_ENTRY.containsKey(docName)) {
            return;
        }
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
