package com.wsteam.wandscape.tourist.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import com.wsteam.wandscape.tourist.entity.TouristEntity;

import java.util.List;

/**
 * Debug renderer that visualizes tourist navigation targets.
 *
 * <p>X-ray (no depth test) rendering:
 * <ul>
 *   <li>Cyan line → entry point (macro outdoor nav target)</li>
 *   <li>Blue cross at entry point</li>
 *   <li>Magenta line → interact point (micro indoor nav)</li>
 *   <li>Yellow cross at interact point</li>
 * </ul>
 */
public final class TouristDebugRenderer {

    private static boolean enabled = false;
    private static boolean registered = false;

    private static final float LINE_Y_OFFSET = 1.6f;  // tourist eye height
    private static final float CROSS_SIZE = 0.4f;
    private static final int SCAN_RADIUS = 80;

    private TouristDebugRenderer() {}

    public static void register() {
        if (registered) return;
        registered = true;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                RenderLevelStageEvent.class, TouristDebugRenderer::onRenderLevelStage);
    }

    public static void toggle() {
        enabled = !enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!enabled) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        List<? extends TouristEntity> tourists = mc.level.getEntitiesOfClass(
                TouristEntity.class,
                new AABB(mc.player.blockPosition()).inflate(SCAN_RADIUS));

        if (tourists.isEmpty()) return;

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        VertexConsumer lineBuf = buf.getBuffer(DebugRenderType.DEBUG_LINES);
        PoseStack.Pose pose = poseStack.last();

        for (TouristEntity t : tourists) {
            BlockPos pos = t.blockPosition();
            BlockPos commute = t.getDebugCommuteTarget();
            BlockPos entryPt = t.getDebugEntryPoint();
            BlockPos interactPt = t.getDebugInteractPoint();
            boolean indoor = t.isDebugIndoorPhase();

            // Fallback: entry point not yet computed → use raw commute target
            if (entryPt == null) entryPt = commute;
            // Fallback: interact not yet computed → use commute target
            if (interactPt == null) interactPt = commute;

            // Skip tourists with no target data at all (idle/wandering/hotel)
            if (entryPt == null && interactPt == null) continue;

            float sx = pos.getX() + 0.5f;
            float sy = pos.getY() + LINE_Y_OFFSET;
            float sz = pos.getZ() + 0.5f;

            if (indoor && interactPt != null) {
                // ── Indoor micro-nav ──
                // Magenta line: tourist → interact point
                line(pose, lineBuf, sx, sy, sz,
                        interactPt.getX() + 0.5f, interactPt.getY() + 0.1f, interactPt.getZ() + 0.5f,
                        255, 64, 255, 220);
                // Yellow cross at interact point
                cross(pose, lineBuf, interactPt, 255, 220, 50, 220);
                // Blue cross at entry point (exit target)
                if (entryPt != null) {
                    cross(pose, lineBuf, entryPt, 64, 140, 255, 180);
                }
            } else if (entryPt != null) {
                // ── Outdoor macro-nav ──
                // Cyan line: tourist → entry point
                line(pose, lineBuf, sx, sy, sz,
                        entryPt.getX() + 0.5f, entryPt.getY() + 0.1f, entryPt.getZ() + 0.5f,
                        64, 220, 255, 220);
                // Blue cross at entry point
                cross(pose, lineBuf, entryPt, 64, 140, 255, 220);
                // Show interact point if resolved (dimmed orange)
                if (interactPt != null && !interactPt.equals(entryPt)) {
                    cross(pose, lineBuf, interactPt, 255, 180, 100, 140);
                }
            }
        }

        buf.endBatch(DebugRenderType.DEBUG_LINES);
        poseStack.popPose();
    }

    // ── Drawing primitives ──

    private static void line(PoseStack.Pose pose, VertexConsumer vc,
                              float x1, float y1, float z1,
                              float x2, float y2, float z2,
                              int r, int g, int b, int a) {
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a);
    }

    private static void cross(PoseStack.Pose pose, VertexConsumer vc,
                               BlockPos pos, int r, int g, int b, int a) {
        float cx = pos.getX() + 0.5f;
        float cy = pos.getY() + 0.08f;
        float cz = pos.getZ() + 0.5f;
        float s = CROSS_SIZE;

        // X line
        vc.addVertex(pose, cx - s, cy, cz).setColor(r, g, b, a);
        vc.addVertex(pose, cx + s, cy, cz).setColor(r, g, b, a);
        // Z line
        vc.addVertex(pose, cx, cy, cz - s).setColor(r, g, b, a);
        vc.addVertex(pose, cx, cy, cz + s).setColor(r, g, b, a);
        // Y spike
        vc.addVertex(pose, cx, cy - 0.1f, cz).setColor(r, g, b, a);
        vc.addVertex(pose, cx, cy + 0.1f, cz).setColor(r, g, b, a);
    }

    // ═══════════════════════════════════════════════════════════════
    // X-Ray debug render type
    // ═══════════════════════════════════════════════════════════════

    private static final class DebugRenderType extends RenderType {
        private DebugRenderType(String name, VertexFormat format, VertexFormat.Mode mode,
                                int bufSize, boolean affectsCrumbling, boolean sortOnUpload,
                                Runnable setup, Runnable clear) {
            super(name, format, mode, bufSize, affectsCrumbling, sortOnUpload, setup, clear);
        }

        static final RenderType DEBUG_LINES = create(
                "tourist_debug",
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.DEBUG_LINES,
                512,
                false,
                false,
                CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(NO_DEPTH_TEST)
                        .setCullState(NO_CULL)
                        .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(2.0)))
                        .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                        .createCompositeState(false)
        );
    }
}
