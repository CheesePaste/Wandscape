package com.wsteam.wandscape.tourist.client;

import com.wsteam.wandscape.shared.data.Activity;
import com.wsteam.wandscape.tourist.entity.TouristEntity;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/**
 * 游客模型：按当前 Activity 插值姿态（浏览/用餐/泡澡/看展/冥想/取现），进出平滑。
 *
 * <p>{@link ActivityVisuals} 定义每个活动的骨骼目标角度（相对原版基础姿态的偏移）；
 * 本模型用 blend 进度（活动开始 10 tick 渐入、结束渐出）做平滑过渡，不瞬移。
 * 未知动作由 ActivityVisuals 兜底 BROWSE，渲染不崩。
 */
public class TouristHumanoidModel extends HumanoidModel<TouristEntity> {

    private static final float BLEND_STEP = 0.1f;

    /** 上一个活动（变化时重置 blend 进度）。 */
    private Activity lastActivity;
    /** 当前活动姿态混合进度 0..1。 */
    private float blend;

    public TouristHumanoidModel(ModelPart root) {
        super(root);
    }

    @Override
    public void setupAnim(TouristEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

        Activity activity = entity.getCurrentActivity();
        if (activity != lastActivity) {
            lastActivity = activity;
            // 活动开始/结束瞬间 blend 已落后 → 直接跳到目标比例，避免整段缓慢爬升
            blend = activity == null ? 0f : 1f;
        }
        if (activity != null) {
            blend = Math.min(1f, blend + BLEND_STEP);
        } else {
            blend = Math.max(0f, blend - BLEND_STEP);
        }
        if (blend <= 0f) return;

        float t = (float) Mth.smoothstep(blend);
        ActivityVisuals.Visuals v = ActivityVisuals.safeFor(activity);
        head.xRot += v.headXRot() * t;
        head.yRot += v.headYRot() * t;
        body.xRot += v.bodyXRot() * t;
        // EAT 等 rightArmOnly：只抬右手（主手持物到嘴边），左手保持自然
        if (v.rightArmOnly()) {
            rightArm.xRot += v.armXRot() * t;
            rightArm.zRot += v.armZRot() * t;
        } else {
            rightArm.xRot += v.armXRot() * t;
            leftArm.xRot += v.armXRot() * t;
            rightArm.zRot += v.armZRot() * t;
            leftArm.zRot += -v.armZRot() * t;
        }
        rightLeg.xRot += v.legXRot() * t;
        leftLeg.xRot += v.legXRot() * t;

        // 循环动画（周期摆动，与 Blender/GeckoLib 无关的原版正弦关键帧）
        if (v.armSwing() > 0f) {
            float swing = Mth.sin(ageInTicks * 0.3f) * v.armSwing();
            rightArm.xRot += swing;
            if (!v.rightArmOnly()) {
                leftArm.xRot += swing;
            }
        }
        if (activity == Activity.VIEW) {
            // 看展：缓慢转身扫视
            head.yRot += Mth.sin(ageInTicks * 0.1f) * 0.3f * t;
        } else if (activity == Activity.BROWSE) {
            // 浏览：小幅左右打量
            head.yRot += Mth.sin(ageInTicks * 0.15f) * 0.2f * t;
        }

        // super.setupAnim 里 hat.copyFrom(head) 在我们改 head 之前就执行了；
        // 重新拷贝一次让外层头发层随头部扫视/打量一起转。
        this.hat.copyFrom(this.head);
    }
}
