package com.wsteam.wandscape.compat.tlm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.npc.worker.ColonyWorker;
import com.wsteam.wandscape.content.npc.worker.WorkerFx;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * 车万女仆的 {@link ColonyWorker} 适配器：让女仆接入殖民地工作链。
 *
 * <p>**导航**走原版 {@code getNavigation()}，与法师的 {@code WandscapeNpc} 同机制。曾经试过走 Brain 的
 * {@code WALK_TARGET} 记忆通道，被实测否决——原因写在 {@link #moveTo} 上（TLM 的 `MaidAwaitTask`
 * 会擦掉超出女仆站位半径的行走目标，而工地基本都在站位半径之外）。
 *
 * <p>**前置：女仆必须开启 Home 模式**（TLM 的叫法，见其 lang `gui.touhou_little_maid.button.home.true`）。
 * 否则 CORE 里的 {@code MaidFollowOwnerTask} 会一直把她拉向主人（它声明的是
 * {@code WALK_TARGET, REGISTERED} 而非 ABSENT，挡不住），表现为在主人与工地之间来回横跳。
 * 这一点由 {@code ColonyWorkerMaidTask.isEnable} 把关。
 *
 * <p>**视觉/动画**：工作动作走 {@link #doWorkAnimation(BlockPos)}——这是留给工作动画的统一接口
 * （{@code ColonyWorker} 上定义、{@code AsyncTransformExecutor} 在每个变形操作时调用），
 * 当前女仆与法师共用 {@link WorkerFx} 的挥手 + 目标点粒子，**尚未做**朝向与施法射线那类持续表现。
 * 若要补，落点在 {@link TlmCompatImpl#onMaidTick}（每女仆 tick，可读 ECS 执行器的
 * {@code currentOpTarget}），对齐法师的 {@code WandscapeNpc.tickCastingState}。
 *
 * <p>属性读 {@link MaidColonyState}（女仆的 vanilla 属性表不含本模组的自定义属性）；
 * 阶段一没有殖民地法术体系，{@link #canCastColonyMagic()} 与 {@link #tryEscapeCast} 恒 false，
 * 女仆不会接守卫/祭坛任务，导航卡死时也只回退为继续走路、不自传送。
 */
public final class MaidColonyWorker implements ColonyWorker {

    private final EntityMaid maid;
    private final UUID colonyId;

    /** 工人模式的法杖是不是我们给的（只有是我们给的，离开时才清掉，不碰玩家自己给的东西）。 */
    private boolean gaveWand;

    public MaidColonyWorker(EntityMaid maid, UUID colonyId) {
        this.maid = maid;
        this.colonyId = colonyId;
    }

    public EntityMaid maid() {
        return maid;
    }

    public boolean gaveWand() {
        return gaveWand;
    }

    public void setGaveWand(boolean gaveWand) {
        this.gaveWand = gaveWand;
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
        // **直接驱动原版寻路**，与法师的 WandscapeNpc.moveTo 完全同机制。
        //
        // 曾经想过走 WALK_TARGET 记忆通道（理由是它能顺带阻断 TLM 声明 WALK_TARGET ABSENT 的移动任务），
        // 但实测打脸：TLM 在 CORE 里注册了 MaidAwaitTask（优先级 1，早于 MoveToTargetSink 的 2），
        // 它会把**目标超出女仆站位半径**的 WALK_TARGET 连同 PATH 记忆一起擦掉。而殖民地工地基本
        // 都在站位半径之外 —— 结果就是开了 Home 模式后女仆被钉死在原地、一步不动。
        // 直接驱动既不写记忆、也不受该擦除影响，走位表现与法师一致。
        //
        // 代价：TLM 那几个声明 WALK_TARGET ABSENT 的移动任务（偷吃等）因此不再被自动挡住。
        // 它们是条件触发 + 900 tick 节流的（附近有可食用方块才会启动），见文档 R1 残余项。
        return maid.getNavigation().moveTo(
                target.getX() + 0.5, target.getY() + 1, target.getZ() + 0.5, speed);
    }

    @Override
    public void stopNavigation() {
        maid.getNavigation().stop();
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
