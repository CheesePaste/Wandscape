package com.wsteam.wandscape.content.warehouse.transport;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

public class TransportItemEntityRenderer extends ItemEntityRenderer {

    private static final double MAX_BUBBLE_DISTANCE_SQR = 24.0 * 24.0; // 24 blocks max for bubble billboard

    private static final int SEGMENTS = 16;
    private static final float[] COS_TABLE = new float[SEGMENTS + 1];
    private static final float[] SIN_TABLE = new float[SEGMENTS + 1];

    static {
        for (int i = 0; i <= SEGMENTS; i++) {
            double angle = 2.0 * Math.PI * i / SEGMENTS;
            COS_TABLE[i] = (float) Math.cos(angle);
            SIN_TABLE[i] = (float) Math.sin(angle);
        }
    }

    public TransportItemEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(net.minecraft.world.entity.item.ItemEntity entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        // Render the spinning item
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);

        if (entity instanceof TransportItemEntity transportEntity) {
            int count = transportEntity.getItem().getCount();
            if (count > 0 && this.entityRenderDispatcher.distanceToSqr(transportEntity) <= MAX_BUBBLE_DISTANCE_SQR) {
                renderBubble(transportEntity, count, poseStack, buffer, packedLight);
            }
        }
    }

    private void renderBubble(TransportItemEntity entity, int count, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        
        // Offset above the item
        poseStack.translate(0.0F, 0.85F, 0.0F);
        
        // Billboard (face camera)
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        
        // Scale down the text and bubble
        float scale = 0.03F;
        poseStack.scale(scale, -scale, scale);
        
        Matrix4f matrix = poseStack.last().pose();
        Font font = this.getFont();
        Component text = Component.literal("x" + count);
        
        float textWidth = font.width(text);
        float textHeight = font.lineHeight;
        
        float paddingX = 5.0F;
        float paddingY = 3.0F;
        float bubbleW = textWidth + paddingX * 2;
        float bubbleH = textHeight + paddingY * 2;
        
        float ex = 0F;
        float ey = -bubbleH / 2F;
        float rx = bubbleW / 2F;
        float ry = bubbleH / 2F;
        
        VertexConsumer vc = buffer.getBuffer(net.minecraft.client.renderer.RenderType.debugQuads());
        
        // 1. Render Gold Outline (slightly larger ellipse)
        float border = 0.8F;
        float orx = rx + border;
        float ory = ry + border;
        for (int i = 0; i < SEGMENTS; i++) {
            float px1 = ex + orx * COS_TABLE[i];
            float py1 = ey + ory * SIN_TABLE[i];
            float px2 = ex + orx * COS_TABLE[i + 1];
            float py2 = ey + ory * SIN_TABLE[i + 1];
            
            vc.addVertex(matrix, ex, ey, 0.0F).setColor(0.78F, 0.61F, 0.23F, 0.9F); // Gold color
            vc.addVertex(matrix, px1, py1, 0.0F).setColor(0.78F, 0.61F, 0.23F, 0.9F);
            vc.addVertex(matrix, px2, py2, 0.0F).setColor(0.78F, 0.61F, 0.23F, 0.9F);
            vc.addVertex(matrix, px2, py2, 0.0F).setColor(0.78F, 0.61F, 0.23F, 0.9F);
        }
        
        // 2. Render Dark Background (inner ellipse, offset Z slightly forward to prevent z-fighting)
        float zOffset = 0.02F;
        for (int i = 0; i < SEGMENTS; i++) {
            float px1 = ex + rx * COS_TABLE[i];
            float py1 = ey + ry * SIN_TABLE[i];
            float px2 = ex + rx * COS_TABLE[i + 1];
            float py2 = ey + ry * SIN_TABLE[i + 1];
            
            vc.addVertex(matrix, ex, ey, zOffset).setColor(0.12F, 0.12F, 0.14F, 0.85F); // Dark grey
            vc.addVertex(matrix, px1, py1, zOffset).setColor(0.12F, 0.12F, 0.14F, 0.85F);
            vc.addVertex(matrix, px2, py2, zOffset).setColor(0.12F, 0.12F, 0.14F, 0.85F);
            vc.addVertex(matrix, px2, py2, zOffset).setColor(0.12F, 0.12F, 0.14F, 0.85F);
        }
        
        // 3. Render Text (offset Z slightly more forward)
        poseStack.translate(0, 0, zOffset * 2);
        Matrix4f textMatrix = poseStack.last().pose();
        
        float textX = -textWidth / 2F;
        float textY = ey - textHeight / 2F;
        int textColor = 0xFFFFFFFF; // Warm white text
        
        font.drawInBatch(text, textX, textY, textColor, false, textMatrix, buffer,
                Font.DisplayMode.POLYGON_OFFSET, 0, packedLight);
                
        poseStack.popPose();
    }
}
