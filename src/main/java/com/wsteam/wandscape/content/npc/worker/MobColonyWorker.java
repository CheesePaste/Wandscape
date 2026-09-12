package com.wsteam.wandscape.content.npc.worker;

import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 通用工作者适配器：把**任意普通 {@link Mob}** 登记为殖民地工人（{@code api/ColonyWorkerApi} 的默认实现）。
 *
 * <p>语义：
 * <ul>
 *   <li><b>走位</b>：原版寻路（{@code getNavigation()}），与法师的 {@code WandscapeNpc} 同一机制。</li>
 *   <li><b>属性</b>：中性常量——工作速度 1、其它本模组自定义属性 1、魔力 0（即不参与需要魔力门槛的任务）、
 *       护甲取原版有效值。这些生物没有本模组注册的自定义属性，也不该被写进它们的 vanilla 属性表
 *       （{@code EntityAttributeCreationEvent} 只在注册期生效，事后改不了已注册 EntityType 的供给器）。
 *       将来若要给第三方工作者真正的属性成长，在这里接一个按 UUID 的自有存储即可，接口不用改。</li>
 *   <li><b>移动抑制</b>：工作期间关掉 {@code goalSelector} 的 MOVE 控制位，否则它自己的游荡/逃跑/追击
 *       会与我们的工作走位互相打架。代价是它**不再自主追击或逃跑**——这是"当工人"的代价，
 *       {@link #setAiWanderingEnabled(boolean)} 传 true 时恢复（原版默认四个标志位全开，故恢复为 true 安全）。</li>
 *   <li><b>脱困施法</b>：不支持（{@link #tryEscapeCast} 恒 false）→ 导航卡死时回退为继续走路，
 *       不会自传送。有魔法体系的工作者自己实现这个能力。</li>
 * </ul>
 */
public final class MobColonyWorker implements ColonyWorker {

    /** 无属性体系时的中性工作速度（与 {@code WandscapeEntityOps.getWorkSpeed} 的兜底值一致）。 */
    private static final float NEUTRAL_WORK_SPEED = 1.0f;

    private final Mob mob;
    private UUID colonyId;
    private boolean movementSuppressed;

    public MobColonyWorker(Mob mob, UUID colonyId) {
        this.mob = mob;
        this.colonyId = colonyId;
    }

    public Mob mob() {
        return mob;
    }

    public void setColonyId(UUID colonyId) {
        this.colonyId = colonyId;
    }

    @Override
    public LivingEntity entity() {
        return mob;
    }

    @Override
    public UUID workerId() {
        return mob.getUUID();
    }

    @Override
    public UUID colonyId() {
        return colonyId;
    }

    @Override
    public boolean isFollowMode() {
        return false;
    }

    @Override
    public boolean isResting() {
        return false;
    }

    @Override
    public boolean isPeaceMode() {
        return false;
    }

    @Override
    public UUID getFollowerUuid() {
        return null;
    }

    @Override
    public void setAiWanderingEnabled(boolean enabled) {
        if (enabled == !movementSuppressed) return;
        movementSuppressed = !enabled;
        mob.goalSelector.setControlFlag(Goal.Flag.MOVE, enabled);
        if (!enabled) {
            mob.getNavigation().stop();
        }
    }

    @Override
    public boolean isNavigationDone() {
        return mob.getNavigation().isDone();
    }

    @Override
    public boolean moveTo(BlockPos target, double speed) {
        // 与 WandscapeNpc.moveTo 同口径：方块中心 (x+0.5, y+1, z+0.5)
        return mob.getNavigation().moveTo(
                target.getX() + 0.5, target.getY() + 1, target.getZ() + 0.5, speed);
    }

    @Override
    public void stopNavigation() {
        mob.getNavigation().stop();
    }

    @Override
    public boolean tryEscapeCast(String magicId, int baseCooldown, int manaCost, int lockTicks) {
        return false;
    }

    @Override
    public void markEscapeChanneling(long gameTime, int ticks) {
        // 无魔法体系，无引导标记
    }

    @Override
    public float getCurrentMana() {
        return 0f;
    }

    @Override
    public float getMaxMana() {
        return 0f;
    }

    @Override
    public float getEffectiveAttribute(AttributeType type) {
        return type == AttributeType.WORK_SPEED ? NEUTRAL_WORK_SPEED : 1.0f;
    }

    @Override
    public float getEffectiveArmorValue() {
        return mob.getArmorValue();
    }

    @Override
    public void doWorkAnimation(BlockPos target) {
        WorkerFx.playWorkAnimation(mob, target);
    }

    @Override
    public boolean canCastColonyMagic() {
        return false;
    }
}
