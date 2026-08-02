package com.wsteam.wandscape.shared.entity;

/**
 * 标记接口：原版敌对生物会像对待村民一样把实现者列为攻击目标。
 *
 * <p>仅表示「获得村民级索敌」这一行为契约，不引入村民的任何其它行为
 * （交易 / 繁殖 / 职业等）。由 {@code HostileTargetingHandler} 消费。
 */
public interface VillagerLike {
}
