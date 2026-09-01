package com.wsteam.wandscape.content.tourist.entity;

/**
 * 标记接口：原版敌对生物会像对待玩家一样把实现者列为攻击目标
 * （骷髅 / 史莱姆 / 苦力怕 / 僵尸等所有含玩家级索敌的生物）。
 *
 * <p>仅表示「获得玩家级索敌」这一行为契约，不引入玩家的任何其它行为
 * （血量 / 饥饿 / 伤害规则等）。由 {@code HostileTargetingHandler} 消费。
 */
public interface PlayerLike {
}
