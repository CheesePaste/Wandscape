package com.wsteam.wandscape.compass.client;

import net.minecraft.core.GlobalPos;

import javax.annotation.Nullable;

/**
 * 客户端缓存的「玩家自己殖民地的市政厅」坐标（由 {@code CompassTargetPacket} 服务端→客户端同步）。
 *
 * <p>魔法指南针的 {@code angle} item property 据此计算指针朝向；高级/终极的 tooltip 据此显示坐标。
 * 仅客户端写/读（服务端不渲染 tooltip，静态值无意义且无害）。与本模块 {@code ring/client/OathRingClientData}
 * 同一种"common 类持客户端状态"范式。
 */
public final class CompassTargetClientCache {

    @Nullable
    private static GlobalPos target;

    private CompassTargetClientCache() {}

    public static void set(@Nullable GlobalPos target) {
        CompassTargetClientCache.target = target;
    }

    @Nullable
    public static GlobalPos get() {
        return target;
    }

    /** 登出时清空，避免跨世界/跨存档残留。 */
    public static void clear() {
        target = null;
    }
}
