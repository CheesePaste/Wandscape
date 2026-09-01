package com.wsteam.wandscape.content.building;
import com.wsteam.wandscape.content.task.boundary.WandscapeBlockOps;

/**
 * 建造放置瞬间的全局开关。置位期间 {@code MixinLevelTicks} 会丢弃由放置同步
 * 触发的 scheduled tick（水/岩浆流动、侦测器脉冲、比较器/中继器重算等），让方块
 * 落地即为其最终状态——施工中容器未封口时水不会提前流走，红石也不会乱触发。
 *
 * <p>只在 {@link WandscapeBlockOps#setBlock} 的 setBlock 调用瞬间开启，期间
 * 同步执行的 onPlace / updateShape 排队的 tick 全部被丢弃；关闭后无残留 pending
 * tick，因此不会在建筑完工后补发流动。守卫之外的一切路径完全不受影响。
 */
public final class BuildPlacementGuard {

    private BuildPlacementGuard() {}

    private static volatile boolean active = false;

    public static boolean isActive() {
        return active;
    }

    public static void enable() {
        active = true;
    }

    public static void disable() {
        active = false;
    }
}
