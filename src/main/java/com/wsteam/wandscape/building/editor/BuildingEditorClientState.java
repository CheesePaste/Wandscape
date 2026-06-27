package com.wsteam.wandscape.building.editor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.shared.data.DecorationConfig;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.MaintenanceCostConfig;
import com.wsteam.wandscape.shared.data.ServiceConfig;
import com.wsteam.wandscape.shared.data.ShopConfig;
import com.wsteam.wandscape.shared.data.ShopGoodDef;
import com.wsteam.wandscape.shared.data.WonderConfig;
import com.wsteam.wandscape.shared.data.WonderEffect;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side static state for the building editor.
 * Thread-safe via volatile fields + synchronized blocks where needed.
 * Pattern mirrors {@code ProjectionClientState} and {@code RoadEditorClientState}.
 *
 * <p>All BuildingConfig fields are held in editable form here.
 * The GUI panel and renderer both read from this state.
 */
public final class BuildingEditorClientState {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ── Edit mode ──

    private static volatile boolean editMode = false;

    // ── World anchor (absolute world position of the building's anchor point) ──

    private static volatile BlockPos worldAnchor = null;
    /** The relative offset within the AABB that is the "anchor" for pattern coordinates. */
    private static volatile BlockOffset anchorOffset = null;

    // ── AABB (relative to worldAnchor) ──

    private static volatile BlockOffset editMin = null;  // null = not set yet
    private static volatile BlockOffset editMax = null;

    // ── Scanned pattern + block mapping ──

    private static volatile List<BlockOffset> pattern = List.of();
    private static volatile Map<String, String> blockMapping = Map.of();

    // ── Building metadata ──

    private static volatile String buildingId = "";
    private static volatile String displayName = "";
    private static volatile String category = "basic";

    // ── Three values ──

    private static volatile int comfort = 0;
    private static volatile int magic = 0;
    private static volatile int wonder = 0;

    // ── Queue ──

    private static volatile int queueCapacity = 5;
    private static final List<String> taskTypes = new ArrayList<>(List.of("building"));

    // ── Unlock requirement ──

    private static volatile int unlockMinComfort = 0;
    private static volatile int unlockMinMagic = 0;
    private static volatile int unlockMinWonder = 0;

    // ── Maintenance cost ──

    private static volatile int maintenanceIntervalTicks = MaintenanceCostConfig.DEFAULT_INTERVAL_TICKS;
    private static final Map<ElementType, Long> maintenanceCosts = new HashMap<>();

    // ── Blueprint reference ──

    private static volatile String blueprintId = "build:clear_and_build";
    private static final Map<String, String> blueprintBind = new HashMap<>();

    // ── Interaction radius ──

    private static volatile int interactionRadius = 0;

    // ── Category-specific configs ──

    /** shop: goods list + profit rate. Null if not a shop. */
    private static final List<ShopGoodDef> shopGoods = new ArrayList<>();
    private static volatile double shopProfitRate = 0.2;

    /** service: config. Null if not a service. */
    private static volatile int serviceEnergyPerUse = 20;
    private static volatile int serviceSatisfactionPerUse = 15;
    private static final Map<ElementType, Long> serviceElementOutput = new HashMap<>();
    private static volatile int serviceMaxOccupancy = 0;

    /** decoration: radius. 0 = not a decoration. */
    private static volatile int decorationRadius = 0;

    /** wonder: effects list. Empty if not a wonder. */
    private static final List<WonderEffect> wonderEffects = new ArrayList<>();

    /** node: config fields. */
    private static volatile String nodeElement = "";
    private static volatile int nodeAmountPerHarvest = 5;
    private static volatile int nodeChannelTicks = 100;
    private static volatile int nodeManaCost = 10;
    private static volatile String nodeBlueprint = "node:gather";

    // ── Projection state (carried over from soul projection) ──

    private static volatile BlockPos bodyAnchor = null;

    // ── Axis drag state ──

