package com.wsteam.wandscape.building.editor;

import java.util.List;

import org.slf4j.Logger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.data.BlockOffset;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * World-space renderer for the building editor.
 * Draws the AABB selection box, block highlights within the pattern,
 * drag handles, and anchor marker — all camera-relative.
 *
 * <p>Registered to {@code RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS},
 * matching {@code RoadEditorRenderer}.
 */
public final class BuildingEditorRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    // AABB colors
    private static final int AABB_R = 0;
    private static final int AABB_G = 220;
    private static final int AABB_B = 80;
    private static final int AABB_A = 100;       // face alpha
    private static final int AABB_LINE_A = 200;  // edge line alpha

    // Overlap AABB colors
    private static final int OVERLAP_R = 220;
    private static final int OVERLAP_G = 40;
    private static final int OVERLAP_B = 40;

    // Pattern block highlight
    private static final int PATTERN_R = 0;
    private static final int PATTERN_G = 200;
    private static final int PATTERN_B = 220;
    private static final int PATTERN_A = 80;

    // Handle colors
    private static final int HANDLE_CORNER_R = 255;
    private static final int HANDLE_CORNER_G = 200;
    private static final int HANDLE_CORNER_B = 50;
    private static final int HANDLE_FACE_R = 180;
    private static final int HANDLE_FACE_G = 180;
    private static final int HANDLE_FACE_B = 180;
    private static final float HANDLE_SIZE = 0.15f;

    // Anchor marker color (gold)
    private static final int ANCHOR_R = 255;
    private static final int ANCHOR_G = 215;
    private static final int ANCHOR_B = 0;
    private static final float ANCHOR_SIZE = 0.3f;

    private static boolean registered = false;
    private static int frameCounter = 0;

    private BuildingEditorRenderer() {}

    // ── Registration ──

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.addListener(RenderLevelStageEvent.class, BuildingEditorRenderer::onRenderLevelStage);
        LOGGER.info("[BuildEditor] Renderer registered");
    }

    // ═══════════════════════════════════════════════════════════════
    // ── World rendering ──
    // ═══════════════════════════════════════════════════════════════

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!BuildingEditorClientState.isEditing()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        frameCounter++;

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        PoseStack.Pose poseEntry = poseStack.last();

        // 1. Render AABB if set
        BlockPos worldMin = BuildingEditorClientState.getWorldMin();
        BlockPos worldMax = BuildingEditorClientState.getWorldMax();
        if (worldMin != null && worldMax != null) {
            renderAABB(bufferSource, poseEntry, worldMin, worldMax);

            // 2. Render pattern block highlights
            renderPatternHighlight(bufferSource, poseEntry);
        }

        // 3. Render drag handles
        if (worldMin != null && worldMax != null && BuildingEditorClientState.hasAABB()) {
            renderHandles(bufferSource, poseEntry, worldMin, worldMax);
        }

        // 4. Render anchor marker
        BlockPos worldAnchor = BuildingEditorClientState.getWorldAnchor();
        if (worldAnchor != null) {
            renderAnchorMarker(bufferSource, poseEntry, worldAnchor);
        }

        poseStack.popPose();
    }

    // ── AABB wireframe + semi-transparent faces ──

    private static void renderAABB(MultiBufferSource.BufferSource bufferSource,
                                    PoseStack.Pose poseEntry,
                                    BlockPos min, BlockPos max) {
        float x0 = min.getX();
        float y0 = min.getY();
        float z0 = min.getZ();
        float x1 = max.getX() + 1.0f;
        float y1 = max.getY() + 1.0f;
        float z1 = max.getZ() + 1.0f;

        int r = AABB_R, g = AABB_G, b = AABB_B;

        // Semi-transparent faces
        VertexConsumer faceVc = bufferSource.getBuffer(RenderType.debugQuads());
        // Bottom (Y-)
        faceVc.addVertex(poseEntry, x0, y0, z0).setColor(r, g, b, AABB_A);
        faceVc.addVertex(poseEntry, x1, y0, z0).setColor(r, g, b, AABB_A);
        faceVc.addVertex(poseEntry, x1, y0, z1).setColor(r, g, b, AABB_A);
        faceVc.addVertex(poseEntry, x0, y0, z1).setColor(r, g, b, AABB_A);
        // Top (Y+)
        faceVc.addVertex(poseEntry, x0, y1, z0).setColor(r, g, b, AABB_A);
        faceVc.addVertex(poseEntry, x0, y1, z1).setColor(r, g, b, AABB_A);
        faceVc.addVertex(poseEntry, x1, y1, z1).setColor(r, g, b, AABB_A);
        faceVc.addVertex(poseEntry, x1, y1, z0).setColor(r, g, b, AABB_A);
        // Front (Z+)
        faceVc.addVertex(poseEntry, x0, y0, z1).setColor(r, g, b, AABB_A);
        faceVc.addVertex(poseEntry, x1, y0, z1).setColor(r, g, b, AABB_A);
        faceVc.addVertex(poseEntry, x1, y1, z1).setColor(r, g, b, AABB_A);
        faceVc.addVertex(poseEntry, x0, y1, z1).setColor(r, g, b, AABB_A);
        // Back (Z-)
        faceVc.addVertex(poseEntry, x0, y0, z0).setColor(r, g, b, AABB_A);
        faceVc.addVertex(poseEntry, x0, y1, z0).setColor(r, g, b, AABB_A);
        faceVc.addVertex(poseEntry, x1, y1, z0).setColor(r, g, b, AABB_A);
        faceVc.addVertex(poseEntry, x1, y0, z0).setColor(r, g, b, AABB_A);
        // Left (X-)
        faceVc.addVertex(poseEntry, x0, y0, z0).setColor(r, g, b, AABB_A);
        faceVc.addVertex(poseEntry, x0, y0, z1).setColor(r, g, b, AABB_A);
        faceVc.addVertex(poseEntry, x0, y1, z1).setColor(r, g, b, AABB_A);
        faceVc.addVertex(poseEntry, x0, y1, z0).setColor(r, g, b, AABB_A);
        // Right (X+)
        faceVc.addVertex(poseEntry, x1, y0, z0).setColor(r, g, b, AABB_A);
        faceVc.addVertex(poseEntry, x1, y1, z0).setColor(r, g, b, AABB_A);
        faceVc.addVertex(poseEntry, x1, y1, z1).setColor(r, g, b, AABB_A);
        faceVc.addVertex(poseEntry, x1, y0, z1).setColor(r, g, b, AABB_A);
        bufferSource.endBatch(RenderType.debugQuads());

        // Wireframe lines
        VertexConsumer lineVc = bufferSource.getBuffer(RenderType.lines());
        drawBoxLines(lineVc, poseEntry, x0, y0, z0, x1, y1, z1, r, g, b, AABB_LINE_A);
        bufferSource.endBatch(RenderType.lines());
    }

    // ── Pattern highlight ──

    private static void renderPatternHighlight(MultiBufferSource.BufferSource bufferSource,
                                                PoseStack.Pose poseEntry) {
        BlockPos worldAnchor = BuildingEditorClientState.getWorldAnchor();
        if (worldAnchor == null) return;

        List<BlockOffset> pattern = BuildingEditorClientState.getPattern();
        if (pattern.isEmpty()) return;

        VertexConsumer lineVc = bufferSource.getBuffer(RenderType.lines());
        int r = PATTERN_R, g = PATTERN_G, b = PATTERN_B;

        for (BlockOffset off : pattern) {
            float bx = worldAnchor.getX() + off.x();
            float by = worldAnchor.getY() + off.y();
            float bz = worldAnchor.getZ() + off.z();
            float ex = bx + 1.0f;
            float ey = by + 1.0f;
            float ez = bz + 1.0f;
            drawBoxLines(lineVc, poseEntry, bx, by, bz, ex, ey, ez, r, g, b, PATTERN_A);
        }
        bufferSource.endBatch(RenderType.lines());
    }

    // ── Drag handles ──

    private static void renderHandles(MultiBufferSource.BufferSource bufferSource,
                                       PoseStack.Pose poseEntry,
                                       BlockPos min, BlockPos max) {
        float x0 = min.getX();
        float y0 = min.getY();
        float z0 = min.getZ();
        float x1 = max.getX() + 1.0f;
        float y1 = max.getY() + 1.0f;
        float z1 = max.getZ() + 1.0f;
        float h = HANDLE_SIZE;

        // 8 corners
        float[][] corners = {
                {x0, y0, z0}, {x1, y0, z0}, {x0, y1, z0}, {x1, y1, z0},
                {x0, y0, z1}, {x1, y0, z1}, {x0, y1, z1}, {x1, y1, z1},
        };
        VertexConsumer quadVc = bufferSource.getBuffer(RenderType.debugQuads());
        for (float[] c : corners) {
            float cx = c[0], cy = c[1], cz = c[2];
            drawHandleCube(quadVc, poseEntry, cx, cy, cz, h, HANDLE_CORNER_R, HANDLE_CORNER_G, HANDLE_CORNER_B);
        }
        bufferSource.endBatch(RenderType.debugQuads());

        // 6 face centers
        float midX = (x0 + x1) / 2.0f;
        float midY = (y0 + y1) / 2.0f;
        float midZ = (z0 + z1) / 2.0f;
        float[][] faces = {
                {x0, midY, midZ}, {x1, midY, midZ},  // X faces
                {midX, y0, midZ}, {midX, y1, midZ},  // Y faces
                {midX, midY, z0}, {midX, midY, z1},  // Z faces
        };
        VertexConsumer faceQuadVc = bufferSource.getBuffer(RenderType.debugQuads());
        for (float[] f : faces) {
            drawHandleCube(faceQuadVc, poseEntry, f[0], f[1], f[2], h * 0.8f,
                    HANDLE_FACE_R, HANDLE_FACE_G, HANDLE_FACE_B);
        }
        bufferSource.endBatch(RenderType.debugQuads());
    }

    private static void drawHandleCube(VertexConsumer vc, PoseStack.Pose poseEntry,
                                        float cx, float cy, float cz,
                                        float half,
                                        int r, int g, int b) {
        float x0 = cx - half, x1 = cx + half;
        float y0 = cy - half, y1 = cy + half;
        float z0 = cz - half, z1 = cz + half;
        int a = 220;

        // All 6 faces
        vc.addVertex(poseEntry, x0, y0, z0).setColor(r, g, b, a);
        vc.addVertex(poseEntry, x1, y0, z0).setColor(r, g, b, a);
        vc.addVertex(poseEntry, x1, y0, z1).setColor(r, g, b, a);
        vc.addVertex(poseEntry, x0, y0, z1).setColor(r, g, b, a);

        vc.addVertex(poseEntry, x0, y1, z0).setColor(r, g, b, a);
        vc.addVertex(poseEntry, x0, y1, z1).setColor(r, g, b, a);
        vc.addVertex(poseEntry, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(poseEntry, x1, y1, z0).setColor(r, g, b, a);

        vc.addVertex(poseEntry, x0, y0, z1).setColor(r, g, b, a);
        vc.addVertex(poseEntry, x1, y0, z1).setColor(r, g, b, a);
        vc.addVertex(poseEntry, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(poseEntry, x0, y1, z1).setColor(r, g, b, a);

        vc.addVertex(poseEntry, x0, y0, z0).setColor(r, g, b, a);
        vc.addVertex(poseEntry, x0, y1, z0).setColor(r, g, b, a);
        vc.addVertex(poseEntry, x1, y1, z0).setColor(r, g, b, a);
        vc.addVertex(poseEntry, x1, y0, z0).setColor(r, g, b, a);

        vc.addVertex(poseEntry, x0, y0, z0).setColor(r, g, b, a);
        vc.addVertex(poseEntry, x0, y0, z1).setColor(r, g, b, a);
        vc.addVertex(poseEntry, x0, y1, z1).setColor(r, g, b, a);
        vc.addVertex(poseEntry, x0, y1, z0).setColor(r, g, b, a);

        vc.addVertex(poseEntry, x1, y0, z0).setColor(r, g, b, a);
        vc.addVertex(poseEntry, x1, y1, z0).setColor(r, g, b, a);
        vc.addVertex(poseEntry, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(poseEntry, x1, y0, z1).setColor(r, g, b, a);
    }

    // ── Anchor marker ──

    private static void renderAnchorMarker(MultiBufferSource.BufferSource bufferSource,
                                            PoseStack.Pose poseEntry,
                                            BlockPos anchor) {
        float cx = anchor.getX() + 0.5f;
        float cy = anchor.getY() + 0.5f;
        float cz = anchor.getZ() + 0.5f;
        float half = ANCHOR_SIZE;

        VertexConsumer lineVc = bufferSource.getBuffer(RenderType.lines());
        int r = ANCHOR_R, g = ANCHOR_G, b = ANCHOR_B;

        // Diamond shape: 6 lines from center to 6 face directions
        drawLine(lineVc, poseEntry, cx, cy, cz, cx + half, cy, cz, r, g, b);       // +X
        drawLine(lineVc, poseEntry, cx, cy, cz, cx - half, cy, cz, r, g, b);       // -X
        drawLine(lineVc, poseEntry, cx, cy, cz, cx, cy + half, cz, r, g, b);       // +Y
        drawLine(lineVc, poseEntry, cx, cy, cz, cx, cy - half, cz, r, g, b);       // -Y
        drawLine(lineVc, poseEntry, cx, cy, cz, cx, cy, cz + half, r, g, b);       // +Z
        drawLine(lineVc, poseEntry, cx, cy, cz, cx, cy, cz - half, r, g, b);       // -Z
        bufferSource.endBatch(RenderType.lines());
    }

    // ── Utility ──

    private static void drawBoxLines(VertexConsumer vc, PoseStack.Pose poseEntry,
                                      float x0, float y0, float z0,
                                      float x1, float y1, float z1,
                                      int r, int g, int b, int a) {
        // Bottom face
        drawLine(vc, poseEntry, x0, y0, z0, x1, y0, z0, r, g, b, a);
        drawLine(vc, poseEntry, x1, y0, z0, x1, y0, z1, r, g, b, a);
        drawLine(vc, poseEntry, x1, y0, z1, x0, y0, z1, r, g, b, a);
        drawLine(vc, poseEntry, x0, y0, z1, x0, y0, z0, r, g, b, a);
        // Top face
        drawLine(vc, poseEntry, x0, y1, z0, x1, y1, z0, r, g, b, a);
        drawLine(vc, poseEntry, x1, y1, z0, x1, y1, z1, r, g, b, a);
        drawLine(vc, poseEntry, x1, y1, z1, x0, y1, z1, r, g, b, a);
        drawLine(vc, poseEntry, x0, y1, z1, x0, y1, z0, r, g, b, a);
        // Vertical edges
        drawLine(vc, poseEntry, x0, y0, z0, x0, y1, z0, r, g, b, a);
        drawLine(vc, poseEntry, x1, y0, z0, x1, y1, z0, r, g, b, a);
        drawLine(vc, poseEntry, x1, y0, z1, x1, y1, z1, r, g, b, a);
        drawLine(vc, poseEntry, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    private static void drawLine(VertexConsumer vc, PoseStack.Pose poseEntry,
                                  float x1, float y1, float z1,
                                  float x2, float y2, float z2,
                                  int r, int g, int b) {
        drawLine(vc, poseEntry, x1, y1, z1, x2, y2, z2, r, g, b, 255);
    }

    private static void drawLine(VertexConsumer vc, PoseStack.Pose poseEntry,
                                  float x1, float y1, float z1,
                                  float x2, float y2, float z2,
                                  int r, int g, int b, int a) {
        vc.addVertex(poseEntry, x1, y1, z1).setColor(r, g, b, a).setNormal(poseEntry, 0, 1, 0);
        vc.addVertex(poseEntry, x2, y2, z2).setColor(r, g, b, a).setNormal(poseEntry, 0, 1, 0);
    }
}
