package com.wsteam.wandscape.building.editor;

import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.shared.data.ElementType;

import net.minecraft.client.Minecraft;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;
import imgui.type.ImString;

/**
 * ImGui-based editor panel for the building editor.
 * Rendered each frame via {@code ImGuiManager} when the editor is active.
 *
 * <p>ImGui handles all panel input (mouse, keyboard, text, buttons).
 * World interaction (AABB drag handles, block clicking) uses GLFW
 * raw polling in {@link BuildingEditorInputHandler} when ImGui
 * does not capture the mouse.
 */
public final class BuildingEditorImGui {

    // Mutable int wrappers for ImGui inputInt
    private static final ImInt comfort = new ImInt();
    private static final ImInt magic = new ImInt();
    private static final ImInt wonder = new ImInt();
    private static final ImInt unlockComfort = new ImInt();
    private static final ImInt unlockMagic = new ImInt();
    private static final ImInt unlockWonder = new ImInt();
    private static final ImInt queueCapacity = new ImInt();
    private static final ImInt interactionRadius = new ImInt();
    private static final ImInt maintInterval = new ImInt();
    private static final ImInt serviceEnergy = new ImInt();
    private static final ImInt serviceSatisfaction = new ImInt();
    private static final ImInt serviceMaxOccupancy = new ImInt();
    private static final ImInt decorationRadius = new ImInt();
    private static final ImInt nodeAmount = new ImInt();
    private static final ImInt nodeChannel = new ImInt();
    private static final ImInt nodeManaCost = new ImInt();
    private static final ImInt shopProfitPct = new ImInt();

    // String buffers for inputText (ImString is mutable)
    private static final ImString idBuf = new ImString(64);
    private static final ImString nameBuf = new ImString(128);
    private static final ImString blueprintBuf = new ImString(128);
    private static final ImString nodeElementBuf = new ImString(32);
    private static final ImString nodeBlueprintBuf = new ImString(64);

    private static boolean showPreview = false;
    private static String previewJson = "";

    private static final String[] CATEGORIES = {
            "basic", "node", "storage", "workstation", "crafting_station",
            "potion_station", "tavern", "shop", "service", "decoration", "wonder"
    };
    private static final ImInt categoryIdx = new ImInt(0);

    // Flags for collapsing headers
    private static final int HEADER_FLAGS = 0;

    /** Panel left edge in screen coordinates (set each frame). */
    public static float panelLeftEdge = Float.MAX_VALUE;

    private BuildingEditorImGui() {}

    // ═══════════════════════════════════════════════════════════════
    // ── Render entry point ──
    // ═══════════════════════════════════════════════════════════════

