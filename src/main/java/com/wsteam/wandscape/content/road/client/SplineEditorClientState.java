package com.wsteam.wandscape.road.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wsteam.wandscape.content.colony.overview.client.OverviewClientState;
import com.wsteam.wandscape.road.core.SplineModel;
import com.wsteam.wandscape.road.core.SplinePoint;
import com.wsteam.wandscape.road.core.SplineVec3;
import com.wsteam.wandscape.shared.log.Log;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Global static client state for the Spline Road Editor.
 */
public final class SplineEditorClientState {
    private static final String TAG = "SplineEditorClientState";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum EditMode { ADD, EDIT }
    public enum SelectionType { NONE, ANCHOR, CONTROL_PREV, CONTROL_NEXT }
    
    public enum AxisDrag {
        NONE,
        X_POS, X_NEG,
        Y_POS, Y_NEG,
        Z_POS, Z_NEG
    }

    private static volatile boolean editing = false;
    private static volatile EditMode editMode = EditMode.ADD;
    private static final SplineModel model = new SplineModel();

    // ── Freecam Camera State ──
    private static double camX, camY, camZ;
    private static float camYaw, camPitch;

    // ── Top-Down (bird's eye) view state ──
    private static volatile boolean topDown = false;
    private static double topDownPrevX, topDownPrevY, topDownPrevZ;
    private static float topDownPrevYaw, topDownPrevPitch;
    private static double lastMouseX, lastMouseY;

    public static double getCamX() { return camX; }
    public static double getCamY() { return camY; }
    public static double getCamZ() { return camZ; }
    public static float getCamYaw() { return camYaw; }
    public static float getCamPitch() { return camPitch; }
    public static void setCamPosition(double x, double y, double z) {
        camX = x; camY = y; camZ = z;
    }
    public static void setCamRotation(float yaw, float pitch) {
        camYaw = yaw;
        camPitch = pitch;
    }

    // ── Top-Down view control ──

    public static boolean isTopDown() {
        return topDown;
    }

    /**
     * Enter bird's eye view, same as the V-panel overview mode:
     * camera jumps 20 blocks above its current position and looks straight down.
     * The previous camera state is saved so exiting restores it.
     */
    public static void enterTopDown() {
        if (topDown) return;
        topDownPrevX = camX;
        topDownPrevY = camY;
        topDownPrevZ = camZ;
        topDownPrevYaw = camYaw;
        topDownPrevPitch = camPitch;
        camY += 20;
        camPitch = 90;
        // Reset mouse baseline so the grab transition does not snap the camera
        Minecraft mc = Minecraft.getInstance();
        long window = mc.getWindow().getWindow();
        double[] mx = new double[1], my = new double[1];
        GLFW.glfwGetCursorPos(window, mx, my);
        lastMouseX = mx[0];
        lastMouseY = my[0];
        topDown = true;
        Log.info(TAG, "[SplineEditor] Top-down view enabled");
    }

    public static void exitTopDown() {
        if (!topDown) return;
        topDown = false;
        camX = topDownPrevX;
        camY = topDownPrevY;
        camZ = topDownPrevZ;
        camYaw = topDownPrevYaw;
        camPitch = topDownPrevPitch;
        Log.info(TAG, "[SplineEditor] Top-down view disabled");
    }

    public static double getLastMouseX() {
        return lastMouseX;
    }

    public static double getLastMouseY() {
        return lastMouseY;
    }

    public static void setLastMouse(double x, double y) {
        lastMouseX = x;
        lastMouseY = y;
    }

    public static void addCamRotation(float yawDelta, float pitchDelta) {
        camYaw += yawDelta;
        camPitch += pitchDelta;
        if (camPitch > 90) camPitch = 90;
        if (camPitch < -90) camPitch = -90;
    }

    // Selection
    private static volatile int selectedPointIndex = -1;
    private static volatile SelectionType selectedType = SelectionType.NONE;

