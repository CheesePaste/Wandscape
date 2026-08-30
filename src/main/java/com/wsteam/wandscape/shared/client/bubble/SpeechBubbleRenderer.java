package com.wsteam.wandscape.shared.client.bubble;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SpeechBubbleRenderer {

    private static final int FADE_DURATION = 60;        // 3 s at 20 tps
    private static final int VISIBLE_DURATION = 120;     // 6 s = fade_in + fade_out

    private static final float SCALE          = 0.04F;
    private static final float Y_OFFSET       = 1.2F;
    private static final float BUBBLE_PADDING = 8.0F;
    private static final float TRIANGLE_SIZE  = 4.0F;
    private static final int   MAX_DIST_SQ    = 4096;
    private static final int   ELLIPSE_SEGMENTS = 20;
    private static final float TEXT_Z_OFFSET   = 0.05F;

    private static final float[][] FONT_WHITE_UVS = {
        {0.5F, 0.5F}, {0.5625F, 0.4375F}, {0.625F, 0.5F}, {0.5625F, 0.5625F}
    };

    private static final Map<UUID, BubbleState> STATES = new ConcurrentHashMap<>();

    private SpeechBubbleRenderer() {}

    public static void renderBubble(LivingEntity entity, PoseStack poseStack,
                                    MultiBufferSource buffer, int packedLight,
                                    IBubbleTextProvider textProvider) {
        if (entity.isInvisible()) return;

        // Transient event bubble (purchase / service feedback) temporarily overrides ambient text
        TransientBubbleStore.Event event = TransientBubbleStore.get(entity.getUUID(), entity.tickCount);
        if (event != null) {
            renderEventBubble(entity, event, poseStack, buffer, packedLight);
            return;
        }

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

        float by = -totalH;

        poseStack.pushPose();
        poseStack.translate(0, entity.getBbHeight() + Y_OFFSET, 0);
        poseStack.mulPose(renderDispatcher.cameraOrientation());
        poseStack.scale(SCALE, -SCALE, SCALE);
        Matrix4f matrix = poseStack.last().pose();

        float ey = by + bubbleH / 2F;

        // Draw filled ellipse + pointer using debug quads (POSITION_COLOR, works in entity pipeline)
        drawEllipseBody(buffer, matrix, bubbleW, bubbleH, by, alpha);

        // Text (offset along +Z towards camera and use POLYGON_OFFSET to prevent shader z-fighting)
        poseStack.pushPose();
        poseStack.translate(0, 0, TEXT_Z_OFFSET);
        Matrix4f textMatrix = poseStack.last().pose();
        float textX = -textWidth / 2F;
        float textY = ey - textHeight / 2F;
        font.drawInBatch(textComp, textX, textY, 0xFF000000, false,
                textMatrix, buffer, Font.DisplayMode.POLYGON_OFFSET, 0, packedLight);
        poseStack.popPose();

        poseStack.popPose();
    }

    // ── Transient event bubble (purchase / service feedback) ──
    // 瞬时满意度头顶条已移除（Block 4）；本方法只画气泡主体 + 图标。

    private static void renderEventBubble(LivingEntity entity, TransientBubbleStore.Event event,
                                          PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        int elapsed = Math.max(0, entity.tickCount - event.startTick());
        float alpha = TransientBubbleStore.alpha(elapsed);
        if (alpha <= 0.005F) return;

        EntityRenderDispatcher renderDispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        if (renderDispatcher.distanceToSqr(entity) > MAX_DIST_SQ) return;

        String iconId = event.iconId();
        if (iconId == null) return; // 没有可展示的物品 → 不显示气泡

        Font font = Minecraft.getInstance().font;
        String countText = "×" + event.count();
        float countW = font.width(countText);

        final float iconSize = 16F;
        final float gap = 4F;

        float contentW = iconSize + gap + countW;
        float bubbleW = contentW + BUBBLE_PADDING * 2F;
        float bubbleH = iconSize + BUBBLE_PADDING * 2F;
        float totalH = bubbleH + TRIANGLE_SIZE;
        float by = -totalH;
        float ey = by + bubbleH / 2F;

        poseStack.pushPose();
        poseStack.translate(0, entity.getBbHeight() + Y_OFFSET, 0);
        poseStack.mulPose(renderDispatcher.cameraOrientation());
        poseStack.scale(SCALE, -SCALE, SCALE);
        Matrix4f matrix = poseStack.last().pose();

        drawEllipseBody(buffer, matrix, bubbleW, bubbleH, by, alpha);

        float cx = -contentW / 2F + iconSize / 2F;
        drawItemIcon(entity, iconId, cx, ey, poseStack, buffer, packedLight);

        float textX = cx + iconSize / 2F + gap;
        float textY = ey - font.lineHeight / 2F;
        poseStack.pushPose();
        poseStack.translate(0, 0, TEXT_Z_OFFSET);
        Matrix4f textMatrix = poseStack.last().pose();
        font.drawInBatch(Component.literal(countText), textX, textY, 0xFF000000, false,
                textMatrix, buffer, Font.DisplayMode.POLYGON_OFFSET, 0, packedLight);
        poseStack.popPose();

        poseStack.popPose();
    }

    /** Ellipse bubble body + downward pointer. */
    private static void drawEllipseBody(MultiBufferSource buffer, Matrix4f matrix,
                                        float bubbleW, float bubbleH, float by, float alpha) {
        VertexConsumer vc = buffer.getBuffer(RenderType.debugQuads());
        float ex = 0F;
        float ey = by + bubbleH / 2F;
        float rx = bubbleW / 2F;
        float ry = bubbleH / 2F;
        for (int i = 0; i < ELLIPSE_SEGMENTS; i++) {
            double a1 = 2 * Math.PI * i / ELLIPSE_SEGMENTS;
            double a2 = 2 * Math.PI * (i + 1) / ELLIPSE_SEGMENTS;
            float px1 = (float) (ex + rx * Math.cos(a1));
            float py1 = (float) (ey + ry * Math.sin(a1));
            float px2 = (float) (ex + rx * Math.cos(a2));
            float py2 = (float) (ey + ry * Math.sin(a2));
            vc.addVertex(matrix, ex, ey, 0).setColor(1F, 1F, 1F, alpha);
            vc.addVertex(matrix, px1, py1, 0).setColor(1F, 1F, 1F, alpha);
            vc.addVertex(matrix, px2, py2, 0).setColor(1F, 1F, 1F, alpha);
            vc.addVertex(matrix, px2, py2, 0).setColor(1F, 1F, 1F, alpha);
        }
        float triBase = by + bubbleH;
        vc.addVertex(matrix, -TRIANGLE_SIZE, triBase, 0).setColor(1F, 1F, 1F, alpha);
        vc.addVertex(matrix, TRIANGLE_SIZE, triBase, 0).setColor(1F, 1F, 1F, alpha);
        vc.addVertex(matrix, 0F, triBase + TRIANGLE_SIZE, 0).setColor(1F, 1F, 1F, alpha);
        vc.addVertex(matrix, 0F, triBase + TRIANGLE_SIZE, 0).setColor(1F, 1F, 1F, alpha);
    }

    /** Renders the purchased item as a flat GUI icon, mirroring {@code GuiGraphics.renderItem} pose. */
    private static void drawItemIcon(LivingEntity entity, @Nullable String itemId,
                                     float cx, float cy, PoseStack poseStack,
                                     MultiBufferSource buffer, int packedLight) {
        if (itemId == null) return;
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) return;
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(rl));
        if (stack.isEmpty()) return;

        poseStack.pushPose();
        poseStack.translate(cx, cy, 0.5F);
        poseStack.scale(16F, -16F, 16F);
        ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
        BakedModel model = itemRenderer.getModel(stack, entity.level(), entity, 0);
        itemRenderer.render(stack, ItemDisplayContext.GUI, false, poseStack, buffer,
                packedLight, OverlayTexture.NO_OVERLAY, model);
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
