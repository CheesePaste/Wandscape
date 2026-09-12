package com.wsteam.wandscape.content.npc.worker;

import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 殖民地工作者：ECS 任务链与 MC 实体之间的解析缝。
 *
 * <p>任务引擎的原子操作接口 {@code OpExecutor#execute(op, world, long ecsId)} 本来就只收一个
 * {@code ecsId}——类型墙只在「ecsId → 实体」这一个解析点上。本接口就是那个解析点的返回类型：
 * MC 边界适配器（{@code WandscapeEntityOps} / {@code WandscapeMovementOps} /
 * {@code NavigationSystem} / {@code WandscapeBlockInteractExecutor} / {@code AsyncTransformExecutor} /
 * {@code ResourceRequestExecutor} / {@code WandscapeRitualOps}）全部经它访问实体，而不是直接
 * {@code instanceof WandscapeNpc}。
 *
 * <p>本模组法师 {@link com.wsteam.wandscape.content.npc.entity.WandscapeNpc} 是它的一个实现；
 * 第三方实体（如车万女仆）由 {@code compat/} 下的适配器实现，即可接入同一套殖民地工作链
 * （见 {@code docs/plan/touhou-little-maid-compat.md}）。
 *
 * <p>刻意**不**把 {@code Entity} 的通用能力放进接口：那部分走 {@link #entity()} 取底层实体即可
 * （坐标/世界/存活/粒子随机数都在 {@code Entity}/{@code LivingEntity} 上）。接口只收敛各实现
 * **必须自己回答**的那部分——导航施加方式、属性来源、脱困施法能力。
 */
public interface ColonyWorker {

    /**
     * 底层 MC 实体。所有与实体类型无关的通用操作（坐标、世界、{@code isRemoved}、
     * {@code getRandom}、{@code teleportTo}、{@code onGround}/{@code isInWater} 等）直接用它。
     */
    LivingEntity entity();

    /** 实体 UUID（任务归属、日志、特效 id）。 */
    UUID workerId();

    /** 所属殖民地；无归属时 null。 */
    @Nullable UUID colonyId();

    /** 是否处于跟随模式（跟随中的工作者不接殖民地任务）。 */
    boolean isFollowMode();

    /** 是否正在休息（小屋静养；休息中不接殖民地任务）。 */
    boolean isResting();

    /** 是否处于和平模式（不主动索敌）。 */
    boolean isPeaceMode();

    /** 跟随目标的玩家 UUID；未跟随时 null。（返回 UUID 而非 Player：避免把"解析玩家"这一步
     *  压进接口——调用方本就有 ServerLevel，需要时自己 {@code getPlayerByUUID}。） */
    @Nullable UUID getFollowerUuid();

    // ── 导航 ──
    // 各实现的施加机制不同，故下沉到实现：
    //   WandscapeNpc  → 直接 getNavigation().moveTo(...)
    //   车万女仆      → 写 Brain 的 WALK_TARGET 记忆，由 CORE 的 MoveToTargetSink 落地
    //                   （写该记忆会顺带阻断 TLM 声明 WALK_TARGET ABSENT 的移动任务族）

    /** 原生随机游荡开关（工作期间须关闭）。 */
    void setAiWanderingEnabled(boolean enabled);

    /** 当前导航是否已结束（无在途路径）。 */
    boolean isNavigationDone();

    /**
     * 请求走向目标（世界坐标，方块格）。返回 false 表示路径根本起不来
     * （区块未加载等），调用方据此走传送兜底。
     */
    boolean moveTo(BlockPos target, double speed);

    /** 停止当前导航并清空路径。 */
    void stopNavigation();

    // ── 脱困自传送（魔力法术）──

    /**
     * 尝试为脱困施展 {@code magicId}（扣魔力 / 上冷却 / 占施法锁）。
     * 返回 false 表示门控未通过（冷却/锁/魔力不足）或该实现**没有施法能力**——
     * 两种情况调用方都回退为继续走路，而不是站等。
     *
     * <p>无魔法体系的实现（如仅工作态的车万女仆）恒返回 false 即可。
     */
    boolean tryEscapeCast(String magicId, int baseCooldown, int manaCost, int lockTicks);

    /** 传送引导期间定身 + 减伤标记（与 {@link #tryEscapeCast} 的锁时长对齐）。 */
    void markEscapeChanneling(long gameTime, int ticks);

    // ── 属性与资源 ──
    // 来源由实现决定：WandscapeNpc 读 vanilla AttributeMap；车万女仆读自有状态容器
    // （她的 vanilla 属性表不含本模组注册的自定义属性）。

    float getCurrentMana();

    float getMaxMana();

    float getEffectiveAttribute(AttributeType type);

    float getEffectiveArmorValue();

    /** 工作动作表现（挥手 + 粒子）。 */
    void doWorkAnimation(BlockPos target);

    /**
     * 该工作者能否承担**需要施放殖民地法术**的任务（守卫 {@code guard:attack}、祭坛施法）。
     *
     * <p>这类任务的执行器（{@code GuardAttackExecutor} / {@code AltarCastExecutor}）只认本模组法师，
     * 拿不到法师时会立刻把任务判为完成——若让不具备该能力的工作者接取，会变成"接了不动、
     * 威胁没处理、源再发布"的空转。故调度侧用本方法把候选挡在门外（任务源在 {@code params} 里
     * 声明 {@code caster_only}，见 {@code SchedulerSystem}）。
     *
     * <p>本模组法师恒 true。没有殖民地法术体系的工作者（如阶段一的车万女仆、通用外部工作者）
     * 返回 false——阶段二女仆接上魔法后把它翻成 true 即可自动接取守卫任务，无需再动调度器。
     */
    boolean canCastColonyMagic();
}
