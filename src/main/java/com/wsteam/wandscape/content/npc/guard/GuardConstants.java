package com.wsteam.wandscape.guard;

/**
 * 守卫模块固定常量。可调数值（半径等）在 {@code Config.java}（TOML）。
 */
public final class GuardConstants {
    private GuardConstants() {}

    /** 守卫任务优先级：< 50 避开 PENDING_APPROVAL 审批门（autoApproveTasks 默认 false），且高于普通建造任务(~40)。 */
    public static final int GUARD_PRIORITY = 49;
    /** 守卫任务源轮询间隔（tick）。 */
    public static final int POLL_INTERVAL_TICKS = 20;
    /** 执行器在「无攻击目标但未脱离 / 视线被挡」时的等待重试间隔（tick）。 */
    public static final int STANDBY_TICKS = 20;
}
