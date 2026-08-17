package com.wsteam.wandscape.tourist.internal;

/**
 * 夜晚「无空闲旅店」闩锁（纯逻辑，可单测）：当晚所有旅店满员或过远传送失败后闩上，
 * 当晚不再尝试找旅店/重扫安全点——夜晚没有退宿，重扫是纯浪费的 CPU（每游客每 tick 全扫建筑）。
 * 次日白天由调用方 {@link #clear()} 解除，让下一晚重新尝试。
 * 实体 {@link TouristMoveGoal} 与 sim {@link TouristSimSystem} 共用。
 */
public final class HotelRouteBackoff {
    private boolean active;

    /** 是否处于「当晚无空闲旅店」状态（不应再尝试旅店路由）。 */
    public boolean isActive() {
        return active;
    }

    /** 闩上：当晚所有旅店满员 / 过远传送失败。 */
    public void enter() {
        active = true;
    }

    /** 解除：白天到 / 成功路由旅店后。 */
    public void clear() {
        active = false;
    }
}
