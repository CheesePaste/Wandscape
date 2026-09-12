package com.wsteam.wandscape.content.npc.worker;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;

/**
 * 工作者共用表现：工作动作（挥手 + 目标点粒子）。
 *
 * <p>本模组法师与第三方工作者共用同一套表现，避免两处各写一份粒子参数后再各自漂移。
 */
public final class WorkerFx {

    private WorkerFx() {}

    /**
     * 播放一次工作动作：主手挥手（客户端+服务端），并在目标方块处撒 5 点粒子（仅服务端，
     * 由服务端同步给追踪客户端）。
     */
    public static void playWorkAnimation(LivingEntity entity, BlockPos target) {
        entity.swing(InteractionHand.MAIN_HAND);
        if (entity.level().isClientSide) return;
        var rand = entity.getRandom();
        for (int i = 0; i < 5; i++) {
            entity.level().addParticle(
                    ParticleTypes.WITCH,
                    target.getX() + 0.5 + (rand.nextDouble() - 0.5) * 0.5,
                    target.getY() + 0.5 + (rand.nextDouble() - 0.5) * 0.5,
                    target.getZ() + 0.5 + (rand.nextDouble() - 0.5) * 0.5,
                    0, 0, 0);
        }
    }
}