    public static void render() {
        syncFromState();

        int flags = ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.AlwaysAutoResize;

        var io = ImGui.getIO();
        float winW = 260;
        float x = io.getDisplaySizeX() - winW - 10;
        panelLeftEdge = x;  // exposed for hit-test gating in Controller
        float y = 8;
        ImGui.setNextWindowPos(x, y, ImGuiCond.Always);
        ImGui.setNextWindowSize(winW, io.getDisplaySizeY() - 16, ImGuiCond.Always);

        if (ImGui.begin("Building Editor", flags)) {
            // ── Basic info ──
            if (ImGui.collapsingHeader("Basic", HEADER_FLAGS)) {
                ImGui.pushItemWidth(-1);
                ImGui.inputText("ID", idBuf);
                ImGui.inputText("Name", nameBuf);
                ImGui.popItemWidth();

                ImGui.combo("Category", categoryIdx, CATEGORIES);

                ImGui.pushItemWidth(-1);
                ImGui.inputText("Blueprint", blueprintBuf);
                ImGui.popItemWidth();
            }

            // ── Three values ──
            if (ImGui.collapsingHeader("Three Values", HEADER_FLAGS)) {
                ImGui.pushItemWidth(100);
                ImGui.inputInt("Comfort", comfort);
                ImGui.sameLine();
                ImGui.inputInt("Magic", magic);
                ImGui.sameLine();
                ImGui.inputInt("Wonder", wonder);
                ImGui.popItemWidth();
            }

            // ── Unlock ──
            if (ImGui.collapsingHeader("Unlock Req.", HEADER_FLAGS)) {
                ImGui.pushItemWidth(80);
                ImGui.inputInt("minC", unlockComfort);
                ImGui.sameLine();
                ImGui.inputInt("minM", unlockMagic);
                ImGui.sameLine();
                ImGui.inputInt("minW", unlockWonder);
                ImGui.popItemWidth();
            }

            // ── Queue & Interaction ──
            if (ImGui.collapsingHeader("Queue & Range", HEADER_FLAGS)) {
                ImGui.pushItemWidth(80);
                ImGui.inputInt("Queue cap", queueCapacity);
                ImGui.sameLine();
                ImGui.inputInt("Interact R", interactionRadius);
                ImGui.popItemWidth();
            }

            // ── Maintenance ──
            if (ImGui.collapsingHeader("Maintenance", HEADER_FLAGS)) {
                ImGui.pushItemWidth(100);
                ImGui.inputInt("Interval (ticks)", maintInterval);
                ImGui.popItemWidth();
            }

            // ── Category-specific ──
            String cat = CATEGORIES[categoryIdx.get()];
            switch (cat) {
                case "shop" -> renderShopSection();
                case "service" -> renderServiceSection();
                case "decoration" -> renderDecorationSection();
                case "wonder" -> renderWonderSection();
                case "node" -> renderNodeSection();
                default -> {}
            }

            // ── AABB info ──
            if (ImGui.collapsingHeader("AABB", HEADER_FLAGS)) {
                if (BuildingEditorClientState.hasAABB()) {
                    BlockOffset min = BuildingEditorClientState.getEditMin();
                    BlockOffset max = BuildingEditorClientState.getEditMax();
                    ImGui.text(String.format("Min: [%d,%d,%d]", min.x(), min.y(), min.z()));
                    ImGui.text(String.format("Max: [%d,%d,%d]", max.x(), max.y(), max.z()));
                    ImGui.text(BuildingEditorClientState.getPattern().size() + " blocks");
                } else {
                    ImGui.textColored(1.0f, 0.7f, 0.3f, 1.0f, "No AABB set");
                }
                if (ImGui.button("Set Anchor (crosshair)")) {
                    BuildingEditorInputHandler.setAnchorAtCrosshair();
                }
                if (BuildingEditorClientState.getWorldAnchor() != null) {
                    ImGui.sameLine();
                    if (ImGui.button("Snap Max (crosshair)")) {
                        BuildingEditorInputHandler.snapMax();
                    }
                }
                if (BuildingEditorClientState.getWorldAnchor() != null) {
                    ImGui.sameLine();
                    if (ImGui.button("Scan")) {
                        BuildingEditorInputHandler.scanBlocks(
                                Minecraft.getInstance());
                    }
                }
                ImGui.textDisabled("L-click=Anchor  Drag axes=AABB  M-click=+/-block  R-hold=camera");
            }

            ImGui.separator();

            // ── Buttons ──
            if (ImGui.button("Export JSON")) {
                BuildingEditorController.doExport();
            }
            ImGui.sameLine();
            if (ImGui.button("Preview")) {
                showPreview = !showPreview;
                if (showPreview) {
                    previewJson = BuildingEditorClientState.buildExportJson();
                }
            }
            ImGui.sameLine();
            if (ImGui.button("Exit")) {
                BuildingEditorController.doExit();
            }
        }
        ImGui.end();

        // ── JSON Preview floating window ──
        if (showPreview) {
            ImGui.setNextWindowPos(10, 20, ImGuiCond.FirstUseEver);
            ImGui.setNextWindowSize(600, 400, ImGuiCond.FirstUseEver);
            if (ImGui.begin("JSON Preview", ImGuiWindowFlags.NoCollapse)) {
                ImGui.beginChild("##jsonScroll", 0, -35, true);
                String[] lines = previewJson.split("\n");
                for (String line : lines) {
                    ImGui.text(line);
                }
                ImGui.endChild();
                if (ImGui.button("Close")) {
                    showPreview = false;
                }
            }
            ImGui.end();
        }

        syncToState();
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Category sections ──
    // ═══════════════════════════════════════════════════════════════

    private static void renderShopSection() {
        if (ImGui.collapsingHeader("Shop Config", HEADER_FLAGS)) {
            ImGui.pushItemWidth(80);
            ImGui.inputInt("Profit %", shopProfitPct);
            ImGui.popItemWidth();
            var goods = BuildingEditorClientState.getShopGoods();
            ImGui.text(goods.size() + " goods defined");
            if (!goods.isEmpty()) {
                ImGui.beginChild("##shopGoods", 0, 80, true);
                for (var g : goods) {
                    ImGui.text("  " + g.itemId());
                }
                ImGui.endChild();
            }
        }
    }

    private static void renderServiceSection() {
        if (ImGui.collapsingHeader("Service Config", HEADER_FLAGS)) {
            ImGui.pushItemWidth(100);
            ImGui.inputInt("Energy/use", serviceEnergy);
            ImGui.inputInt("Satisfaction", serviceSatisfaction);
            ImGui.inputInt("Max Occupancy", serviceMaxOccupancy);
            ImGui.popItemWidth();
        }
    }

    private static void renderDecorationSection() {
        if (ImGui.collapsingHeader("Decoration Config", HEADER_FLAGS)) {
            ImGui.pushItemWidth(100);
            ImGui.inputInt("Radius", decorationRadius);
            ImGui.popItemWidth();
        }
    }

    private static void renderWonderSection() {
        if (ImGui.collapsingHeader("Wonder Config", HEADER_FLAGS)) {
            var effects = BuildingEditorClientState.getWonderEffects();
            ImGui.text(effects.size() + " effects");
            for (var e : effects) {
                ImGui.textDisabled("  " + e.toString());
            }
        }
    }

    private static void renderNodeSection() {
        if (ImGui.collapsingHeader("Node Config", HEADER_FLAGS)) {
            ImGui.pushItemWidth(-1);
            ImGui.inputText("Element", nodeElementBuf);
            ImGui.inputText("Node BP", nodeBlueprintBuf);
            ImGui.popItemWidth();
            ImGui.pushItemWidth(100);
            ImGui.inputInt("Amount/harvest", nodeAmount);
            ImGui.inputInt("Channel ticks", nodeChannel);
            ImGui.inputInt("Mana cost", nodeManaCost);
            ImGui.popItemWidth();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── State sync ──
    // ═══════════════════════════════════════════════════════════════

    private static void syncFromState() {
        idBuf.set(BuildingEditorClientState.getBuildingId());
        nameBuf.set(BuildingEditorClientState.getDisplayName());
        blueprintBuf.set(BuildingEditorClientState.getBlueprintId());
        nodeElementBuf.set(BuildingEditorClientState.getNodeElement());
        nodeBlueprintBuf.set(BuildingEditorClientState.getNodeBlueprint());

        String cat = BuildingEditorClientState.getCategory();
        categoryIdx.set(Math.max(0, List.of(CATEGORIES).indexOf(cat)));

        comfort.set(BuildingEditorClientState.getComfort());
        magic.set(BuildingEditorClientState.getMagic());
        wonder.set(BuildingEditorClientState.getWonder());

        unlockComfort.set(BuildingEditorClientState.getUnlockMinComfort());
        unlockMagic.set(BuildingEditorClientState.getUnlockMinMagic());
        unlockWonder.set(BuildingEditorClientState.getUnlockMinWonder());

        queueCapacity.set(BuildingEditorClientState.getQueueCapacity());
        interactionRadius.set(BuildingEditorClientState.getInteractionRadius());
        maintInterval.set(BuildingEditorClientState.getMaintenanceIntervalTicks());

        serviceEnergy.set(BuildingEditorClientState.getServiceEnergyPerUse());
        serviceSatisfaction.set(BuildingEditorClientState.getServiceSatisfactionPerUse());
        serviceMaxOccupancy.set(BuildingEditorClientState.getServiceMaxOccupancy());

        decorationRadius.set(BuildingEditorClientState.getDecorationRadius());

        nodeAmount.set(BuildingEditorClientState.getNodeAmountPerHarvest());
        nodeChannel.set(BuildingEditorClientState.getNodeChannelTicks());
        nodeManaCost.set(BuildingEditorClientState.getNodeManaCost());

        shopProfitPct.set((int) (BuildingEditorClientState.getShopProfitRate() * 100));
    }

    private static void syncToState() {
        BuildingEditorClientState.setBuildingId(idBuf.get());
        BuildingEditorClientState.setDisplayName(nameBuf.get());
        BuildingEditorClientState.setBlueprintId(blueprintBuf.get());
        BuildingEditorClientState.setNodeElement(nodeElementBuf.get());
        BuildingEditorClientState.setNodeBlueprint(nodeBlueprintBuf.get());

        if (categoryIdx.get() >= 0 && categoryIdx.get() < CATEGORIES.length) {
            BuildingEditorClientState.setCategory(CATEGORIES[categoryIdx.get()]);
        }

        BuildingEditorClientState.setComfort(comfort.get());
        BuildingEditorClientState.setMagic(magic.get());
        BuildingEditorClientState.setWonder(wonder.get());

        BuildingEditorClientState.setUnlockMinComfort(unlockComfort.get());
        BuildingEditorClientState.setUnlockMinMagic(unlockMagic.get());
        BuildingEditorClientState.setUnlockMinWonder(unlockWonder.get());

        BuildingEditorClientState.setQueueCapacity(queueCapacity.get());
        BuildingEditorClientState.setInteractionRadius(interactionRadius.get());
        BuildingEditorClientState.setMaintenanceIntervalTicks(maintInterval.get());

        BuildingEditorClientState.setServiceEnergyPerUse(serviceEnergy.get());
        BuildingEditorClientState.setServiceSatisfactionPerUse(serviceSatisfaction.get());
        BuildingEditorClientState.setServiceMaxOccupancy(serviceMaxOccupancy.get());

        BuildingEditorClientState.setDecorationRadius(decorationRadius.get());

        BuildingEditorClientState.setNodeAmountPerHarvest(nodeAmount.get());
        BuildingEditorClientState.setNodeChannelTicks(nodeChannel.get());
        BuildingEditorClientState.setNodeManaCost(nodeManaCost.get());

        BuildingEditorClientState.setShopProfitRate(shopProfitPct.get() / 100.0);
    }
}
