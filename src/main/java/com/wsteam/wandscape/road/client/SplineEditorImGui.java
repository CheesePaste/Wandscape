package com.wsteam.wandscape.road.client;

import java.io.File;

import com.wsteam.wandscape.imgui.ImGuiManager;
import com.wsteam.wandscape.road.core.SplineModel;
import com.wsteam.wandscape.road.core.SplinePoint;
import com.wsteam.wandscape.road.core.SplineVec3;
import com.wsteam.wandscape.shared.log.Log;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImDouble;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Renders the ImGui panel for Spline Road Editor.
 */
public final class SplineEditorImGui {
    private static final String TAG = "SplineEditorImGui";
    public static float panelLeftEdge = 0f;

    private static final ImString templateNameInput = new ImString(64);
    private static final ImDouble globalShiftX = new ImDouble(0.0);
    private static final ImDouble globalShiftY = new ImDouble(0.0);
    private static final ImDouble globalShiftZ = new ImDouble(0.0);

    // Array Generation UI binding
    private static final ImBoolean uiArrayPreview = new ImBoolean(false);
    private static final ImDouble uiStepDistance = new ImDouble(2.0);
    private static final float[] uiOffsetRoll = new float[]{0.0f};
    private static final float[] uiOffsetPitch = new float[]{0.0f};
    private static final float[] uiOffsetYaw = new float[]{0.0f};

    private SplineEditorImGui() {}

    public static void render() {
        Minecraft mc = Minecraft.getInstance();
        var io = ImGui.getIO();
        float y = 0;

        ImGui.setNextWindowPos(io.getDisplaySizeX() - 320, y, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(320, io.getDisplaySizeY(), ImGuiCond.FirstUseEver);

        int flags = ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoMove;

        if (ImGui.begin("Spline Editor Panel", flags)) {
            panelLeftEdge = ImGui.getWindowPosX();
            ImGui.textColored(255, 215, 0, 255, "=== SPLINE ROAD EDITOR ===");
            ImGui.separator();

            SplineModel model = SplineEditorClientState.getModel();

            // ── Mode selection ──
            ImGui.text("Editor Mode:");
            boolean isAdd = SplineEditorClientState.getEditMode() == SplineEditorClientState.EditMode.ADD;
            if (ImGui.radioButton("Add Point Mode (Left-Click Surface)", isAdd)) {
                SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.ADD);
            }
            if (ImGui.radioButton("Select & Edit Mode (Drag Handles)", !isAdd)) {
                SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.EDIT);
            }

            ImGui.spacing();
            ImGui.separator();

            // ── Spline global properties ──
            ImGui.text("Curve Configuration:");
            ImBoolean closed = new ImBoolean(model.isClosed());
            if (ImGui.checkbox("Closed Loop", closed)) {
                model.setClosed(closed.get());
            }

            ImGui.spacing();
            ImGui.separator();

            // ── Global Transformation ──
            ImGui.text("Global Translation:");
            ImGui.alignTextToFramePadding();
            ImGui.pushItemWidth(60);
            ImGui.inputDouble("##ShiftX", globalShiftX, 0.1, 1.0, "%.1f");
            ImGui.sameLine();
            ImGui.inputDouble("##ShiftY", globalShiftY, 0.1, 1.0, "%.1f");
            ImGui.sameLine();
            ImGui.inputDouble("##ShiftZ", globalShiftZ, 0.1, 1.0, "%.1f");
            ImGui.popItemWidth();
            
            ImGui.sameLine();
            if (ImGui.button("Shift All")) {
                SplineVec3 delta = new SplineVec3(globalShiftX.get(), globalShiftY.get(), globalShiftZ.get());
                model.translateAll(delta);
                globalShiftX.set(0.0);
                globalShiftY.set(0.0);
                globalShiftZ.set(0.0);
                Log.info(TAG, "[SplineEditor] Translated all points by {}", delta);
            }

            ImGui.spacing();
            ImGui.separator();

            // ── List of points ──
            ImGui.text("Point List (Total: " + model.getPoints().size() + "):");
            if (ImGui.beginChild("PointsList", 0, 150, true)) {
                for (int i = 0; i < model.getPoints().size(); i++) {
                    SplinePoint pt = model.getPoints().get(i);
                    SplineVec3 anchor = pt.getAnchor();
                    String label = String.format("#%d: (%.1f, %.1f, %.1f)", i, anchor.x(), anchor.y(), anchor.z());
                    
                    boolean isSelected = SplineEditorClientState.getSelectedPointIndex() == i;
                    if (ImGui.selectable(label, isSelected)) {
                        SplineEditorClientState.setSelectedPoint(i, SplineEditorClientState.SelectionType.ANCHOR);
                        SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.EDIT);
                    }
                }
            }
            ImGui.endChild();

