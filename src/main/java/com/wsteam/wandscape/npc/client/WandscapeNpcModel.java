package com.wsteam.wandscape.npc.client;

import com.wsteam.wandscape.npc.entity.WandscapeNpc;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;

public class WandscapeNpcModel extends HumanoidModel<WandscapeNpc> {

    public WandscapeNpcModel(ModelPart root) {
        super(root);
    }

    @Override
    public void setupAnim(WandscapeNpc entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

        if (entity.isCasting()) {
            float pitchRad = (float) Math.toRadians(entity.getXRot());
            this.rightArm.xRot = -1.2f + pitchRad;
            this.rightArm.yRot = 0.15f;
            this.rightArm.zRot = 0.0f;
        }
    }
}
