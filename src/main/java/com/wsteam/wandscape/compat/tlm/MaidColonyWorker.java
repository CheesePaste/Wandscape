package com.wsteam.wandscape.compat.tlm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.npc.worker.ColonyWorker;
import com.wsteam.wandscape.content.npc.worker.WorkerFx;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.UUID;

/**
 * 车万女仆的 {@link ColonyWorker} 适配器：让女仆接入殖民地工作链。
 *
 * <p>关键在于**导航走 Brain 的 {@code WALK_TARGET} 记忆，而不是直接 {@code getNavigation().moveTo()}**：
 * <ul>
 *   <li>TLM 把 vanilla 的 {@code MoveToTargetSink} 注册在恒活跃的 {@code Activity.CORE}
 *       （{@code MaidBrain.registerCoreGoals}），它读 {@code WALK_TARGET} 并驱动导航——这是女仆移动的正门。</li>
 *   <li>写这个记忆会顺带**阻断 TLM 那一族移动任务**（`MaidMoveToBlockTask` 把
 *       {@code WALK_TARGET, VALUE_ABSENT} 声明为启动前提），偷吃/种田/找家饭等行为因此无法启动，
 *       与我们的工作走位互斥免费拿到，不必用 mixin 去关它们。</li>
 * </ul>
 *
 * <p>导航速度与"够近"判定沿用 TLM 自己的 {@code setWalkAndLookTargetMemories} 口径。
 *
 * <p>**前置：女仆必须开启站位模式**（TLM 的 home mode）。否则 CORE 里的 {@code MaidFollowOwnerTask}
 * 会一直把她拉向主人——它声明的是 {@code WALK_TARGET, REGISTERED}（不是 ABSENT），挡不住。
 * 这一点由 {@code ColonyWorkerMaidTask.isEnable} 把关。
 *
 * <p>属性读 {@link MaidColonyState}（女仆的 vanilla 属性表不含本模组的自定义属性）；
 * 阶段一没有殖民地法术体系，{@link #canCastColonyMagic()} 与 {@link #tryEscapeCast} 恒 false，
 * 女仆不会接守卫/祭坛任务，导航卡死时也只回退为继续走路、不自传送。
 */
public final class MaidColonyWorker implements ColonyWorker {

    private final EntityMaid maid;
    private final UUID colonyId;

    public MaidColonyWorker(EntityMaid maid, UUID colonyId) {
        this.maid = maid;
        this.colonyId = colonyId;
    }

    public EntityMaid maid() {
        return maid;
    }

    @Override
    public LivingEntity entity() {
        return maid;
    }

    @Override
    public UUID workerId() {
        return maid.getUUID();
    }

    @Override
    public UUID colonyId() {
        return colonyId;
    }

    // ── 模式标志 ──
    // 女仆没有"跟随/休息"这对概念（跟随由 TLM 的 home mode 决定，工作模式下恒为站位态），
    // 全部恒 false 即"永远可以接活"——这正是调度器想要的。

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

    // ── 导航 ──

    @Override
    public void setAiWanderingEnabled(boolean enabled) {
        // 女仆的随机走动由 TLM 的 MaidBrain 统一挂（优先级 20），开关是 IMaidTask#enableLookAndRandomWalk
        // ——我们的任务在工作态恒返回 false，所以这里是空实现。不去碰 goalSelector（女仆是 Brain 驱动，
        // goalSelector 上没有她的行为）。
    }

    @Override
    public boolean isNavigationDone() {
        return maid.getNavigation().isDone();
    }

    @Override
    public boolean moveTo(BlockPos target, double speed) {
        // TLM 自己的移动任务用的就是这个入口（见 MaidMoveToBlockTask.searchForDestination）
        BehaviorUtils.setWalkAndLookTargetMemories(maid, target, (float) speed, 0);
        return true;
    }

    @Override
    public void stopNavigation() {
        maid.getNavigation().stop();
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }

    // ── 脱困施法：阶段一无魔法体系 ──

    @Override
    public boolean tryEscapeCast(String magicId, int baseCooldown, int manaCost, int lockTicks) {
        return false;
    }

    @Override
    public void markEscapeChanneling(long gameTime, int ticks) {
        // 无施法引导
    }

    // ── 属性与资源：读女仆自有状态容器 ──

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
        return state().effective(type);
    }

    @Override
    public float getEffectiveArmorValue() {
        return maid.getArmorValue();
    }

    @Override
    public void doWorkAnimation(BlockPos target) {
        WorkerFx.playWorkAnimation(maid, target);
    }

    @Override
    public boolean canCastColonyMagic() {
        // 阶段一：女仆没有殖民地法术（已学法术/策略槽/魔力都还没有），故不接守卫与祭坛任务——
        // 那两个执行器只认本模组法师，接了会「瞬间完成并空转」。阶段二给它接上魔法后翻成 true 即可。
        return false;
    }

    private MaidColonyState state() {
        return maid.getData(MaidColonyAttachments.MAID_COLONY_STATE.get());
    }
}
