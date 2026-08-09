package com.wsteam.wandscape.tourist.client;

import javax.annotation.Nullable;

import com.wsteam.wandscape.shared.data.Activity;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;

/**
 * 游客活动（Activity）→ 表现注册表：骨骼目标角度 + 粒子规格。
 *
 * <p>动作是创作者可配数据（扫描器 marker 可设任意 SPOT_ACTIONS），渲染不能因未知值崩：
 * {@link #safeFor} 对未知/空活动兜底 BROWSE。
 *
 * <p>骨骼角度 = 相对原版 {@code HumanoidModel.setupAnim} 基础姿态的偏移（弧度）。
 * 进出平滑由 {@link TouristHumanoidModel} 的 blend 进度负责。
 */
public final class ActivityVisuals {

    /** 活动表现参数。armSwing = 周期抬放幅度（EAT 咀嚼等循环动画用）。 */
    public record Visuals(
            float headXRot, float headYRot,
            float bodyXRot,
            float armXRot, float armZRot,
            float legXRot,
            float armSwing,
            @Nullable ParticleSpec particles,
            /** true = 只动右手（主手持物，如 EAT 抬手到嘴边），左手保持自然下垂。 */
            boolean rightArmOnly
    ) {
        public static final Visuals NONE = new Visuals(0, 0, 0, 0, 0, 0, 0, null, false);
    }

    /** 粒子规格：发射的粒子类型 + 每次数量 + 散布半径。 */
    public record ParticleSpec(ParticleOptions type, int count, double spread) {
    }

    private ActivityVisuals() {
    }

    /** 活动 → 表现参数（空/未知 → BROWSE 兜底）。 */
    public static Visuals safeFor(@Nullable Activity a) {
        if (a == null) return Visuals.NONE;
        return switch (a) {
            case TRAVEL, QUEUE, SLEEP -> Visuals.NONE;
            case BROWSE -> new Visuals(0.12f, 0, 0, -0.2f, 0.1f, 0, 0, null, false);
            case EAT -> new Visuals(0.15f, 0, 0, -1.4f, 0.5f, 0, 0.18f, null, true);
            case BATHE -> new Visuals(0, 0, -0.05f, -1.0f, 0.6f, 0.15f, 0,
                    new ParticleSpec(ParticleTypes.BUBBLE_POP, 2, 0.3), false);
            case VIEW -> new Visuals(-0.35f, 0.25f, 0, -0.3f, 0.1f, 0, 0, null, false);
            case MEDITATE -> new Visuals(0.08f, 0, 0, -1.5f, 0.35f, 0.2f, 0,
                    new ParticleSpec(ParticleTypes.ENCHANT, 3, 0.4), false);
            case REST -> new Visuals(0.10f, 0, -0.05f, -0.4f, 0.2f, 0, 0, null, false);
            case WITHDRAW -> new Visuals(-0.15f, 0, 0, -0.9f, 0, 0, 0,
                    new ParticleSpec(ParticleTypes.HAPPY_VILLAGER, 3, 0.4), false);
        };
    }
}