    /** Axis currently hovered by the crosshair (null = none). */
    private static volatile AxisDrag hoveredAxis = null;
    /** World-space point on the hovered axis (for rendering). */
    private static volatile Vec3 hoveredAxisWorld = null;
    /** Axis currently being dragged (null = idle). */
    private static volatile AxisDrag draggingAxis = null;
    /** World position where the drag started. */
    private static volatile BlockPos dragStartWorld = null;
    /** The relative min/max state at drag start (used for restoring on cancel). */
    private static volatile BlockOffset dragStartMin = null;
    private static volatile BlockOffset dragStartMax = null;

    // ── Other UI state ──

    /** Whether JSON preview is toggled on (used by ImGui panel). */
    private static volatile boolean showPreview = false;

    /** Cached JSON preview text (used by ImGui panel). */
    private static volatile String previewJson = "";

    /** Snapshot of player abilities before editor (for restore on exit). */
    private static volatile AbilitySnapshot savedAbilities = null;

    private BuildingEditorClientState() {}

    /** Snapshot of player abilities for restoration on exit. */
    private record AbilitySnapshot(
            boolean mayfly, boolean flying, boolean instabuild, boolean mayBuild,
            float flyingSpeed, float walkingSpeed) {}

    // ═══════════════════════════════════════════════════════════════
    // ── Edit mode ──
    // ═══════════════════════════════════════════════════════════════

    public static boolean isEditing() { return editMode; }

    public static void enterEditMode(BlockPos bodyAnchor, BlockPos worldAnchor,
                                      BlockOffset anchorOffset,
                                      BlockOffset editMin, BlockOffset editMax,
                                      String buildingId, String displayName, String category) {
        editMode = true;
        BuildingEditorClientState.bodyAnchor = bodyAnchor;
        BuildingEditorClientState.worldAnchor = worldAnchor;
        BuildingEditorClientState.anchorOffset = anchorOffset;
        BuildingEditorClientState.editMin = editMin;
        BuildingEditorClientState.editMax = editMax;
        BuildingEditorClientState.buildingId = buildingId;
        BuildingEditorClientState.displayName = displayName;
        BuildingEditorClientState.category = category;

        // Enable creative flight
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            var abilities = mc.player.getAbilities();
            savedAbilities = new AbilitySnapshot(
                    abilities.mayfly, abilities.flying, abilities.instabuild, abilities.mayBuild,
                    abilities.getFlyingSpeed(), abilities.getWalkingSpeed());
            abilities.mayfly = true;
            abilities.flying = true;
            abilities.setFlyingSpeed(BuildingEditorController.getFlyingSpeed());
            mc.player.onUpdateAbilities();
        }

        // Auto-show ImGui (releases mouse)
        com.wsteam.wandscape.imgui.ImGuiManager.setVisible(true);

