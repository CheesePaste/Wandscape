package com.wsteam.wandscape.content.magic.worldresponse;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelAccessor;

import javax.annotation.Nullable;

/**
 * 一个**持续的**世界回应（世界应答里那些「让世界保持某种状态」的回应）。
 *
 * <p>和一次性的回应（向前/扶摇/裁决那种点完就完事）分开：这些有开始、有每 tick 的维持、也有
 * 主动停止时的回滚。持续时间可以先做成无限（见企划案），但**必须能主动关掉**——
 * 关闭入口见 {@link WorldResponseEffects#stopAll}（配套魔法《平息》走的就是它）。
 *
 * <p>实现约定：{@link #stop} 必须幂等且是唯一的回滚入口（停效果、断线、换维度、传送、关服都走它），
 * 不允许把回滚逻辑写在别处——否则会出现「效果没了但地形没还」的孤儿状态。
 * 回滚**不许丢快照**：区块没加载就留着等下次，见 {@link #stop} 的返回值约定。
 */
public interface WorldResponseEffect {

    /** 稳定 id（同 id 的效果对同一玩家只允许存在一个，重复激活会被拒）。 */
    String id();

    /** 每 tick 推进（内部自己限流；不需要每 tick 做事的实现请自带计数器）。 */
    void tick(ServerPlayer player);

    /**
     * 这个位置是不是正被本效果**借用**（临时改过、还没还回去）。
     *
     * <p>管理器据此**禁止第三方改动它**：破坏、放置、以及流体在那一格造方块（黑曜石/石头之类）
     * 都会被取消。理由是借出去的位置一旦被改写，回滚时就分不清「该还原成什么」——轻则水位永久
     * 回不去，重则把别人的建造覆盖掉。
     *
     * @param level 事件所在的维度。效果是按维度绑定的，实现方必须拿它跟自己的 level 比一下，
     *              否则两个维度里同一坐标会互相误判
     */
    default boolean holds(LevelAccessor level, BlockPos pos) {
        return false;
    }

    /** 默认借用优先级（扶摇这类「给世界加东西」的回应）。 */
    int PRIORITY_DEFAULT = 0;
    /** 移山填海：**世界让路的权限最高**——它要清开的格子，低优先级的回应一律让。 */
    int PRIORITY_TERRAFORM = 1;

    /**
     * 借用优先级：**数值大的可以先手**。一格同一时刻只归一个效果，但优先级更高的那个可以要求
     * 更低的效果先把这一格还回原位，然后自己接管（实现见 {@link WorldResponseEffects#releaseFor}）。
     *
     * <p>当前口径：移山填海（{@link #PRIORITY_TERRAFORM}）> 扶摇（{@link #PRIORITY_DEFAULT}）。
     * 与设计初衷一致——「世界为你让路」是最高指令，它要清开的地方，连我们自己刚铺的台阶也得让开；
     * 反过来扶摇不许把台阶/平台铺进移山填海正在用的格子里（那会把刚让开的路堵回去）。
     */
    default int borrowPriority() {
        return PRIORITY_DEFAULT;
    }

    /**
     * 立刻把这一格还回原位并放弃它（给优先级更高的效果腾位置）。
     *
     * <p>默认不借任何格子，所以什么都不用做。自己借了格子的实现必须**先还原再放手**：直接让高优先级
     * 去读当前状态的话，读到的是我们留下的方块，会被当成"原位"记下来，最后回滚出一个谁都没见过的方块。
     *
     * @param level 事件所在维度（不是自己的维度就什么都不做）
     */
    default void release(LevelAccessor level, BlockPos pos) {}

    /**
     * 生效期间是否让施法者免疫**环境热伤害**（岩浆 / 岩浆块 / 站在火里 / 身上着火）。
     *
     * <p>默认不免疫。实现方不用自己接伤害事件——{@link WorldResponseEffects} 会在伤害进来时问一句，
     * 这样「让路顺便别把人烫伤」这件事只有一份判断。
     */
    default boolean wardsHeat() {
        return false;
    }

    /**
     * 停止并回滚到生效前的状态；幂等，可重复调用。
     *
     * @param player     触发这次回滚的玩家；登出/关服之后补做的回滚会传 {@code null}
     * @param loadChunks {@code true} 时允许为了把方块还回去而把未加载的区块读回来——只用在关服那一次收尾；
     *                   常规路径一律 {@code false}，也就是**绝不为回滚同步加载区块**（那是几十毫秒的卡顿，
     *                   而且会顺带把玩家根本没去过的区块拉进内存）。还不了就先留着记录。
     * @return {@code true} = 已经完全还干净；{@code false} = 还有方块压在未加载的区块里，
     *         管理器会稍后重试——**实现方不许在这种情况下把快照丢掉**
     */
    boolean stop(@Nullable ServerPlayer player, boolean loadChunks);
}
