package com.wsteam.wandscape.npc.entity;

import com.wsteam.wandscape.guard.executor.GuardCombat;
import com.wsteam.wandscape.magic.internal.MagicCaster;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

/**
 * 邪恶法师施法战斗 goal：每 tick 一轮（施法门控在 {@code MagicCaster} 内部原子复验，
 * CD/互斥锁/蓝不足时自动放弃本次尝试，不会刷屏）。
 *
 * <p>目标为 {@code NearestAttackableTargetGoal} 选出的最近生存玩家：
 * <ul>
 *   <li>LOS 通 → 站定，{@link GuardCombat#engage} 施法（光束重定向/法阵/音效全复用；
 *       {@code world=null} 使 ECS 寻路调用自动跳过，敌对法师用原版导航）。</li>
 *   <li>LOS 挡 → 原版寻路向玩家移动（追着绕墙），施法姿态放下。</li>
 *   <li>目标失效（死亡/离开视野范围）→ 停手清姿态。</li>
 * </ul>
 * 施法姿态（{@code isCasting}）按施法互斥锁（{@code magic.getLockTicks()}）/活跃光束驱动，
 * 法阵出现到光束消失全程举杖；{@code setDebugTarget} 供客户端施法射线粒子。
 */
public class EvilMageCastGoal extends Goal {

    private static final double CHASE_SPEED = 1.0;

    private final EvilMage mage;

    public EvilMageCastGoal(EvilMage mage) {
        this.mage = mage;
    }

    @Override
    public boolean canUse() {
        return validTarget();
    }

    @Override
    public boolean canContinueToUse() {
        return validTarget();
    }

    /** 目标必须是存活生存玩家（创造/旁观免疫，且与索敌谓词一致）。 */
    private boolean validTarget() {
        LivingEntity target = mage.getTarget();
        return target instanceof Player player
                && player.isAlive() && !player.isRemoved()
                && !player.isCreative() && !player.isSpectator();
    }

    @Override
    public void tick() {
        if (!(mage.level() instanceof ServerLevel level)) return;
        LivingEntity target = mage.getTarget();
        if (target == null || target.isRemoved() || !target.isAlive()) {
            stop();
            return;
        }

        boolean los = GuardCombat.hasLineOfSight(mage, target);
        // engage 负责：光束重定向 / LOS 判定（隔墙时淡出光束）/ CastBrain 选魔法 / 施法视觉
        GuardCombat.engage(level, mage, target, null, -1,
                MagicCaster.beamCircleId(), MagicCaster.beamColor());

        if (los) {
            mage.getNavigation().stop();
            boolean casting = mage.magic.getLockTicks() > 0
                    || GuardCombat.findActiveBeam(level, mage) != null;
            mage.setCasting(casting);
            mage.setDebugTarget(target.blockPosition());
        } else {
            mage.getNavigation().moveTo(target, CHASE_SPEED);
            mage.setCasting(false);
            mage.setDebugTarget(null);
        }
    }

    @Override
    public void stop() {
        mage.setCasting(false);
        mage.setDebugTarget(null);
    }
}
