package com.wsteam.wandscape.building.editor;

import java.util.List;

import org.slf4j.Logger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.data.BlockOffset;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * World-space renderer for the building editor AABB wireframe
 * and pattern block highlights.
 *
 * <p>3D coordinate axes are rendered separately by {@link BuildingEditorAxisRenderer}.
 */
public final class BuildingEditorRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    // AABB face + line colors
    private static final int AABB_FACE_R = 0, AABB_FACE_G = 220, AABB_FACE_B = 80, AABB_FACE_A = 70;
    private static final int AABB_LINE_R = 0, AABB_LINE_G = 220, AABB_LINE_B = 80, AABB_LINE_A = 180;
    // Pattern block highlight
    private static final int PAT_R = 0, PAT_G = 200, PAT_B = 220, PAT_A = 60;
    // Anchor marker
    private static final int ANC_R = 255, ANC_G = 215, ANC_B = 0;

    private static boolean registered = false;

    private BuildingEditorRenderer() {}

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.addListener(RenderLevelStageEvent.class, BuildingEditorRenderer::onRenderLevelStage);
        LOGGER.info("[BuildEditor] Renderer registered");
    }

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!BuildingEditorClientState.isEditing()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        PoseStack.Pose pose = poseStack.last();

        // 1. AABB wireframe
        BlockPos wMin = BuildingEditorClientState.getWorldMin();
        BlockPos wMax = BuildingEditorClientState.getWorldMax();
        if (wMin != null && wMax != null) {
            renderAABB(buf, pose, wMin, wMax);
        }

        // 2. Pattern block highlights
        renderPattern(buf, pose);

        // 3. Anchor marker
        BlockPos anchor = BuildingEditorClientState.getWorldAnchor();
        if (anchor != null) {
            renderAnchor(buf, pose, anchor);
        }

        poseStack.popPose();
    }

    private static void renderAABB(MultiBufferSource.BufferSource buf, PoseStack.Pose pose,
                                    BlockPos min, BlockPos max) {
        float x0 = min.getX(), y0 = min.getY(), z0 = min.getZ();
        float x1 = max.getX() + 1f, y1 = max.getY() + 1f, z1 = max.getZ() + 1f;

        // Semi-transparent faces (only the 3 outward-facing faces)
        VertexConsumer fvc = buf.getBuffer(RenderType.debugQuads());
        int r = AABB_FACE_R, g = AABB_FACE_G, b = AABB_FACE_B, a = AABB_FACE_A;
        quad(fvc, pose, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a); // bottom
        quad(fvc, pose, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, a); // top
        quad(fvc, pose, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a); // back
        quad(fvc, pose, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a); // right
        quad(fvc, pose, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, a); // front
        quad(fvc, pose, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a); // left
        buf.endBatch(RenderType.debugQuads());

        // Edges
        VertexConsumer lvc = buf.getBuffer(RenderType.lines());
        r = AABB_LINE_R; g = AABB_LINE_G; b = AABB_LINE_B; a = AABB_LINE_A;
        boxEdges(lvc, pose, x0, y0, z0, x1, y1, z1, r, g, b, a);
        buf.endBatch(RenderType.lines());
    }

    private static void renderPattern(MultiBufferSource.BufferSource buf, PoseStack.Pose pose) {
        BlockPos anchor = BuildingEditorClientState.getWorldAnchor();
        if (anchor == null) return;
        List<BlockOffset> pattern = BuildingEditorClientState.getPattern();
        if (pattern.isEmpty()) return;

        VertexConsumer vc = buf.getBuffer(RenderType.lines());
        for (BlockOffset off : pattern) {
            float bx = anchor.getX() + off.x();
            float by = anchor.getY() + off.y();
            float bz = anchor.getZ() + off.z();
            boxEdges(vc, pose, bx, by, bz, bx + 1, by + 1, bz + 1, PAT_R, PAT_G, PAT_B, PAT_A);
        }
        buf.endBatch(RenderType.lines());
    }

    private static void renderAnchor(MultiBufferSource.BufferSource buf, PoseStack.Pose pose,
                                      BlockPos anchor) {
        float cx = anchor.getX() + 0.5f, cy = anchor.getY() + 0.5f, cz = anchor.getZ() + 0.5f;
        float h = 0.35f;
        VertexConsumer vc = buf.getBuffer(RenderType.lines());
        int r = ANC_R, g = ANC_G, b = ANC_B;
        line(vc, pose, cx, cy, cz, cx + h, cy, cz, r, g, b, 200);
        line(vc, pose, cx, cy, cz, cx - h, cy, cz, r, g, b, 200);
        line(vc, pose, cx, cy, cz, cx, cy + h, cz, r, g, b, 200);
        line(vc, pose, cx, cy, cz, cx, cy - h, cz, r, g, b, 200);
        line(vc, pose, cx, cy, cz, cx, cy, cz + h, r, g, b, 200);
        line(vc, pose, cx, cy, cz, cx, cy, cz - h, r, g, b, 200);
        buf.endBatch(RenderType.lines());
    }

    // ── Drawing helpers ──

    private static void boxEdges(VertexConsumer vc, PoseStack.Pose pose,
                                  float x0, float y0, float z0, float x1, float y1, float z1,
                                  int r, int g, int b, int a) {
        line(vc, pose, x0,y0,z0, x1,y0,z0, r,g,b,a); line(vc, pose, x1,y0,z0, x1,y0,z1, r,g,b,a);
        line(vc, pose, x1,y0,z1, x0,y0,z1, r,g,b,a); line(vc, pose, x0,y0,z1, x0,y0,z0, r,g,b,a);
        line(vc, pose, x0,y1,z0, x1,y1,z0, r,g,b,a); line(vc, pose, x1,y1,z0, x1,y1,z1, r,g,b,a);
        line(vc, pose, x1,y1,z1, x0,y1,z1, r,g,b,a); line(vc, pose, x0,y1,z1, x0,y1,z0, r,g,b,a);
        line(vc, pose, x0,y0,z0, x0,y1,z0, r,g,b,a); line(vc, pose, x1,y0,z0, x1,y1,z0, r,g,b,a);
        line(vc, pose, x1,y0,z1, x1,y1,z1, r,g,b,a); line(vc, pose, x0,y0,z1, x0,y1,z1, r,g,b,a);
    }

    private static void line(VertexConsumer vc, PoseStack.Pose pose,
                              float x1, float y1, float z1, float x2, float y2, float z2,
                              int r, int g, int b, int a) {
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a).setNormal(pose, 0, 1, 0);
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose,
                              float x1, float y1, float z1, float x2, float y2, float z2,
                              float x3, float y3, float z3, float x4, float y4, float z4,
                              int r, int g, int b, int a) {
        vc.addVertex(pose, x1,y1,z1).setColor(r,g,b,a);
        vc.addVertex(pose, x2,y2,z2).setColor(r,g,b,a);
        vc.addVertex(pose, x3,y3,z3).setColor(r,g,b,a);
        vc.addVertex(pose, x4,y4,z4).setColor(r,g,b,a);
    }
}
