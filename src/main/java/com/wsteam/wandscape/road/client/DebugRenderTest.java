package com.wsteam.wandscape.road.client;

import org.joml.Matrix4f;
import org.slf4j.Logger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Debug render test: draws a colored cross at the player's position.
 * Tries multiple approaches to find one that works.
 */
public final class DebugRenderTest {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile boolean enabled = false;
    private static Vec3 testPos = Vec3.ZERO;
    private static int frameCount = 0;

    private DebugRenderTest() {}

    public static void register() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(RenderLevelStageEvent.class, DebugRenderTest::onRender);
        LOGGER.info("[DebugRender] registered");
    }

    public static void enable(Vec3 pos) {
        testPos = pos;
        enabled = true;
        frameCount = 0;
        LOGGER.info("[DebugRender] ENABLED at ({}, {}, {})", pos.x, pos.y, pos.z);
    }

    static void onRender(RenderLevelStageEvent event) {
        if (!enabled) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        if (frameCount < 3) {
            LOGGER.info("[DebugRender] frame={} pos=({},{},{}) stage={}",
                    frameCount, testPos.x, testPos.y, testPos.z, event.getStage());
        }
        frameCount++;

        float cx = (float) testPos.x + 0.5f;
        float cy = (float) testPos.y + 0.5f;
        float cz = (float) testPos.z + 0.5f;
        float len = 5.0f;

        // ── Approach 1: RenderType.LINES with modelViewMatrix ──
        {
            Matrix4f mv = event.getModelViewMatrix();
            Matrix4f proj = event.getProjectionMatrix();
            PoseStack poseStack = event.getPoseStack();
            PoseStack.Pose poseEntry = poseStack.last();

            MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
            VertexConsumer vc = buf.getBuffer(RenderType.lines());

            // Red X-axis
            vc.addVertex(mv, cx - len, cy, cz).setColor(255, 0, 0, 255).setNormal(poseEntry, 0, 1, 0);
            vc.addVertex(mv, cx + len, cy, cz).setColor(255, 0, 0, 255).setNormal(poseEntry, 0, 1, 0);
            // Green Z-axis
            vc.addVertex(mv, cx, cy, cz - len).setColor(0, 255, 0, 255).setNormal(poseEntry, 0, 1, 0);
            vc.addVertex(mv, cx, cy, cz + len).setColor(0, 255, 0, 255).setNormal(poseEntry, 0, 1, 0);
            // Blue Y-axis
            vc.addVertex(mv, cx, cy - len, cz).setColor(0, 0, 255, 255).setNormal(poseEntry, 0, 1, 0);
            vc.addVertex(mv, cx, cy + len, cz).setColor(0, 0, 255, 255).setNormal(poseEntry, 0, 1, 0);

            buf.endBatch(RenderType.lines());
        }

        // ── Approach 2: debugLineStrip with poseStack ──
        {
            PoseStack poseStack = event.getPoseStack();
            poseStack.pushPose();
            Matrix4f pm = poseStack.last().pose();
            PoseStack.Pose pe = poseStack.last();

            MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
            VertexConsumer vc = buf.getBuffer(RenderType.debugLineStrip(3.0));

            // White diamond
            vc.addVertex(pm, cx - 2, cy + 2, cz).setColor(255, 255, 255, 255);
            vc.addVertex(pm, cx, cy + 4, cz).setColor(255, 255, 255, 255);
            vc.addVertex(pm, cx + 2, cy + 2, cz).setColor(255, 255, 255, 255);
            vc.addVertex(pm, cx, cy + 4, cz).setColor(255, 255, 255, 255);

            poseStack.popPose();
            buf.endBatch(RenderType.debugLineStrip(3.0));
        }

        // ── Approach 3: debugQuads (filled box) ──
        {
            PoseStack poseStack = event.getPoseStack();
            poseStack.pushPose();
            Matrix4f pm = poseStack.last().pose();

            MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
            VertexConsumer vc = buf.getBuffer(RenderType.debugFilledBox());

            float s = 1.0f;
            // A single quad facing up at player feet
            vc.addVertex(pm, cx - s, cy + 0.1f, cz - s).setColor(255, 0, 255, 128);
            vc.addVertex(pm, cx - s, cy + 0.1f, cz + s).setColor(255, 0, 255, 128);
            vc.addVertex(pm, cx + s, cy + 0.1f, cz + s).setColor(255, 0, 255, 128);
            vc.addVertex(pm, cx + s, cy + 0.1f, cz - s).setColor(255, 0, 255, 128);

            poseStack.popPose();
            buf.endBatch(RenderType.debugFilledBox());
        }
    }
}