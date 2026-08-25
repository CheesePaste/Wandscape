package com.wsteam.wandscape.tourist.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wsteam.wandscape.tourist.internal.TouristShadow;
import com.wsteam.wandscape.tourist.internal.TouristSimSystem;

/**
 * 孤儿身体清除判定纯逻辑单测。
 *
 * <p>契约：活体游客身体若在影子注册表中找不到对应 shadow（游客已离场、shadow 已删），
 * 即为孤儿、应被 {@code runTick} 的孤儿扫描 discard。**该判定与注册表是否为空无关**——
 * 玩家睡觉（快进夜）会让无旅店游客一次性全部离场、清空注册表；若孤儿判定依赖注册表非空
 * （旧 bug：runTick 的 {@code shadows.isEmpty()} 空保护把孤儿扫描短路），这些已离场游客的
 * 观察中身体会永远滞留世界。
 */
class TouristOrphanCleanupTest {

    private static final UUID ID = UUID.randomUUID();

    /** 注册表被清空（快进夜全部离场）时，仍在场的活体身体仍判定为孤儿——本 bug 的回归守卫。 */
    @Test
    void bodyIsOrphanWhenRegistryEmptied() {
        assertTrue(TouristSimSystem.isOrphan(ID, Map.of()));
    }

    /** 身体在注册表里找不到自己的 shadow（只其它游客的 shadow 在场）→ 孤儿。 */
    @Test
    void bodyIsOrphanWhenItsShadowAbsentAmongOthers() {
        Map<UUID, TouristShadow> shadows = new HashMap<>();
        shadows.put(UUID.randomUUID(), new TouristShadow());
        assertTrue(TouristSimSystem.isOrphan(ID, shadows));
    }

    /** 身体在注册表里有自己的 shadow → 非孤儿，不应被清除。 */
    @Test
    void bodyWithShadowIsNotOrphan() {
        Map<UUID, TouristShadow> shadows = new HashMap<>();
        shadows.put(ID, new TouristShadow());
        assertFalse(TouristSimSystem.isOrphan(ID, shadows));
    }
}
