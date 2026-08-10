package com.wsteam.wandscape.tourist.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 交互位（spot）占用管理：spot 数量 = 该建筑同时交互的游客人数上限。
 *
 * <p>游客占位做动作（browse/eat/bathe/...）期间占用一个 spot；spot 全满 → 排队，**每个 spot 各排一队**
 * （游客均匀分散到队最短的 spot 后，沿该 spot 朝向站成一列）。内存态、无需持久化——玩家离开后影子游客
 * 不占真实位子，sim 瞬时交互即占即放。
 *
 * <p>实体路径与影子 sim 路径共用本单例，保证 {@code freeSpotCount} 反映真实占用（实体游客排队时
 * 能感知 sim 游客的占用，反之亦然）。
 */
public final class TouristSpotManager {

    /** buildingId → 已占用的 spot 下标集合。 */
    private final Map<UUID, Set<Integer>> occupancy = new ConcurrentHashMap<>();

    /** buildingId → spot 下标 → 该队游客 UUID 列表（队首 = 下标 0，FIFO）。 */
    private final Map<UUID, Map<Integer, List<UUID>>> queue = new ConcurrentHashMap<>();

    private static final TouristSpotManager ACTIVE = new TouristSpotManager();

    /** 包私有构造：运行用 {@link #getActive()} 单例，单测可另建隔离实例。 */
    TouristSpotManager() {
    }

    public static TouristSpotManager getActive() {
        return ACTIVE;
    }

    /** 认领一个空 spot 并占用。 */
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

    /** 认领指定 spot（供排在该 spot 队首的游客用）；已被占返回 -1。 */
    public int claimAt(UUID buildingId, int spotIndex, int totalSpots) {
        if (spotIndex < 0 || spotIndex >= totalSpots) return -1;
        Set<Integer> taken = occupancy.computeIfAbsent(buildingId, k -> ConcurrentHashMap.newKeySet());
        synchronized (taken) {
            if (!taken.contains(spotIndex)) {
                taken.add(spotIndex);
                return spotIndex;
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

    // ── 排队注册（每 spot 一队，FIFO；队首前移时后续游客重新查 queuePosition 即可前移站位）──

    /** 加入指定 spot 的队并返回队序（0 = 队首）；已在队中则返回当前队序（幂等）。 */
    public int joinQueue(UUID buildingId, int spotIndex, UUID touristId) {
        Map<Integer, List<UUID>> spots = queue.computeIfAbsent(buildingId, k -> new ConcurrentHashMap<>());
        List<UUID> q = spots.computeIfAbsent(spotIndex, k -> new CopyOnWriteArrayList<>());
        if (!q.contains(touristId)) {
            q.add(touristId);
        }
        return q.indexOf(touristId);
    }

    /** 从所有队中移除该游客（幂等；空队清理条目）。 */
    public void leaveAllQueues(UUID buildingId, UUID touristId) {
        Map<Integer, List<UUID>> spots = queue.get(buildingId);
        if (spots == null) return;
        for (List<UUID> q : spots.values()) {
            q.remove(touristId);
        }
        spots.values().removeIf(List::isEmpty);
        if (spots.isEmpty()) {
            queue.remove(buildingId, spots);
        }
    }

    /** 指定 spot 队中，队首到该游客的距离（0 = 队首）；不在队中返回 -1。 */
    public int queuePosition(UUID buildingId, int spotIndex, UUID touristId) {
        Map<Integer, List<UUID>> spots = queue.get(buildingId);
        if (spots == null) return -1;
        List<UUID> q = spots.get(spotIndex);
        return q == null ? -1 : q.indexOf(touristId);
    }

    /** 指定 spot 队当前人数。 */
    public int queueSize(UUID buildingId, int spotIndex) {
        Map<Integer, List<UUID>> spots = queue.get(buildingId);
        if (spots == null) return 0;
        List<UUID> q = spots.get(spotIndex);
        return q == null ? 0 : q.size();
    }

    /** 该建筑所有 spot 的排队总人数（正在占位交互的不算）。0 = 无人排队。 */
    public int totalQueueLength(UUID buildingId) {
        Map<Integer, List<UUID>> spots = queue.get(buildingId);
        if (spots == null) return 0;
        int n = 0;
        for (List<UUID> q : spots.values()) {
            n += q.size();
        }
        return n;
    }

    /** 队最短的 spot（均匀分散排队人群）；并列取最小编号。totalSpots>0 时恒返回有效下标。 */
    public int shortestQueueSpot(UUID buildingId, int totalSpots) {
        int best = -1;
        int bestLen = Integer.MAX_VALUE;
        for (int i = 0; i < totalSpots; i++) {
            int len = queueSize(buildingId, i);
            if (len < bestLen) {
                bestLen = len;
                best = i;
            }
        }
        return best;
    }
}