        LOGGER.info("[BuildEditor] Entered edit mode. worldAnchor={}, min={}, max={}, id={}",
                worldAnchor, editMin, editMax, buildingId);
    }

    public static void exitEditMode() {
        editMode = false;

        // Restore abilities
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && savedAbilities != null) {
            var abilities = mc.player.getAbilities();
            abilities.mayfly = savedAbilities.mayfly;
            abilities.flying = savedAbilities.flying;
            abilities.instabuild = savedAbilities.instabuild;
            abilities.mayBuild = savedAbilities.mayBuild;
            abilities.setFlyingSpeed(savedAbilities.flyingSpeed);
            abilities.setWalkingSpeed(savedAbilities.walkingSpeed);
            mc.player.onUpdateAbilities();
        }

        bodyAnchor = null;
        worldAnchor = null;
        anchorOffset = null;
        editMin = null;
        editMax = null;
        pattern = List.of();
        blockMapping = Map.of();
        buildingId = "";
        displayName = "";
        category = "basic";
        comfort = 0; magic = 0; wonder = 0;
        queueCapacity = 5;
        taskTypes.clear(); taskTypes.add("building");
        unlockMinComfort = 0; unlockMinMagic = 0; unlockMinWonder = 0;
        maintenanceIntervalTicks = MaintenanceCostConfig.DEFAULT_INTERVAL_TICKS;
        maintenanceCosts.clear();
        blueprintId = "build:clear_and_build";
        blueprintBind.clear();
        interactionRadius = 0;
        shopGoods.clear();
        shopProfitRate = 0.2;
        serviceEnergyPerUse = 20; serviceSatisfactionPerUse = 15;
        serviceElementOutput.clear(); serviceMaxOccupancy = 0;
        decorationRadius = 0;
        wonderEffects.clear();
        nodeElement = ""; nodeAmountPerHarvest = 5; nodeChannelTicks = 100; nodeManaCost = 10;
        nodeBlueprint = "node:gather";
        hoveredAxis = null; hoveredAxisWorld = null;
        draggingAxis = null; dragStartWorld = null;
        dragStartMin = null; dragStartMax = null;
        showPreview = false;
        previewJson = "";
        savedAbilities = null;
        LOGGER.info("[BuildEditor] Exited edit mode");
    }

    // ── World anchor ──

    public static BlockPos getWorldAnchor() { return worldAnchor; }
    public static void setWorldAnchor(BlockPos pos) { worldAnchor = pos; }

    // ── Anchor offset ──

    public static BlockOffset getAnchorOffset() { return anchorOffset; }
    public static void setAnchorOffset(BlockOffset offset) { anchorOffset = offset; }

    // ── AABB ──

    public static BlockOffset getEditMin() { return editMin; }
    public static void setEditMin(BlockOffset min) { editMin = min; }

    public static BlockOffset getEditMax() { return editMax; }
    public static void setEditMax(BlockOffset max) { editMax = max; }

    /** Return true if both min and max are set. */
    public static boolean hasAABB() { return editMin != null && editMax != null; }

    /** Return the world-space min corner of the AABB. */
    public static BlockPos getWorldMin() {
        if (worldAnchor == null || editMin == null) return null;
        return worldAnchor.offset(editMin.x(), editMin.y(), editMin.z());
    }

    /** Return the world-space max corner of the AABB. */
    public static BlockPos getWorldMax() {
        if (worldAnchor == null || editMax == null) return null;
        return worldAnchor.offset(editMax.x(), editMax.y(), editMax.z());
    }

    // ── Pattern ──

    public static List<BlockOffset> getPattern() { return pattern; }
    public static void setPattern(List<BlockOffset> p) { pattern = List.copyOf(p); }

    // ── Block mapping ──

    public static Map<String, String> getBlockMapping() { return blockMapping; }
    public static void setBlockMapping(Map<String, String> bm) { blockMapping = Map.copyOf(bm); }

    // ── Metadata ──

    public static String getBuildingId() { return buildingId; }
    public static void setBuildingId(String id) { buildingId = id; }

    public static String getDisplayName() { return displayName; }
    public static void setDisplayName(String name) { displayName = name; }

    public static String getCategory() { return category; }
    public static void setCategory(String cat) { category = cat; }

    // ── Three values ──

    public static int getComfort() { return comfort; }
    public static void setComfort(int v) { comfort = v; }
    public static int getMagic() { return magic; }
    public static void setMagic(int v) { magic = v; }
    public static int getWonder() { return wonder; }
    public static void setWonder(int v) { wonder = v; }

    // ── Queue ──

    public static int getQueueCapacity() { return queueCapacity; }
    public static void setQueueCapacity(int v) { queueCapacity = v; }
    public static List<String> getTaskTypes() { return List.copyOf(taskTypes); }
    public static void setTaskTypes(List<String> types) {
        synchronized (taskTypes) { taskTypes.clear(); taskTypes.addAll(types); }
    }

    // ── Unlock ──

    public static int getUnlockMinComfort() { return unlockMinComfort; }
    public static void setUnlockMinComfort(int v) { unlockMinComfort = v; }
    public static int getUnlockMinMagic() { return unlockMinMagic; }
    public static void setUnlockMinMagic(int v) { unlockMinMagic = v; }
    public static int getUnlockMinWonder() { return unlockMinWonder; }
    public static void setUnlockMinWonder(int v) { unlockMinWonder = v; }

    // ── Maintenance ──

    public static int getMaintenanceIntervalTicks() { return maintenanceIntervalTicks; }
    public static void setMaintenanceIntervalTicks(int v) { maintenanceIntervalTicks = v; }
    public static Map<ElementType, Long> getMaintenanceCosts() {
        synchronized (maintenanceCosts) { return Map.copyOf(maintenanceCosts); }
    }
    public static void setMaintenanceCosts(Map<ElementType, Long> costs) {
        synchronized (maintenanceCosts) { maintenanceCosts.clear(); maintenanceCosts.putAll(costs); }
    }
    public static void addMaintenanceCost(ElementType element, long amount) {
        synchronized (maintenanceCosts) { maintenanceCosts.put(element, amount); }
    }
    public static void removeMaintenanceCost(ElementType element) {
        synchronized (maintenanceCosts) { maintenanceCosts.remove(element); }
    }

    // ── Blueprint ──

    public static String getBlueprintId() { return blueprintId; }
    public static void setBlueprintId(String id) { blueprintId = id; }
    public static Map<String, String> getBlueprintBind() {
        synchronized (blueprintBind) { return Map.copyOf(blueprintBind); }
    }
    public static void setBlueprintBind(Map<String, String> bind) {
        synchronized (blueprintBind) { blueprintBind.clear(); blueprintBind.putAll(bind); }
    }

    // ── Interaction radius ──

    public static int getInteractionRadius() { return interactionRadius; }
    public static void setInteractionRadius(int v) { interactionRadius = v; }

    // ── Shop ──

    public static List<ShopGoodDef> getShopGoods() {
        synchronized (shopGoods) { return List.copyOf(shopGoods); }
    }
    public static void setShopGoods(List<ShopGoodDef> goods) {
        synchronized (shopGoods) { shopGoods.clear(); shopGoods.addAll(goods); }
    }
    public static void addShopGood(ShopGoodDef good) {
        synchronized (shopGoods) { shopGoods.add(good); }
    }
    public static void removeShopGood(int index) {
        synchronized (shopGoods) { if (index >= 0 && index < shopGoods.size()) shopGoods.remove(index); }
    }
    public static double getShopProfitRate() { return shopProfitRate; }
    public static void setShopProfitRate(double v) { shopProfitRate = v; }

    // ── Service ──

    public static int getServiceEnergyPerUse() { return serviceEnergyPerUse; }
    public static void setServiceEnergyPerUse(int v) { serviceEnergyPerUse = v; }
    public static int getServiceSatisfactionPerUse() { return serviceSatisfactionPerUse; }
    public static void setServiceSatisfactionPerUse(int v) { serviceSatisfactionPerUse = v; }
    public static Map<ElementType, Long> getServiceElementOutput() {
        synchronized (serviceElementOutput) { return Map.copyOf(serviceElementOutput); }
    }
    public static void setServiceElementOutput(Map<ElementType, Long> output) {
        synchronized (serviceElementOutput) { serviceElementOutput.clear(); serviceElementOutput.putAll(output); }
    }
    public static int getServiceMaxOccupancy() { return serviceMaxOccupancy; }
    public static void setServiceMaxOccupancy(int v) { serviceMaxOccupancy = v; }

    // ── Decoration ──

    public static int getDecorationRadius() { return decorationRadius; }
    public static void setDecorationRadius(int v) { decorationRadius = v; }

    // ── Wonder ──

    public static List<WonderEffect> getWonderEffects() {
        synchronized (wonderEffects) { return List.copyOf(wonderEffects); }
    }
    public static void setWonderEffects(List<WonderEffect> effects) {
        synchronized (wonderEffects) { wonderEffects.clear(); wonderEffects.addAll(effects); }
    }

    // ── Node ──

    public static String getNodeElement() { return nodeElement; }
    public static void setNodeElement(String v) { nodeElement = v; }
    public static int getNodeAmountPerHarvest() { return nodeAmountPerHarvest; }
    public static void setNodeAmountPerHarvest(int v) { nodeAmountPerHarvest = v; }
    public static int getNodeChannelTicks() { return nodeChannelTicks; }
    public static void setNodeChannelTicks(int v) { nodeChannelTicks = v; }
    public static int getNodeManaCost() { return nodeManaCost; }
    public static void setNodeManaCost(int v) { nodeManaCost = v; }
    public static String getNodeBlueprint() { return nodeBlueprint; }
    public static void setNodeBlueprint(String v) { nodeBlueprint = v; }

    // ── Body anchor ──

    public static BlockPos getBodyAnchor() { return bodyAnchor; }

    // ── Axis hover/drag ──

    public static AxisDrag getHoveredAxis() { return hoveredAxis; }
    public static void setHoveredAxis(AxisDrag axis) { hoveredAxis = axis; }
    public static Vec3 getHoveredAxisWorld() { return hoveredAxisWorld; }
    public static void setHoveredAxisWorld(Vec3 pos) { hoveredAxisWorld = pos; }
    public static AxisDrag getDraggingAxis() { return draggingAxis; }
    public static void setDraggingAxis(AxisDrag axis) { draggingAxis = axis; }
    public static BlockPos getDragStartWorld() { return dragStartWorld; }
    public static void setDragStartWorld(BlockPos pos) { dragStartWorld = pos; }
    public static BlockOffset getDragStartMin() { return dragStartMin; }
    public static void setDragStartMin(BlockOffset off) { dragStartMin = off; }
    public static BlockOffset getDragStartMax() { return dragStartMax; }
    public static void setDragStartMax(BlockOffset off) { dragStartMax = off; }
    public static boolean isDragging() { return draggingAxis != null; }

    // ── Preview (used by ImGui panel) ──

    public static boolean isShowPreview() { return showPreview; }
    public static void setShowPreview(boolean v) { showPreview = v; }
    public static String getPreviewJson() { return previewJson; }
    public static void setPreviewJson(String json) { previewJson = json; }

    // ═══════════════════════════════════════════════════════════════
    // ── Utility: Build a BuildingConfig JSON string for export ──
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build a complete BuildingConfig JSON string from the current state.
     * This is sent to the server for validation + file write.
     */
    public static String buildExportJson() {
        // Pure manual JSON building to avoid Gson dependency on client for this
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"id\": \"").append(escapeJson(buildingId)).append("\",\n");
        sb.append("  \"display_name\": \"").append(escapeJson(displayName)).append("\",\n");
        sb.append("  \"category\": \"").append(escapeJson(category)).append("\",\n");

        // Pattern
        sb.append("  \"pattern\": [\n");
        List<BlockOffset> pat = pattern;
        for (int i = 0; i < pat.size(); i++) {
            BlockOffset off = pat.get(i);
            sb.append("    [").append(off.x()).append(", ").append(off.y()).append(", ").append(off.z()).append("]");
            if (i < pat.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");

        // Block mapping
        sb.append("  \"block_mapping\": {\n");
        Map<String, String> bm = blockMapping;
        List<String> keys = new ArrayList<>(bm.keySet());
        java.util.Collections.sort(keys);
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            sb.append("    \"").append(key).append("\": \"").append(escapeJson(bm.get(key))).append("\"");
            if (i < keys.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  },\n");

        // Three values
        sb.append("  \"comfort\": ").append(comfort).append(",\n");
        sb.append("  \"magic\": ").append(magic).append(",\n");
        sb.append("  \"wonder\": ").append(wonder).append(",\n");

        // Queue
        sb.append("  \"queue\": {\n");
        sb.append("    \"capacity\": ").append(queueCapacity).append(",\n");
        sb.append("    \"task_types\": [");
        List<String> tt = getTaskTypes();
        for (int i = 0; i < tt.size(); i++) {
            sb.append("\"").append(escapeJson(tt.get(i))).append("\"");
            if (i < tt.size() - 1) sb.append(", ");
        }
        sb.append("]\n  },\n");

        // Unlock requirement
        sb.append("  \"unlock_requirement\": {\n");
        sb.append("    \"min_comfort\": ").append(unlockMinComfort).append(",\n");
        sb.append("    \"min_magic\": ").append(unlockMinMagic).append(",\n");
        sb.append("    \"min_wonder\": ").append(unlockMinWonder).append("\n");
        sb.append("  },\n");

        // Boundary
        if (editMin != null && editMax != null) {
            sb.append("  \"boundary\": {\n");
            sb.append("    \"min\": [").append(editMin.x()).append(", ").append(editMin.y()).append(", ").append(editMin.z()).append("],\n");
            sb.append("    \"max\": [").append(editMax.x()).append(", ").append(editMax.y()).append(", ").append(editMax.z()).append("]\n");
            sb.append("  },\n");
        }

        // Maintenance cost (only if non-empty)
        Map<ElementType, Long> mc = getMaintenanceCosts();
        if (!mc.isEmpty()) {
            sb.append("  \"maintenance_cost\": {\n");
            sb.append("    \"interval_ticks\": ").append(maintenanceIntervalTicks).append(",\n");
            sb.append("    \"costs\": {");
            List<ElementType> mcKeys = new ArrayList<>(mc.keySet());
            for (int i = 0; i < mcKeys.size(); i++) {
                ElementType et = mcKeys.get(i);
                sb.append("\"").append(et.getId()).append("\": ").append(mc.get(et));
                if (i < mcKeys.size() - 1) sb.append(", ");
            }
            sb.append("}\n  },\n");
        }

        // Blueprint
        if (blueprintId != null && !blueprintId.isEmpty()) {
            sb.append("  \"blueprint\": {\n");
            sb.append("    \"id\": \"").append(escapeJson(blueprintId)).append("\"");
            Map<String, String> bb = getBlueprintBind();
            // Default bind when none set — matches standard building conventions
            if (bb.isEmpty()) {
                bb = Map.of(
                    "offsets", "$pattern",
                    "blocks", "$block_mapping",
                    "name", "$display_name"
                );
            }
            sb.append(",\n    \"bind\": {");
            List<String> bbKeys = new ArrayList<>(bb.keySet());
            for (int i = 0; i < bbKeys.size(); i++) {
                String k = bbKeys.get(i);
                sb.append("\"").append(escapeJson(k)).append("\": \"").append(escapeJson(bb.get(k))).append("\"");
                if (i < bbKeys.size() - 1) sb.append(", ");
            }
            sb.append("}");
            sb.append("\n  },\n");
        }

        // Interaction radius
        if (interactionRadius > 0) {
            sb.append("  \"interaction_radius\": ").append(interactionRadius).append(",\n");
        }

        // Category-specific configs
        switch (category) {
            case "shop" -> {
                sb.append("  \"shop\": {\n");
                sb.append("    \"goods\": [\n");
                List<ShopGoodDef> goods = getShopGoods();
                for (int i = 0; i < goods.size(); i++) {
                    ShopGoodDef g = goods.get(i);
                    sb.append("      {\n");
                    sb.append("        \"item_id\": \"").append(escapeJson(g.itemId())).append("\"");
                    if (g.comfort() != 0) sb.append(",\n        \"comfort\": ").append(g.comfort());
                    if (g.magic() != 0) sb.append(",\n        \"magic\": ").append(g.magic());
                    if (g.wonder() != 0) sb.append(",\n        \"wonder\": ").append(g.wonder());
                    Map<ElementType, Integer> rc = g.restockCost();
                    if (rc != null && !rc.isEmpty()) {
                        sb.append(",\n        \"restock_cost\": {");
                        List<ElementType> rcKeys = new ArrayList<>(rc.keySet());
                        for (int j = 0; j < rcKeys.size(); j++) {
                            ElementType et = rcKeys.get(j);
                            sb.append("\"").append(et.getId()).append("\": ").append(rc.get(et));
                            if (j < rcKeys.size() - 1) sb.append(", ");
                        }
                        sb.append("}");
                    }
                    sb.append("\n      }");
                    if (i < goods.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append("    ],\n");
                sb.append("    \"profit_rate\": ").append(shopProfitRate).append("\n");
                sb.append("  },\n");
            }
            case "service" -> {
                sb.append("  \"service\": {\n");
                sb.append("    \"energy_per_use\": ").append(serviceEnergyPerUse).append(",\n");
                sb.append("    \"satisfaction_per_use\": ").append(serviceSatisfactionPerUse).append(",\n");
                sb.append("    \"element_output\": {");
                Map<ElementType, Long> seo = getServiceElementOutput();
                List<ElementType> seoKeys = new ArrayList<>(seo.keySet());
                for (int i = 0; i < seoKeys.size(); i++) {
                    ElementType et = seoKeys.get(i);
                    sb.append("\"").append(et.getId()).append("\": ").append(seo.get(et));
                    if (i < seoKeys.size() - 1) sb.append(", ");
                }
                sb.append("},\n");
                sb.append("    \"max_occupancy\": ").append(serviceMaxOccupancy).append("\n");
                sb.append("  },\n");
            }
            case "decoration" -> {
                sb.append("  \"decoration\": {\n");
                sb.append("    \"radius\": ").append(decorationRadius).append("\n");
                sb.append("  },\n");
            }
            case "wonder" -> {
                sb.append("  \"wonder_config\": {\n");
                sb.append("    \"effects\": [\n");
                List<WonderEffect> effects = getWonderEffects();
                for (int i = 0; i < effects.size(); i++) {
                    WonderEffect e = effects.get(i);
                    sb.append("      {\n");
                    switch (e) {
                        case WonderEffect.StatMod(String target, int value) -> {
                            sb.append("        \"type\": \"stat_mod\"");
                            if (target != null) sb.append(",\n        \"target\": \"").append(escapeJson(target)).append("\"");
                            sb.append(",\n        \"value\": ").append(value);
                        }
                        case WonderEffect.PriceMod(String target, double percentage) -> {
                            sb.append("        \"type\": \"price_mod\"");
                            if (target != null) sb.append(",\n        \"target\": \"").append(escapeJson(target)).append("\"");
                            sb.append(",\n        \"percentage\": ").append(percentage);
                        }
                        case WonderEffect.RuleUnlock(String ruleId) -> {
                            sb.append("        \"type\": \"rule_unlock\"");
                            if (ruleId != null) sb.append(",\n        \"rule_id\": \"").append(escapeJson(ruleId)).append("\"");
                        }
                    }
                    sb.append("\n      }");
                    if (i < effects.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append("    ]\n  },\n");
            }
            case "node" -> {
                sb.append("  \"node_config\": {\n");
                sb.append("    \"element\": \"").append(escapeJson(nodeElement)).append("\",\n");
                sb.append("    \"amount_per_harvest\": ").append(nodeAmountPerHarvest).append(",\n");
                sb.append("    \"channel_ticks\": ").append(nodeChannelTicks).append(",\n");
                sb.append("    \"mana_cost\": ").append(nodeManaCost).append(",\n");
                sb.append("    \"blueprint\": \"").append(escapeJson(nodeBlueprint)).append("\"\n");
                sb.append("  },\n");
            }
        }

        // Remove trailing comma from last field
        // Simple approach: trim last comma+newline, add closing
        String result = sb.toString();
        // Find last comma before closing
        int lastComma = result.lastIndexOf(",\n");
        if (lastComma > 0) {
            result = result.substring(0, lastComma) + "\n" + result.substring(lastComma + 2);
        }
        result += "}\n";
        return result;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Drag handle enum ──
    // ═══════════════════════════════════════════════════════════════

    /**
     * Identifies which axis arrow is being hovered/dragged.
     */
    public enum AxisDrag {
        X_POS, X_NEG, Y_POS, Y_NEG, Z_POS, Z_NEG
    }
}
