package com.wsteam.wandscape.content.npc.client;

import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
public class WizardHatModel extends HumanoidModel<WandscapeNpc> {

    public WizardHatModel(ModelPart root) {
        super(root);
    }

    public ModelPart getHatBrimEdge() {
        return head.getChild("hat_brim_edge");
    }

    public ModelPart getHatBrimInner() {
        return head.getChild("hat_brim_inner");
    }

    public ModelPart getHatBody() {
        return head.getChild("hat_body");
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0f);
        PartDefinition root = mesh.getRoot();

        PartDefinition head = root.addOrReplaceChild("head",
                CubeListBuilder.create(),
                PartPose.ZERO);

        // Brim edge: outer ring (rendered untinted, gold texture)
        head.addOrReplaceChild("hat_brim_edge",
                CubeListBuilder.create()
                        .texOffs(0, 0)
                        .addBox(-5.5f, -9.0f, -5.5f, 11.0f, 1.0f, 11.0f),
                PartPose.ZERO);

        // Brim inner: slightly smaller, same Y (rendered tinted with body color)
        head.addOrReplaceChild("hat_brim_inner",
                CubeListBuilder.create()
                        .texOffs(0, 44)
                        .addBox(-4.5f, -9.0f, -4.5f, 9.0f, 1.0f, 9.0f, new CubeDeformation(0.01f)),
                PartPose.ZERO);

        // Hat body: cone layers (rendered with grayscale texture + tint)
        PartDefinition body = head.addOrReplaceChild("hat_body",
                CubeListBuilder.create()
                        .texOffs(0, 12)
                        .addBox(-3.5f, -5.0f, -3.5f, 7.0f, 5.0f, 7.0f),
                PartPose.offset(0.0f, -9.0f, 0.0f));

        PartDefinition layer2 = body.addOrReplaceChild("hat_layer2",
                CubeListBuilder.create()
                        .texOffs(0, 24)
                        .addBox(-2.0f, -4.0f, -2.0f, 4.0f, 4.0f, 4.0f),
                PartPose.offsetAndRotation(0.0f, -5.0f, 0.0f,
                        -0.06f, 0.0f, 0.03f));

        layer2.addOrReplaceChild("hat_tip",
                CubeListBuilder.create()
                        .texOffs(0, 32)
                        .addBox(-1.0f, -3.0f, -1.0f, 2.0f, 3.0f, 2.0f, new CubeDeformation(0.2f)),
                PartPose.offsetAndRotation(0.0f, -4.0f, 0.0f,
                        -0.1f, 0.0f, 0.06f));

        return LayerDefinition.create(mesh, 64, 64);
    }
}
