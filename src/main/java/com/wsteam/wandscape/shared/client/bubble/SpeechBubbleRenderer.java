package com.wsteam.wandscape.shared.client.bubble;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;

import org.joml.Matrix4f;

public final class SpeechBubbleRenderer {

    private static final int FADE_DURATION = 60;        // 3 s at 20 tps
    private static final int VISIBLE_DURATION = 120;     // 6 s = fade_in + fade_out

    private static final float SCALE          = 0.04F;
    private static final float Y_OFFSET       = 1.2F;
    private static final float BUBBLE_PADDING = 8.0F;
    private static final float TRIANGLE_SIZE  = 4.0F;
    private static final int   MAX_DIST_SQ    = 4096;
    private static final int   ELLIPSE_SEGMENTS = 20;

    private static final float[][] FONT_WHITE_UVS = {
        {0.5F, 0.5F}, {0.5625F, 0.4375F}, {0.625F, 0.5F}, {0.5625F, 0.5625F}
    };

    private static final Map<UUID, BubbleState> STATES = new ConcurrentHashMap<>();

    private SpeechBubbleRenderer() {}

    public static void renderBubble(LivingEntity entity, PoseStack poseStack,
                                    MultiBufferSource buffer, int packedLight,
                                    IBubbleTextProvider textProvider) {
        if (entity.isInvisible()) return;

        UUID uuid = entity.getUUID();
        BubbleState state = STATES.computeIfAbsent(uuid, k -> new BubbleState(entity));

        int effectiveTick = entity.tickCount + state.offset;
        int cycleLen = state.getCycleLength();
        int timer = effectiveTick % cycleLen;
        int cycle = effectiveTick / cycleLen;
        int fadeInStart = cycleLen - VISIBLE_DURATION;

        if (cycle != state.lastCycle) {
            state.lastCycle = cycle;
            state.currentText = textProvider.getText(entity);
        }
        if (!state.initialized) {
            state.initialized = true;
            if (state.currentText == null) {
                state.currentText = textProvider.getText(entity);
            }
        }

        if (timer < fadeInStart) return;

        Component textComp = state.currentText;
        if (textComp == null) return;

        float alpha = getAlpha(timer, fadeInStart);
        if (alpha <= 0.005F) return;

        EntityRenderDispatcher renderDispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        double distSq = renderDispatcher.distanceToSqr(entity);
        if (distSq > MAX_DIST_SQ) return;

        Font font = Minecraft.getInstance().font;
        float textWidth = font.width(textComp);
        float textHeight = font.lineHeight;

        float bubbleW = textWidth + BUBBLE_PADDING * 2;
        float bubbleH = textHeight + BUBBLE_PADDING * 2;
        float totalH = bubbleH + TRIANGLE_SIZE;

        float bx = -bubbleW / 2F;
        float by = -totalH;

        poseStack.pushPose();
        poseStack.translate(0, entity.getBbHeight() + Y_OFFSET, 0);
        poseStack.mulPose(renderDispatcher.cameraOrientation());
        poseStack.scale(SCALE, -SCALE, SCALE);
        Matrix4f matrix = poseStack.last().pose();

        float ex = 0F;
        float ey = by + bubbleH / 2F;
        float rx = bubbleW / 2F;
        float ry = bubbleH / 2F;

        // Draw filled ellipse using debug quads (POSITION_COLOR, works in entity pipeline)
        VertexConsumer vc = buffer.getBuffer(RenderType.debugQuads());
        for (int i = 0; i < ELLIPSE_SEGMENTS; i++) {
            double a1 = 2 * Math.PI * i / ELLIPSE_SEGMENTS;
            double a2 = 2 * Math.PI * (i + 1) / ELLIPSE_SEGMENTS;
            float px1 = (float)(ex + rx * Math.cos(a1));
            float py1 = (float)(ey + ry * Math.sin(a1));
            float px2 = (float)(ex + rx * Math.cos(a2));
            float py2 = (float)(ey + ry * Math.sin(a2));

            vc.addVertex(matrix, ex, ey, 0).setColor(1F, 1F, 1F, alpha);
            vc.addVertex(matrix, px1, py1, 0).setColor(1F, 1F, 1F, alpha);
            vc.addVertex(matrix, px2, py2, 0).setColor(1F, 1F, 1F, alpha);
            vc.addVertex(matrix, px2, py2, 0).setColor(1F, 1F, 1F, alpha);
        }

        // Triangle pointer
        float triBase = by + bubbleH;
        vc.addVertex(matrix, -TRIANGLE_SIZE, triBase, 0).setColor(1F, 1F, 1F, alpha);
        vc.addVertex(matrix, TRIANGLE_SIZE, triBase, 0).setColor(1F, 1F, 1F, alpha);
        vc.addVertex(matrix, 0F, triBase + TRIANGLE_SIZE, 0).setColor(1F, 1F, 1F, alpha);
        vc.addVertex(matrix, 0F, triBase + TRIANGLE_SIZE, 0).setColor(1F, 1F, 1F, alpha);

        // Text
        float textX = -textWidth / 2F;
        float textY = ey - textHeight / 2F;
        font.drawInBatch(textComp, textX, textY, 0xFF000000, false,
                matrix, buffer, Font.DisplayMode.NORMAL, 0, packedLight);

        poseStack.popPose();
    }

    private static float getAlpha(int timer, int fadeInStart) {
        int local = timer - fadeInStart;
        if (local < 0) return 0F;
        if (local < FADE_DURATION) {
            return (float) local / FADE_DURATION;
        }
        int fadeOutStart = VISIBLE_DURATION - FADE_DURATION;
        if (local < fadeOutStart) return 1F;
        if (local < VISIBLE_DURATION) {
            return 1F - (float) (local - fadeOutStart) / FADE_DURATION;
        }
        return 0F;
    }

    private static class BubbleState {
        int lastCycle = -1;
        int offset;
        int hiddenDuration;  // random 320-640 ticks (16-32 s)
        @Nullable
        Component currentText;
        boolean initialized;

        BubbleState(LivingEntity entity) {
            this.offset = entity.getRandom().nextInt(600);
            this.hiddenDuration = 320 + entity.getRandom().nextInt(321); // 320..640
        }

        int getCycleLength() {
            return hiddenDuration + VISIBLE_DURATION;
        }
    }
}
