package com.wsteam.wandscape.npc.client;

import java.util.Locale;

import com.wsteam.wandscape.npc.entity.WandscapeNpc;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/**
 * Wandscape 法师人形模型：根据当前释放/蓄力的魔法类型，智能匹配玩家同款专属施法姿态。
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
            String spellId = entity.getCastSpellId().toLowerCase(Locale.ROOT);

            // 1. 经典黑洞开天姿态（charge_black_hole）：双手在胸前微弯成环形张开，高频剧烈虚空震颤
            if (spellId.contains("black_hole") || spellId.contains("void") || spellId.contains("singularity")) {
                float pulse = Mth.sin(ageInTicks * 0.75f) * 0.08f;
                float bob = Mth.cos(ageInTicks * 0.40f) * 0.04f;

                this.rightArm.xRot = -1.25f + pitchRad * 0.35f + pulse;
                this.rightArm.yRot = -0.45f + bob;
                this.rightArm.zRot = 0.40f;

                this.leftArm.xRot = -1.25f + pitchRad * 0.35f - pulse;
                this.leftArm.yRot = 0.45f - bob;
                this.leftArm.zRot = -0.40f;

                this.head.xRot = (float) Math.toRadians(headPitch) - 0.12f;
                this.head.yRot = (float) Math.toRadians(netHeadYaw);
                return;
            }

            // 2. 持续光束/射线喷射姿态（continuous_thrust / beam）：双臂笔直向前瞄准锁定，伴随高频后坐力震颤
            if (spellId.contains("beam") || spellId.contains("ray") || spellId.contains("frost")
                    || spellId.contains("breath") || spellId.contains("electrocute") || spellId.contains("continuous")) {
                float recoil = Mth.sin(ageInTicks * 0.90f) * 0.035f;

                this.rightArm.xRot = -1.55f + pitchRad + recoil;
                this.rightArm.yRot = 0.08f;
                this.rightArm.zRot = 0.02f;

                this.leftArm.xRot = -1.55f + pitchRad + recoil;
                this.leftArm.yRot = -0.08f;
                this.leftArm.zRot = -0.02f;

                this.head.xRot = (float) Math.toRadians(headPitch);
                this.head.yRot = (float) Math.toRadians(netHeadYaw);
                return;
            }

            // 3. 天降流星/风暴召唤姿态（continuous_overhead / meteor）：双臂直插云霄向天召唤，头部仰视
            if (spellId.contains("meteor") || spellId.contains("starfall") || spellId.contains("storm")
                    || spellId.contains("blizzard") || spellId.contains("overhead")) {
                float sway = Mth.sin(ageInTicks * 0.35f) * 0.06f;

                this.rightArm.xRot = -2.75f + sway;
                this.rightArm.yRot = 0.20f;
                this.rightArm.zRot = 0.35f;

                this.leftArm.xRot = -2.75f - sway;
                this.leftArm.yRot = -0.20f;
                this.leftArm.zRot = -0.35f;

                this.head.xRot = -0.55f; // 仰望苍穹
                this.head.yRot = (float) Math.toRadians(netHeadYaw) * 0.5f;
                return;
            }

            // 4. 自我祝福/圣光护体姿态（instant_self / heal / defense）：双手交叉护胸虔诚祈福
            if (spellId.contains("heal") || spellId.contains("bless") || spellId.contains("shield")
                    || spellId.contains("oakskin") || spellId.contains("fortify") || spellId.contains("angel")) {
                float breathe = Mth.sin(ageInTicks * 0.25f) * 0.03f;

                this.rightArm.xRot = -0.85f + breathe;
                this.rightArm.yRot = -0.45f;
                this.rightArm.zRot = 0.25f;

                this.leftArm.xRot = -0.85f + breathe;
                this.leftArm.yRot = 0.45f;
                this.leftArm.zRot = -0.25f;

                this.head.xRot = (float) Math.toRadians(headPitch) * 0.5f + 0.15f; // 虔诚低头
                this.head.yRot = (float) Math.toRadians(netHeadYaw) * 0.5f;
                return;
            }

            // 5. 蓄力标枪/大火球投掷姿态（charged_throw / fireball / lance）：右手引力后撤蓄力，左手向前瞄准
            if (spellId.contains("fireball") || spellId.contains("lance") || spellId.contains("icicle")
                    || spellId.contains("missile") || spellId.contains("petrify") || spellId.contains("arrow")) {
                float charge = Mth.sin(ageInTicks * 0.50f) * 0.05f;

                this.rightArm.xRot = -0.65f + pitchRad * 0.40f + charge;
                this.rightArm.yRot = -0.55f;
                this.rightArm.zRot = 0.55f;

                this.leftArm.xRot = -1.45f + pitchRad;
                this.leftArm.yRot = 0.20f;
                this.leftArm.zRot = -0.10f;

                this.head.xRot = (float) Math.toRadians(headPitch);
                this.head.yRot = (float) Math.toRadians(netHeadYaw);
                return;
            }

            // 6. 通用高阶施法姿态：右手持杖瞄准目标，左手协同环抱奥术焦点
            float pulse = Mth.sin(ageInTicks * 0.45f) * 0.035f;
            this.rightArm.xRot = (float) (WandscapeNpc.CAST_ARM_ANGLE + pitchRad + pulse);
            this.rightArm.yRot = 0.12f;
            this.rightArm.zRot = 0.05f;

            this.leftArm.xRot = -1.15f + pitchRad * 0.5f - pulse;
            this.leftArm.yRot = 0.35f;
            this.leftArm.zRot = -0.22f;

            this.head.xRot = (float) Math.toRadians(headPitch);
            this.head.yRot = (float) Math.toRadians(netHeadYaw);
        }
    }
}