            // ── Details of selected point ──
            int selectedIdx = SplineEditorClientState.getSelectedPointIndex();
            SplineEditorClientState.SelectionType selectedType = SplineEditorClientState.getSelectedType();

            if (selectedIdx != -1 && selectedIdx < model.getPoints().size()) {
                SplinePoint pt = model.getPoints().get(selectedIdx);
                ImGui.spacing();
                ImGui.textColored(100, 200, 255, 255, "Selected Point #" + selectedIdx);
                
                // Show/toggle sub-selections
                ImGui.text("Active Handle:");
                if (ImGui.radioButton("Anchor", selectedType == SplineEditorClientState.SelectionType.ANCHOR)) {
                    SplineEditorClientState.setSelectedPoint(selectedIdx, SplineEditorClientState.SelectionType.ANCHOR);
                }
                ImGui.sameLine();
                if (ImGui.radioButton("Prev Handle", selectedType == SplineEditorClientState.SelectionType.CONTROL_PREV)) {
                    SplineEditorClientState.setSelectedPoint(selectedIdx, SplineEditorClientState.SelectionType.CONTROL_PREV);
                }
                ImGui.sameLine();
                if (ImGui.radioButton("Next Handle", selectedType == SplineEditorClientState.SelectionType.CONTROL_NEXT)) {
                    SplineEditorClientState.setSelectedPoint(selectedIdx, SplineEditorClientState.SelectionType.CONTROL_NEXT);
                }

                // Coordinate input for selected sub-part
                SplineVec3 targetPos = switch (selectedType) {
                    case ANCHOR -> pt.getAnchor();
                    case CONTROL_PREV -> pt.getControlPrev();
                    case CONTROL_NEXT -> pt.getControlNext();
                    default -> null;
                };

                if (targetPos != null) {
                    ImDouble px = new ImDouble(targetPos.x());
                    ImDouble py = new ImDouble(targetPos.y());
                    ImDouble pz = new ImDouble(targetPos.z());

                    ImGui.text("Coordinates:");
                    ImGui.pushItemWidth(75);
                    boolean mx = ImGui.inputDouble("X##Coord", px, 0.05, 0.5, "%.2f");
                    boolean my = ImGui.inputDouble("Y##Coord", py, 0.05, 0.5, "%.2f");
                    boolean mz = ImGui.inputDouble("Z##Coord", pz, 0.05, 0.5, "%.2f");
                    ImGui.popItemWidth();

                    if (mx || my || mz) {
                        SplineVec3 updated = new SplineVec3(px.get(), py.get(), pz.get());
                        switch (selectedType) {
                            case ANCHOR -> pt.setAnchor(updated);
                            case CONTROL_PREV -> pt.setControlPrev(updated);
                            case CONTROL_NEXT -> pt.setControlNext(updated);
                        }
                    }
                }

                // Symmetrical locks
                ImBoolean locked = new ImBoolean(pt.isLocked());
                if (ImGui.checkbox("Symmetric Locked", locked)) {
                    pt.setLocked(locked.get());
                }
                
                if (!pt.isLocked()) {
                    ImGui.sameLine();
                    if (ImGui.button("Align Symmetric")) {
                        pt.setLocked(true);
                    }
                }

                ImGui.spacing();
                if (ImGui.button("Delete Selected Point")) {
                    model.removePoint(selectedIdx);
                    SplineEditorClientState.setSelectedPoint(-1, SplineEditorClientState.SelectionType.NONE);
                }
            }

