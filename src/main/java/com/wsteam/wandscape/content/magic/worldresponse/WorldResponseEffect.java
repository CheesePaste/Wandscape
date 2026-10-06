package com.wsteam.wandscape.content.magic.worldresponse;

import net.minecraft.server.level.ServerPlayer;

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
