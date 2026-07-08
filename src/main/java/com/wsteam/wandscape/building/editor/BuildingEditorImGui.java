package com.wsteam.wandscape.building.editor;

import java.util.List;

import com.wsteam.wandscape.building.data.BlockOffset;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
/**
 * ImGui editor panel. Compact two-column layout. All collapsing
 * sections default to closed so the essential buttons always show.
 */
public final class BuildingEditorImGui {

    // ── Widget wrappers ──
    private static final ImInt comfort = new ImInt();
    private static final ImInt magic = new ImInt();
    private static final ImInt wonder = new ImInt();
    private static final ImInt unlockComfort = new ImInt();
    private static final ImInt unlockMagic = new ImInt();
    private static final ImInt unlockWonder = new ImInt();
    private static final ImInt queueCapacity = new ImInt();
    private static final ImInt interactionRadius = new ImInt();
    private static final ImInt serviceEnergy = new ImInt();
    private static final ImInt serviceMaxOccupancy = new ImInt();
    private static final ImInt decorationRadius = new ImInt();
    private static final ImInt nodeAmount = new ImInt();
    private static final ImInt nodeChannel = new ImInt();
    private static final ImInt nodeManaCost = new ImInt();
    private static final ImInt shopProfitPct = new ImInt();

    private static final ImBoolean autoAnchor = new ImBoolean(true);

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

    /** Panel left edge for hit-test gating. */
    public static float panelLeftEdge = Float.MAX_VALUE;

    private BuildingEditorImGui() {}

    // ═══════════════════════════════════════════════════════════════
    // ── Render ──
    // ═══════════════════════════════════════════════════════════════

