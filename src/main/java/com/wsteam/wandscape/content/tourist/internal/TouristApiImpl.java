package com.wsteam.wandscape.content.tourist.internal;

import com.wsteam.wandscape.api.TouristApi;
import com.wsteam.wandscape.content.tourist.data.BarRatio;
import com.wsteam.wandscape.content.tourist.event.TouristArrivedEvent;
import com.wsteam.wandscape.content.tourist.event.TouristDepartedEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
/**
 * Implementation of {@link TouristApi}.
 * Tracks tourist presence per colony in memory.
 * Full spawn logic will be implemented in Phase C (TouristSpawnSystem).
 */
public class TouristApiImpl implements TouristApi {

    // colonyId → set of tourist entity UUIDs
    private final Map<UUID, Set<UUID>> colonyTourists = new ConcurrentHashMap<>();
    // colonyId → count of tourists who stayed overnight (checked into hotel)
    private final Map<UUID, Integer> colonyOvernightCounts = new ConcurrentHashMap<>();
    /**
     * 本会话内已登记离场的游客 UUID——让 {@link #registerDeparture} 幂等。
     * <p>同一游客的离场可能被两条路径重复上报：显式离场（onTouristDepart / sim depart / 快进夜）
     * 与实体移除兜底（onTouristKilled → discard 的 DISCARDED 移除）。两者都调 registerDeparture，
     * 若不幂等，TouristDepartedEvent 会被重复 post，StatisticsCollector 等把离场数双计。
     * <p>游客 UUID 离线场后 shadow 即从注册表移除、不会再出现，故集合按「只增不删」即可，
     * 无需清理；仅按 JVM 生命周期驻留，UUID 不跨世界/会话复用，不会误伤新游客。
     */
    private final Set<UUID> departedTourists = ConcurrentHashMap.newKeySet();

    @Override
    public int getTouristCount(UUID colonyId) {
        if (colonyId == null) return 0;
        // sim 影子注册表是权威人口（SavedData 持久化，重启后仍恢复）——覆盖已加载实体、
        // 未加载 shadow 与磁盘加载的游客。内存 colonyTourists map 重启即清空，仅当 sim
        // 未激活（registry 为 null）时兜底，避免最早期启动窗口读到 0。
        TouristSimSystem sim = TouristSimSystem.getActive();
        if (sim != null && sim.getRegistry() != null) {
            return (int) sim.getRegistry().getShadows().values().stream()
                    .filter(s -> colonyId.equals(s.getColonyId()))
                    .count();
        }
        Set<UUID> tourists = colonyTourists.get(colonyId);
        return tourists != null ? tourists.size() : 0;
    }

    @Override
    public List<UUID> getTouristsInColony(UUID colonyId) {
        Set<UUID> tourists = colonyTourists.get(colonyId);
        return tourists != null ? List.copyOf(tourists) : List.of();
    }

    @Override
    public void spawnTourist(UUID colonyId, net.minecraft.core.BlockPos spawnPos) {
        // Phase C: TouristSpawnSystem will handle actual entity spawning
        // For now, this is a placeholder that the spawn system will call
    }

    @Override
    public void registerArrival(UUID touristId, UUID colonyId) {
        colonyTourists.computeIfAbsent(colonyId, k -> ConcurrentHashMap.newKeySet()).add(touristId);
        NeoForge.EVENT_BUS.post(new TouristArrivedEvent(touristId, colonyId));
    }

    @Override
    public void registerDeparture(UUID touristId, UUID colonyId, BarRatio fill) {
        // 幂等：同一游客只登记一次离场（显式离场与实体移除兜底可能双双调用本方法）。
        if (!departedTourists.add(touristId)) return;
        Set<UUID> tourists = colonyTourists.get(colonyId);
        if (tourists != null) {
            tourists.remove(touristId);
            if (tourists.isEmpty()) {
                colonyTourists.remove(colonyId);
            }
        }
        NeoForge.EVENT_BUS.post(new TouristDepartedEvent(touristId, colonyId, fill));
    }

    @Override
    public int getOvernightStayerCount(UUID colonyId) {
        return colonyOvernightCounts.getOrDefault(colonyId, 0);
    }

    /** Set the count of overnight stayers for a colony. Called by TouristSpawnSystem during morning phase. */
    public void setOvernightStayerCount(UUID colonyId, int count) {
        if (count <= 0) colonyOvernightCounts.remove(colonyId);
        else colonyOvernightCounts.put(colonyId, count);
    }
}
