package com.wsteam.wandscape.shared.client.bubble;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.LivingEntity;

import org.joml.Matrix4f;

/**
 * Renders the transient satisfaction bar (XP-style) that appears under a
 * tourist's bubble after a building interaction. Kept separate from
 * {@link SpeechBubbleRenderer} so bubble and bar can be debugged / tuned
 * independently. Both are driven by the shared {@link TransientBubbleStore}.
 */
public final class SatisfactionBarRenderer {

    private static final float SCALE        = 0.04F;
    private static final float Y_OFFSET     = 1.2F;
    private static final float BAR_H        = 6F;
    private static final float BAR_W        = 36F;
    private static final float BAR_GAP_HEAD = 4F; // space above the head, aligns with the bubble pointer
    private static final int   MAX_DIST_SQ  = 4096;

    private SatisfactionBarRenderer() {}

    public static void renderBar(LivingEntity entity, PoseStack poseStack,
                                 MultiBufferSource buffer, int packedLight) {
        if (entity.isInvisible()) return;
        TransientBubbleStore.Event event = TransientBubbleStore.get(entity.getUUID(), entity.tickCount);
        if (event == null) return;

        int elapsed = Math.max(0, entity.tickCount - event.startTick());
        float alpha = TransientBubbleStore.alpha(elapsed);
        if (alpha <= 0.005F) return;

        EntityRenderDispatcher renderDispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        if (renderDispatcher.distanceToSqr(entity) > MAX_DIST_SQ) return;

        float barTop = -(BAR_H + BAR_GAP_HEAD);

        poseStack.pushPose();
        poseStack.translate(0, entity.getBbHeight() + Y_OFFSET, 0);
        poseStack.mulPose(renderDispatcher.cameraOrientation());
        poseStack.scale(SCALE, -SCALE, SCALE);
        Matrix4f matrix = poseStack.last().pose();

        float fill = TransientBubbleStore.satFill(elapsed, event.satBefore(), event.satAfter());
        drawBar(buffer, matrix, barTop, BAR_W, fill, alpha);

        poseStack.popPose();
    }

    /** XP-style bar: dark track + gold fill (before → after) + segment notches. */
    private static void drawBar(MultiBufferSource buffer, Matrix4f matrix,
                                float barTop, float barW, float fill, float alpha) {
        VertexConsumer vc = buffer.getBuffer(RenderType.debugQuads());
        float x0 = -barW / 2F;
        float x1 = barW / 2F;
        float y0 = barTop;
        float y1 = barTop + BAR_H;

        // Layers stacked on different z (toward camera) so the gold fill covers
        // the dark track instead of z-fighting with it.
        addQuad(vc, matrix, x0, y0, x1, y1, 0F, 0.10F, 0.10F, 0.12F, alpha);
        float fx1 = x0 + (x1 - x0) * fill;
        addQuad(vc, matrix, x0, y0, fx1, y1, 0.5F, 1.0F, 0.85F, 0.30F, alpha);

        for (int i = 1; i <= 4; i++) {
            float sepX = x0 + (x1 - x0) * (i / 5F);
            if (sepX >= fx1) break;
            addQuad(vc, matrix, sepX, y0, sepX + 1F, y1, 1F, 0.10F, 0.10F, 0.12F, alpha);
        }
    }

    private static void addQuad(VertexConsumer vc, Matrix4f matrix,
                                float x0, float y0, float x1, float y1, float z,
                                float r, float g, float b, float a) {
        vc.addVertex(matrix, x0, y0, z).setColor(r, g, b, a);
        vc.addVertex(matrix, x1, y0, z).setColor(r, g, b, a);
        vc.addVertex(matrix, x1, y1, z).setColor(r, g, b, a);
        vc.addVertex(matrix, x0, y1, z).setColor(r, g, b, a);
    }
}
