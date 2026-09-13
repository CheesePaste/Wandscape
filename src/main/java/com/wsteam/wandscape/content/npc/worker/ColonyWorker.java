package com.wsteam.wandscape.content.npc.worker;

import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;

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
 * <p><b>实现成本刻意压到最低</b>：只有 {@link #entity()} 与 {@link #colonyId()} 两个抽象方法，
 * 其余全是 default。缺省行为就是「中立工作者」——原版寻路走位、中性属性、没有魔力与法术、
 * 共用 {@link WorkerFx} 的工作动作。本模组法师
 * （{@link com.wsteam.wandscape.content.npc.entity.WandscapeNpc}）是它的一个实现；
 * 第三方实体（如车万女仆）由 {@code compat/} 下的适配器实现，即可接入同一套殖民地工作链
 * （见 {@code docs/plan/touhou-little-maid-compat.md}）。**通用 {@link MobColonyWorker} 几乎
 * 不覆写任何方法，正是这些 default 的含义**——它们是"普通 Mob 当工人"的基线，不是空占位。
 *
 * <p>刻意**不**把 {@code Entity} 的通用能力放进接口：那部分走 {@link #entity()} 取底层实体即可
 * （坐标/世界/存活/粒子随机数都在 {@code Entity}/{@code LivingEntity} 上）。接口只收敛各实现
 * **必须自己回答**的那部分——实体是谁、属于哪个殖民地；其余按需覆写。
 */
public interface ColonyWorker {

    /**
     * 底层 MC 实体。所有与实体类型无关的通用操作（坐标、世界、{@code isRemoved}、
     * {@code getRandom}、{@code teleportTo}、{@code onGround}/{@code isInWater} 等）直接用它。
     *
     * <p>类型是 {@link Mob} 而非 {@code LivingEntity}：走位统一走原版寻路（见 {@link #moveTo}），
     * 这是接入的硬前提，{@code api/ColonyWorkerApi} 也在登记时就拒收非 Mob。
     */
    Mob entity();

    /** 实体 UUID（任务归属、日志、特效 id）。默认取 {@link #entity()} 的 UUID。 */
    default UUID workerId() {
        return entity().getUUID();
    }

    /** 所属殖民地；无归属时 null。 */
    @Nullable
    UUID colonyId();

    // ── 模式标志（默认恒 false = "永远可以接活"）──

    /** 是否处于跟随模式（跟随中的工作者不接殖民地任务）。 */
    default boolean isFollowMode() {
        return false;
    }

    /** 是否正在休息（小屋静养；休息中不接殖民地任务）。 */
    default boolean isResting() {
        return false;
    }

    /** 是否处于和平模式（不主动索敌）。 */
    default boolean isPeaceMode() {
        return false;
    }

    /**
     * 跟随目标的玩家 UUID；未跟随时 null。
     *
     * <p>返回 UUID 而非 Player：避免把"解析玩家"这一步压进接口——调用方本就有 ServerLevel，
     * 需要时自己 {@code getPlayerByUUID}。**注意守卫要自己带上**（存活 / 未被移除 / 实体表兜底），
     * 原先挂在 {@code WandscapeNpc.getFollowerPlayer()} 里的那几条不会跟着 UUID 一起过来。
     */
    @Nullable
    default UUID getFollowerUuid() {
        return null;
    }

    // ── 导航（默认直接驱动原版寻路，所有 PathfinderMob 通用）──
    //
    // 曾经考虑过让女仆走 Brain 的 WALK_TARGET 记忆通道（理由是它能顺带阻断 TLM 声明
    // WALK_TARGET ABSENT 的移动任务），被实测否决：TLM 在 CORE 里挂了 MaidAwaitTask（优先级 1，
    // 早于 MoveToTargetSink 的 2），它会擦掉**目标超出站位半径**的 WALK_TARGET 连同 PATH 记忆，
    // 而殖民地工地基本都在站位半径之外——结果是被钉死在原地一步不动。直驱既不写记忆、也不受
    // 该擦除影响。代价：第三方声明 WALK_TARGET ABSENT 的移动任务（偷吃等）不再被自动挡住。

    /**
     * 原生随机游荡开关（工作期间须关闭）。
     *
     * <p>默认空实现：**若你的实体有自己的游荡/逃跑/追击 AI，必须覆写**，否则它会与工作走位
     * 抢导航。走 Brain 的实体（如车万女仆）由它自己的任务行为统一关掉随机走动，覆写成空即可。
     */
    default void setAiWanderingEnabled(boolean enabled) {}

    /** 当前导航是否已结束（无在途路径）。 */
    default boolean isNavigationDone() {
        return entity().getNavigation().isDone();
    }

    /**
     * 请求走向目标（世界坐标，方块格）。返回 false 表示路径根本起不来
     * （区块未加载等），调用方据此走传送兜底。
     *
     * <p>落点口径为方块中心 {@code (x + 0.5, y + 1, z + 0.5)}。
     */
    default boolean moveTo(BlockPos target, double speed) {
        return entity().getNavigation().moveTo(
                target.getX() + 0.5, target.getY() + 1, target.getZ() + 0.5, speed);
    }

    /** 停止当前导航并清空路径。 */
    default void stopNavigation() {
        entity().getNavigation().stop();
    }

    // ── 脱困自传送（魔力法术）──
    // 默认"没有这个能力"：导航卡死时调用方回退为继续走路，而不是站等施法。

    /**
     * 尝试为脱困施展 {@code magicId}（扣魔力 / 上冷却 / 占施法锁）。
     * 返回 false 表示门控未通过（冷却/锁/魔力不足）或该实现**没有施法能力**——
     * 两种情况调用方都回退为继续走路，而不是站等。
     */
    default boolean tryEscapeCast(String magicId, int baseCooldown, int manaCost, int lockTicks) {
        return false;
    }

    /** 传送引导期间定身 + 减伤标记（与 {@link #tryEscapeCast} 的锁时长对齐）。 */
    default void markEscapeChanneling(long gameTime, int ticks) {}

    // ── 属性与资源（默认中性值）──
    // 来源由实现决定：WandscapeNpc 读 vanilla AttributeMap；车万女仆读自有状态容器
    // （她的 vanilla 属性表不含本模组注册的自定义属性）。

    /** 当前魔力。默认 0 = 不参与需要魔力门槛的任务。 */
    default float getCurrentMana() {
        return 0f;
    }

    /** 魔力上限。默认 0。 */
    default float getMaxMana() {
        return 0f;
    }

    /**
     * 有效属性值（任务评分与面板用）。默认恒 1（中性）。
     *
     * <p>没有本模组属性体系的工作者不该被写进 vanilla 属性表——{@code EntityAttributeCreationEvent}
     * 只在注册期生效，事后改不了已注册 {@code EntityType} 的供给器。要真正的成长就自己挂一份
     * 按 UUID 的自有存储（先例：车万女仆的 {@code MaidColonyState} 数据附件）。
     */
    default float getEffectiveAttribute(AttributeType type) {
        return 1f;
    }

    /** 有效护甲值。默认取原版有效值。 */
    default float getEffectiveArmorValue() {
        return entity().getArmorValue();
    }

    /** 工作动作表现（挥手 + 粒子）。默认走共用表现，避免各处各写一份粒子参数后漂移。 */
    default void doWorkAnimation(BlockPos target) {
        WorkerFx.playWorkAnimation(entity(), target);
    }

    /**
     * 该工作者能否承担**需要施放殖民地法术**的任务（守卫 {@code guard:attack}、祭坛施法）。
     * 默认 false。
     *
     * <p>这类任务的执行器（{@code GuardAttackExecutor} / {@code AltarCastExecutor}）只认本模组法师，
     * 拿不到法师时会立刻把任务判为完成——若让不具备该能力的工作者接取，会变成"接了不动、
     * 威胁没处理、源再发布"的空转。故调度侧用本方法把候选挡在门外（任务源在 {@code params} 里
     * 声明 {@code caster_only}，见 {@code SchedulerSystem}）。
     *
     * <p>本模组法师恒 true。没有殖民地法术体系的工作者返回 false——阶段二接上魔法后覆写成
     * true 即可自动接取守卫任务，无需再动调度器。
     */
    default boolean canCastColonyMagic() {
        return false;
    }

    /**
     * 任务面板里的**种类标签**（仅供 UI 区分图标/文案，无任何行为含义）：
     * 本模组法师 {@code "npc"}，其它模组登记的工作者用默认值 {@code "worker"}。
     *
     * <p>刻意用通用的 {@code "worker"} 而不是 {@code "maid"} 之类——把某个具体第三方模组的概念
     * 焊进通用 DTO 字段里，会让下一个接入的模组变成特例。
     */
    default String panelKind() {
        return "worker";
    }
}