    // Gizmo dragging state
    private static volatile AxisDrag hoveredAxis = AxisDrag.NONE;
    private static volatile AxisDrag draggingAxis = AxisDrag.NONE;

    public enum TemplateSourceMode { VPANEL_PRESET, JSON_FILE }

    private static volatile TemplateSourceMode templateSourceMode = TemplateSourceMode.VPANEL_PRESET;
    private static volatile int dynamicWidth = 5;
    private static volatile int dynamicDepth = 1;
    private static volatile boolean dynamicHasBorder = false;

    // Array Generation / Preview state
    private static volatile boolean arrayPreview = false;
    private static volatile double arrayStepDistance = 2.0;
    private static volatile double arrayOffsetRoll = 0.0;
    private static volatile double arrayOffsetPitch = 0.0;
    private static volatile double arrayOffsetYaw = 0.0;
    
    // Registry of loaded/exported road templates
    private static final java.util.Map<String, com.wsteam.wandscape.road.core.RoadTemplate> templateRegistry = new java.util.LinkedHashMap<>();
    private static volatile String activeTemplateId = "vpanel_preset_dynamic";

    static {
        com.wsteam.wandscape.road.core.RoadTemplate testTemplate = new com.wsteam.wandscape.road.core.RoadTemplate("test_road_5x1");
        testTemplate.addBlock(-2, 0, 0, "minecraft:stone_bricks");
        testTemplate.addBlock(-1, 0, 0, "minecraft:stone_bricks");
        testTemplate.addBlock( 0, 0, 0, "minecraft:stone_bricks");
        testTemplate.addBlock( 1, 0, 0, "minecraft:stone_bricks");
        testTemplate.addBlock( 2, 0, 0, "minecraft:stone_bricks");
        templateRegistry.put(testTemplate.getId(), testTemplate);

        rebuildDynamicTemplate();
    }

    public static TemplateSourceMode getTemplateSourceMode() { return templateSourceMode; }
    public static void setTemplateSourceMode(TemplateSourceMode mode) {
        templateSourceMode = mode;
        if (mode == TemplateSourceMode.VPANEL_PRESET) {
            rebuildDynamicTemplate();
        }
    }

    public static int getDynamicWidth() { return dynamicWidth; }
    public static void setDynamicWidth(int width) {
        dynamicWidth = Math.max(1, Math.min(15, width));
        if (templateSourceMode == TemplateSourceMode.VPANEL_PRESET) rebuildDynamicTemplate();
    }

    public static int getDynamicDepth() { return dynamicDepth; }
    public static void setDynamicDepth(int depth) {
        dynamicDepth = Math.max(1, Math.min(3, depth));
        if (templateSourceMode == TemplateSourceMode.VPANEL_PRESET) rebuildDynamicTemplate();
    }

    public static boolean isDynamicHasBorder() { return dynamicHasBorder; }
    public static void setDynamicHasBorder(boolean border) {
        dynamicHasBorder = border;
        if (templateSourceMode == TemplateSourceMode.VPANEL_PRESET) rebuildDynamicTemplate();
    }

    public static void rebuildDynamicTemplate() {
        var preset = RoadPlacementState.getSelectedPreset();
        if (preset == null) return;

        String id = "vpanel_preset_dynamic";
        com.wsteam.wandscape.road.core.RoadTemplate template = new com.wsteam.wandscape.road.core.RoadTemplate(id);

        int width = dynamicWidth;
        int halfW = width / 2;
        int minX = -halfW;
        int maxX = (width % 2 == 0) ? (halfW - 1) : halfW;

        for (int d = 0; d < dynamicDepth; d++) {
            int y = -d;
            for (int x = minX; x <= maxX; x++) {
                boolean isEdge = (x == minX || x == maxX);
                String blockState;
                if (isEdge && dynamicHasBorder && d == 0) {
                    blockState = "minecraft:stone_bricks";
                } else {
                    blockState = preset.pickBlock(x, y);
                }
                template.addBlock(x, y, 0, blockState);
            }
        }

        templateRegistry.put(id, template);
        if (templateSourceMode == TemplateSourceMode.VPANEL_PRESET) {
            activeTemplateId = id;
        }
    }

