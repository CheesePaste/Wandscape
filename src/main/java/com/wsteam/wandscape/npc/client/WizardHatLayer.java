package com.wsteam.wandscape.npc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
public class WizardHatLayer extends RenderLayer<WandscapeNpc, HumanoidModel<WandscapeNpc>> {

    private static final ResourceLocation HAT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("wandscape", "textures/entity/wizard_hat.png");

    private final ModelPart hatBrimEdge;
    private final ModelPart hatBrimInner;
    private final ModelPart hatBody;

    public WizardHatLayer(RenderLayerParent<WandscapeNpc, HumanoidModel<WandscapeNpc>> parent,
                          WizardHatModel hatModel) {
        super(parent);
        this.hatBrimEdge = hatModel.getHatBrimEdge();
        this.hatBrimInner = hatModel.getHatBrimInner();
        this.hatBody = hatModel.getHatBody();
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       WandscapeNpc entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (entity.isInvisible()) return;

        int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0.0f);
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(HAT_TEXTURE));

        poseStack.pushPose();
        getParentModel().head.translateAndRotate(poseStack);

        int color = entity.getHatColor();
        if (color == 0) {
            color = 0xFF5030A0;
        }

        // Brim edge: untinted gold from texture
        hatBrimEdge.render(poseStack, vc, packedLight, overlay, 0xFFFFFFFF);
        // Brim inner: tinted with hat color
        hatBrimInner.render(poseStack, vc, packedLight, overlay, color);
        // Hat body: tinted
        hatBody.render(poseStack, vc, packedLight, overlay, color);

        poseStack.popPose();
    }
}
