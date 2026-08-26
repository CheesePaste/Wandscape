package com.wsteam.wandscape.npc.client;

import com.wsteam.wandscape.npc.entity.WandscapeNpc;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/**
 * Wandscape 法师人形模型：处理行走、持杖、施法与蓄力引导姿态。
 */
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
            float pulse = Mth.sin(ageInTicks * 0.45f) * 0.035f;

            // 右手：高举法杖并精准指向施法仰角，伴随魔力充能微颤
            this.rightArm.xRot = (float) (WandscapeNpc.CAST_ARM_ANGLE + pitchRad + pulse);
            this.rightArm.yRot = 0.12f;
            this.rightArm.zRot = 0.05f;

            // 左手：协同进入法术引导手势（向前弧形抬起，聚集奥术能量）
            this.leftArm.xRot = -1.15f + pitchRad * 0.5f - pulse;
            this.leftArm.yRot = 0.35f;
            this.leftArm.zRot = -0.22f;

            // 躯干与头部朝向锁定
            this.head.xRot = (float) Math.toRadians(headPitch);
            this.head.yRot = (float) Math.toRadians(netHeadYaw);
        }
    }
}