    private SplineEditorClientState() {}

    public static boolean isEditing() {
        return editing;
    }

    public static void enterEditMode() {
        editing = true;
        editMode = EditMode.ADD;
        selectedPointIndex = -1;
        selectedType = SelectionType.NONE;
        hoveredAxis = AxisDrag.NONE;
        draggingAxis = AxisDrag.NONE;
        SplineEditorController.resetInputState();
        Minecraft mc = Minecraft.getInstance();
        if (OverviewClientState.isActive()) {
            camX = OverviewClientState.getCamX();
            camY = OverviewClientState.getCamY();
            camZ = OverviewClientState.getCamZ();
            camYaw = OverviewClientState.getCamYaw();
            camPitch = OverviewClientState.getCamPitch();
        } else if (mc.player != null) {
            camX = mc.player.getX();
            camY = mc.player.getEyeY(); // better to start at eye level
            camZ = mc.player.getZ();
            camYaw = mc.player.getYRot();
            camPitch = mc.player.getXRot();
        }
        
        Log.info(TAG, "[SplineEditor] Entered edit mode");
    }

    public static void exitEditMode() {
        editing = false;
        selectedPointIndex = -1;
        selectedType = SelectionType.NONE;
        hoveredAxis = AxisDrag.NONE;
        draggingAxis = AxisDrag.NONE;
        if (topDown) exitTopDown();
        SplineEditorController.resetInputState();
        Log.info(TAG, "[SplineEditor] Exited edit mode");
    }

    public static EditMode getEditMode() {
        return editMode;
    }

    public static void setEditMode(EditMode mode) {
        editMode = mode;
    }

    public static SplineModel getModel() {
        return model;
    }

    public static int getSelectedPointIndex() {
        return selectedPointIndex;
    }

    public static SelectionType getSelectedType() {
        return selectedType;
    }

    public static void setSelectedPoint(int index, SelectionType type) {
        selectedPointIndex = index;
        selectedType = type;
        if (type == SelectionType.NONE) {
            selectedPointIndex = -1;
        }
    }

    public static AxisDrag getHoveredAxis() {
        return hoveredAxis;
    }

    public static void setHoveredAxis(AxisDrag axis) {
        hoveredAxis = axis;
    }

    public static AxisDrag getDraggingAxis() {
        return draggingAxis;
    }

    public static void setDraggingAxis(AxisDrag axis) {
        draggingAxis = axis;
    }

    public static boolean isDragging() {
        return draggingAxis != AxisDrag.NONE;
    }

    // ── Array Generation Getters/Setters ──

    public static boolean isArrayPreview() { return arrayPreview; }
    public static void setArrayPreview(boolean preview) { arrayPreview = preview; }

    public static double getArrayStepDistance() { return arrayStepDistance; }
    public static void setArrayStepDistance(double dist) { arrayStepDistance = dist; }

    public static double getArrayOffsetRoll() { return arrayOffsetRoll; }
    public static void setArrayOffsetRoll(double roll) { arrayOffsetRoll = roll; }

    public static double getArrayOffsetPitch() { return arrayOffsetPitch; }
    public static void setArrayOffsetPitch(double pitch) { arrayOffsetPitch = pitch; }

    public static double getArrayOffsetYaw() { return arrayOffsetYaw; }
    public static void setArrayOffsetYaw(double yaw) { arrayOffsetYaw = yaw; }

    public static com.wsteam.wandscape.road.core.RoadTemplate getActiveTemplate() {
        return templateRegistry.get(activeTemplateId);
    }

    public static String getActiveTemplateId() {
        return activeTemplateId;
    }

    public static void setActiveTemplateId(String id) {
        if (templateRegistry.containsKey(id)) {
            activeTemplateId = id;
        }
    }