            ImGui.spacing();
            ImGui.separator();

            // ── Array Generation Config ──
            ImGui.textColored(100, 255, 100, 255, "Array Generation:");
            
            java.util.List<String> templateIds = SplineEditorClientState.getAvailableTemplateIds();
            if (!templateIds.isEmpty()) {
                String currentId = SplineEditorClientState.getActiveTemplateId();
                int idx = templateIds.indexOf(currentId);
                if (idx < 0) idx = 0;
                
                imgui.type.ImInt activeTemplateIdx = new imgui.type.ImInt(idx);
                String[] templateArray = templateIds.toArray(new String[0]);
                if (ImGui.combo("Blueprint", activeTemplateIdx, templateArray)) {
                    SplineEditorClientState.setActiveTemplateId(templateArray[activeTemplateIdx.get()]);
                }
            } else {
                ImGui.textDisabled("No blueprints available.");
            }
            
            uiArrayPreview.set(SplineEditorClientState.isArrayPreview());
            if (ImGui.checkbox("Enable Array Preview", uiArrayPreview)) {
                SplineEditorClientState.setArrayPreview(uiArrayPreview.get());
            }

            if (SplineEditorClientState.isArrayPreview()) {
                uiStepDistance.set(SplineEditorClientState.getArrayStepDistance());
                ImGui.pushItemWidth(100);
                if (ImGui.inputDouble("Step Distance", uiStepDistance, 0.5, 2.0, "%.2f")) {
                    SplineEditorClientState.setArrayStepDistance(Math.max(0.1, uiStepDistance.get()));
                }
                ImGui.popItemWidth();

                uiOffsetRoll[0] = (float) SplineEditorClientState.getArrayOffsetRoll();
                uiOffsetPitch[0] = (float) SplineEditorClientState.getArrayOffsetPitch();
                uiOffsetYaw[0] = (float) SplineEditorClientState.getArrayOffsetYaw();

                boolean rotChanged = false;
                ImGui.pushItemWidth(150);
                rotChanged |= ImGui.sliderFloat("Roll", uiOffsetRoll, -180.0f, 180.0f, "%.1f deg");
                rotChanged |= ImGui.sliderFloat("Pitch", uiOffsetPitch, -180.0f, 180.0f, "%.1f deg");
                rotChanged |= ImGui.sliderFloat("Yaw", uiOffsetYaw, -180.0f, 180.0f, "%.1f deg");
                ImGui.popItemWidth();

                if (rotChanged) {
                    SplineEditorClientState.setArrayOffsetRoll(uiOffsetRoll[0]);
                    SplineEditorClientState.setArrayOffsetPitch(uiOffsetPitch[0]);
                    SplineEditorClientState.setArrayOffsetYaw(uiOffsetYaw[0]);
                }
            }

            ImGui.spacing();
            ImGui.separator();

            // ── Save and Load Templates ──
            ImGui.text("Save/Load Templates:");
            ImGui.inputText("Name", templateNameInput);
            
            if (ImGui.button("Save To JSON")) {
                String name = templateNameInput.get().trim();
                if (!name.isEmpty()) {
                    SplineEditorClientState.saveTemplate(name);
                }
            }

            ImGui.sameLine();
            if (ImGui.button("Load From JSON")) {
                String name = templateNameInput.get().trim();
                if (!name.isEmpty()) {
                    Vec3 pos = mc.player.position();
                    SplineVec3 placementOrigin = new SplineVec3(pos.x, pos.y, pos.z);
                    SplineEditorClientState.loadTemplate(name, placementOrigin);
                }
            }

            ImGui.spacing();
            ImGui.separator();
            
            if (ImGui.button("Clear Canvas")) {
                model.clear();
                SplineEditorClientState.setSelectedPoint(-1, SplineEditorClientState.SelectionType.NONE);
            }

            ImGui.sameLine();
            if (ImGui.button("Close Editor")) {
                SplineEditorClientState.exitEditMode();
                ImGuiManager.toggle();
            }
        }
        ImGui.end();
    }
}