    public static void render() {
        syncFromState();

        // NoResize intentionally omitted — user can drag the left edge to resize width
        int flags = ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoMove;

        var io = ImGui.getIO();
        float y = 8;

        // Set initial position/size only once; after that ImGui remembers user resize
        ImGui.setNextWindowPos(io.getDisplaySizeX() - 308, y, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(308, io.getDisplaySizeY() - 16, ImGuiCond.FirstUseEver);

        if (ImGui.begin("Building Editor", flags)) {
            // Read actual dimensions — may differ from initial 308 after user resize
            float winW = ImGui.getWindowWidth();
            panelLeftEdge = ImGui.getWindowPosX();

            // ═══ CRITICAL: always-visible controls (top) ═══
            ImGui.pushItemWidth(-1);
            ImGui.inputText("ID", idBuf);
            ImGui.inputText("Name", nameBuf);
            ImGui.popItemWidth();
            ImGui.combo("Category", categoryIdx, CATEGORIES);

            ImGui.separator();

            // ── Three Values ──
            ImGui.pushItemWidth(60);
            ImGui.inputInt("Comfort", comfort);
            ImGui.sameLine();
            ImGui.inputInt("Magic", magic);
            ImGui.sameLine();
            ImGui.inputInt("Wonder", wonder);
            ImGui.popItemWidth();

            // ── Unlock ──
            ImGui.pushItemWidth(60);
            ImGui.inputInt("Unlock C", unlockComfort);
            ImGui.sameLine();
            ImGui.inputInt("U M", unlockMagic);
            ImGui.sameLine();
            ImGui.inputInt("U W", unlockWonder);
            ImGui.popItemWidth();

            // ── Queue + Radius + Maint ──
            ImGui.pushItemWidth(60);
            ImGui.inputInt("QueueCap", queueCapacity);
            ImGui.sameLine();
            ImGui.inputInt("Radius", interactionRadius);
            ImGui.popItemWidth();

            ImGui.pushItemWidth(-1);
            ImGui.inputText("Blueprint", blueprintBuf);
            ImGui.popItemWidth();

            // ── Category-specific (collapsed) ──
            String cat = CATEGORIES[categoryIdx.get()];
            if ("shop".equals(cat)) {
                if (ImGui.collapsingHeader("Shop Config", 0)) {
                    ImGui.pushItemWidth(60);
                    ImGui.inputInt("Profit%", shopProfitPct);
                    ImGui.popItemWidth();
                    var gs = BuildingEditorClientState.getShopGoods();
                    ImGui.text(gs.size() + " goods");
                }
            } else if ("service".equals(cat)) {
                if (ImGui.collapsingHeader("Service Config", 0)) {
                    ImGui.pushItemWidth(80);
                    ImGui.inputInt("Energy", serviceEnergy);
                    ImGui.inputInt("MaxOcc", serviceMaxOccupancy);
                    ImGui.popItemWidth();
                }
            } else if ("decoration".equals(cat)) {
                if (ImGui.collapsingHeader("Decoration", 0)) {
                    ImGui.pushItemWidth(80);
                    ImGui.inputInt("Radius", decorationRadius);
                    ImGui.popItemWidth();
                }
            } else if ("wonder".equals(cat)) {
                if (ImGui.collapsingHeader("Wonder", 0)) {
                    ImGui.text(BuildingEditorClientState.getWonderEffects().size() + " effects");
                }
            } else if ("node".equals(cat)) {
                if (ImGui.collapsingHeader("Node Config", 0)) {
                    ImGui.pushItemWidth(-1);
                    ImGui.inputText("Element", nodeElementBuf);
                    ImGui.inputText("Node BP", nodeBlueprintBuf);
                    ImGui.popItemWidth();
                    ImGui.pushItemWidth(80);
                    ImGui.inputInt("Amt/harv", nodeAmount);
                    ImGui.sameLine();
                    ImGui.inputInt("ChTick", nodeChannel);
                    ImGui.sameLine();
                    ImGui.inputInt("Mana", nodeManaCost);
                    ImGui.popItemWidth();
                }
            }

            // ═══ AABB status + action buttons (always visible) ═══
            ImGui.separator();
            ImGui.textColored(0.6f, 0.9f, 0.4f, 1.0f, "AABB SELECTION");
            if (BuildingEditorClientState.hasAABB()) {
                BlockOffset mn = BuildingEditorClientState.getEditMin();
                BlockOffset mx = BuildingEditorClientState.getEditMax();
                ImGui.text(String.format("[%d,%d,%d] -> [%d,%d,%d]", mn.x(), mn.y(), mn.z(), mx.x(), mx.y(), mx.z()));
                ImGui.text(BuildingEditorClientState.getPattern().size() + " blocks");
            } else {
                ImGui.textDisabled("No AABB yet");
            }

            ImGui.checkbox("Auto Anchor (bottom-center)", autoAnchor);
            BuildingEditorClientState.setAutoAnchorEnabled(autoAnchor.get());
            if (autoAnchor.get()) BuildingEditorClientState.recalculateAnchor();

            // ── Edit InteractZone toggle ──
            boolean izMode = BuildingEditorClientState.isEditInteractZone();
            if (ImGui.checkbox("Edit InteractZone", izMode)) {
                BuildingEditorClientState.setEditInteractZone(!izMode);
            }
            if (izMode) {
                ImGui.textColored(0.4f, 0.8f, 1.0f, 1.0f, "  Dragging axis = resize zone");
                if (BuildingEditorClientState.hasInteractAABB()) {
                    BlockOffset imn = BuildingEditorClientState.getInteractMin();
                    BlockOffset imx = BuildingEditorClientState.getInteractMax();
                    ImGui.textDisabled(String.format("  zone [%d,%d,%d]→[%d,%d,%d]",
                            imn.x(), imn.y(), imn.z(), imx.x(), imx.y(), imx.z()));
                }
            }

            // Buttons — stacked vertically (width follows panel)
            float btnW = winW - 20;
            if (ImGui.button("Set Anchor (crosshair)", btnW, 24)) {
                BuildingEditorInputHandler.setAnchorAtCrosshair();
            }
            if (ImGui.button("Snap Max (crosshair)", btnW, 24)) {
                BuildingEditorInputHandler.snapMax();
            }
            if (ImGui.button("Scan Blocks", btnW, 24)) {
                BuildingEditorInputHandler.scanNow();
            }

            ImGui.separator();
            if (ImGui.button("Export JSON", btnW, 28)) {
                BuildingEditorController.doExport();
            }
            if (ImGui.button("Export Road Template", btnW, 24)) {
                BuildingEditorController.doExportRoadTemplate();
            }
            if (ImGui.button("Preview JSON", btnW, 22)) {
                showPreview = !showPreview;
                if (showPreview) previewJson = BuildingEditorClientState.buildExportJson();
            }
            if (ImGui.button("Exit Editor", btnW, 28)) {
                BuildingEditorController.doExit();
            }

            ImGui.textDisabled("L-click axis arrow = drag");
            ImGui.textDisabled("R-hold = look  M-click = pattern");
        }
        ImGui.end();

        // ── JSON Preview floating window ──
        if (showPreview) {
            ImGui.setNextWindowPos(10, 20, ImGuiCond.FirstUseEver);
            ImGui.setNextWindowSize(600, 400, ImGuiCond.FirstUseEver);
            if (ImGui.begin("JSON Preview", ImGuiWindowFlags.NoCollapse)) {
                ImGui.beginChild("##jsonScroll", 0, -35, true);
                for (String line : previewJson.split("\n")) {
                    ImGui.text(line);
                }
                ImGui.endChild();
                if (ImGui.button("Close")) showPreview = false;
            }
            ImGui.end();
        }

        syncToState();
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

        serviceEnergy.set(BuildingEditorClientState.getServiceEnergyPerUse());
        serviceMaxOccupancy.set(BuildingEditorClientState.getServiceMaxOccupancy());
        decorationRadius.set(BuildingEditorClientState.getDecorationRadius());
        shopProfitPct.set((int) (BuildingEditorClientState.getShopProfitRate() * 100));

        nodeAmount.set(BuildingEditorClientState.getNodeAmountPerHarvest());
        nodeChannel.set(BuildingEditorClientState.getNodeChannelTicks());
        nodeManaCost.set(BuildingEditorClientState.getNodeManaCost());

        autoAnchor.set(BuildingEditorClientState.isAutoAnchorEnabled());
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
        BuildingEditorClientState.setServiceEnergyPerUse(serviceEnergy.get());
        BuildingEditorClientState.setServiceMaxOccupancy(serviceMaxOccupancy.get());
        BuildingEditorClientState.setDecorationRadius(decorationRadius.get());
        BuildingEditorClientState.setShopProfitRate(shopProfitPct.get() / 100.0);
        BuildingEditorClientState.setNodeAmountPerHarvest(nodeAmount.get());
        BuildingEditorClientState.setNodeChannelTicks(nodeChannel.get());
        BuildingEditorClientState.setNodeManaCost(nodeManaCost.get());
    }
}