    public static void registerTemplate(com.wsteam.wandscape.road.core.RoadTemplate template) {
        templateRegistry.put(template.getId(), template);
        activeTemplateId = template.getId();
    }

    public static List<String> getAvailableTemplateIds() {
        return new ArrayList<>(templateRegistry.keySet());
    }

    // ── Serialization DTOs ──

    private static class SplineJsonDto {
        boolean closed;
        List<SplinePointDto> points;
    }

    private static class SplinePointDto {
        double ax, ay, az; // Anchor offset
        double cpx, cpy, cpz; // Prev handle offset
        double cnx, cny, cnz; // Next handle offset
        boolean locked;
    }

    public static File getSplinesDirectory() {
        File runDir = Minecraft.getInstance().gameDirectory;
        File dir = new File(runDir, "config/wandscape/splines");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static void saveTemplate(String filename) {
        if (model.getPoints().isEmpty()) {
            Log.warn(TAG, "[SplineEditor] Cannot save empty spline model");
            return;
        }
        if (!filename.endsWith(".json")) {
            filename += ".json";
        }

        File file = new File(getSplinesDirectory(), filename);
        try {
            // First point acts as absolute origin (0, 0, 0)
            SplineVec3 origin = model.getPoints().get(0).getAnchor();

            SplineJsonDto dto = new SplineJsonDto();
            dto.closed = model.isClosed();
            dto.points = new ArrayList<>();

            for (SplinePoint pt : model.getPoints()) {
                SplinePointDto ptDto = new SplinePointDto();
                
                SplineVec3 relAnchor = pt.getAnchor().subtract(origin);
                SplineVec3 relPrev = pt.getControlPrev().subtract(origin);
                SplineVec3 relNext = pt.getControlNext().subtract(origin);

                ptDto.ax = relAnchor.x();
                ptDto.ay = relAnchor.y();
                ptDto.az = relAnchor.z();
                ptDto.cpx = relPrev.x();
                ptDto.cpy = relPrev.y();
                ptDto.cpz = relPrev.z();
                ptDto.cnx = relNext.x();
                ptDto.cny = relNext.y();
                ptDto.cnz = relNext.z();
                ptDto.locked = pt.isLocked();

                dto.points.add(ptDto);
            }

            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(dto, writer);
            }
            Log.info(TAG, "[SplineEditor] Saved template to {}", file.getAbsolutePath());
        } catch (IOException e) {
            Log.warn(TAG, "[SplineEditor] Failed to save spline template", e);
        }
    }

    public static boolean loadTemplate(String filename, SplineVec3 placementOrigin) {
        if (!filename.endsWith(".json")) {
            filename += ".json";
        }
        File file = new File(getSplinesDirectory(), filename);
        if (!file.exists()) {
            Log.warn(TAG, "[SplineEditor] Template file does not exist: {}", file.getAbsolutePath());
            return false;
        }

        try (FileReader reader = new FileReader(file)) {
            SplineJsonDto dto = GSON.fromJson(reader, SplineJsonDto.class);
            if (dto == null || dto.points == null) return false;

            model.clear();
            model.setClosed(dto.closed);

            for (SplinePointDto ptDto : dto.points) {
                SplineVec3 anchor = new SplineVec3(ptDto.ax, ptDto.ay, ptDto.az).add(placementOrigin);
                SplineVec3 prev = new SplineVec3(ptDto.cpx, ptDto.cpy, ptDto.cpz).add(placementOrigin);
                SplineVec3 next = new SplineVec3(ptDto.cnx, ptDto.cny, ptDto.cnz).add(placementOrigin);
                model.getPoints().add(new SplinePoint(anchor, prev, next, ptDto.locked));
            }

            selectedPointIndex = -1;
            selectedType = SelectionType.NONE;

            Log.info(TAG, "[SplineEditor] Loaded template {} with {} points", filename, model.getPoints().size());
            return true;
        } catch (IOException e) {
            Log.warn(TAG, "[SplineEditor] Failed to load spline template", e);
            return false;
        }
    }
}
