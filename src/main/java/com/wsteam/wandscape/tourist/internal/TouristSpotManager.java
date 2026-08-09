package com.wsteam.wandscape.tourist.internal;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交互位（spot）占用管理：spot 数量 = 该建筑同时交互的游客人数上限。
 *
 * <p>游客占位做动作（browse/eat/bathe/...）期间占用一个 spot；spot 全满 → 排队（在建筑旁等）。
 * 内存态、无需持久化——玩家离开后影子游客不占真实位子，sim 瞬时交互即占即放。
 *
 * <p>实体路径与影子 sim 路径共用本单例，保证 {@code freeSpotCount} 反映真实占用（实体游客排队时
 * 能感知 sim 游客的占用，反之亦然）。
 */
public final class TouristSpotManager {

    /** buildingId → 已占用的 spot 下标集合。 */
    private final Map<UUID, Set<Integer>> occupancy = new ConcurrentHashMap<>();

    private static final TouristSpotManager ACTIVE = new TouristSpotManager();

    private TouristSpotManager() {
    }

    public static TouristSpotManager getActive() {
        return ACTIVE;
    }

    /**
     * 认领一个空 spot 并占用。
     *
     * @return 认领成功的 spot 下标；全满（无可认领）返回 -1。
     */
    public int claim(UUID buildingId, int totalSpots) {
        if (totalSpots <= 0) return -1;
        Set<Integer> taken = occupancy.computeIfAbsent(buildingId, k -> ConcurrentHashMap.newKeySet());
        synchronized (taken) {
            for (int i = 0; i < totalSpots; i++) {
                if (!taken.contains(i)) {
                    taken.add(i);
                    return i;
                }
            }
        }
        return -1;
    }

    /** 释放一个已占用的 spot。 */
    public void release(UUID buildingId, int spotIndex) {
        if (spotIndex < 0) return;
        Set<Integer> taken = occupancy.get(buildingId);
        if (taken != null) {
            taken.remove(spotIndex);
            if (taken.isEmpty()) {
                occupancy.remove(buildingId, taken);
            }
        }
    }

    public boolean isSpotFree(UUID buildingId, int spotIndex) {
        Set<Integer> taken = occupancy.get(buildingId);
        return taken == null || !taken.contains(spotIndex);
    }

    /** 当前空闲 spot 数（0 = 全满，新游客要排队）。 */
    public int freeSpotCount(UUID buildingId, int totalSpots) {
        if (totalSpots <= 0) return 0;
        Set<Integer> taken = occupancy.get(buildingId);
        if (taken == null) return totalSpots;
        return Math.max(0, totalSpots - taken.size());
    }

    /** spot 是否全满（全满 → 排队）。 */
    public boolean isFull(UUID buildingId, int totalSpots) {
        return freeSpotCount(buildingId, totalSpots) <= 0;
    }
}
