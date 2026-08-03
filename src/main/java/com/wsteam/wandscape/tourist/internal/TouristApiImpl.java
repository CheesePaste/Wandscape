package com.wsteam.wandscape.tourist.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.shared.api.TouristApi;
import com.wsteam.wandscape.shared.event.TouristArrivedEvent;
import com.wsteam.wandscape.shared.event.TouristDepartedEvent;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
/**
 * Implementation of {@link TouristApi}.
 * Tracks tourist presence and satisfaction per colony in memory.
 * Full spawn logic will be implemented in Phase C (TouristSpawnSystem).
 */
public class TouristApiImpl implements TouristApi {

    // colonyId → set of tourist entity UUIDs
    private final Map<UUID, Map<UUID, Integer>> colonyTourists = new ConcurrentHashMap<>();
    // colonyId → count of tourists who stayed overnight (checked into hotel)
    private final Map<UUID, Integer> colonyOvernightCounts = new ConcurrentHashMap<>();

    @Override
    public int getTouristCount(UUID colonyId) {
        Map<UUID, Integer> tourists = colonyTourists.get(colonyId);
        return tourists != null ? tourists.size() : 0;
    }

    @Override
    public List<UUID> getTouristsInColony(UUID colonyId) {
        Map<UUID, Integer> tourists = colonyTourists.get(colonyId);
        return tourists != null ? List.copyOf(tourists.keySet()) : List.of();
    }

    @Override
    public void spawnTourist(UUID colonyId, net.minecraft.core.BlockPos spawnPos) {
        // Phase C: TouristSpawnSystem will handle actual entity spawning
        // For now, this is a placeholder that the spawn system will call
    }

    @Override
    public int getAverageSatisfaction(UUID colonyId) {
        Map<UUID, Integer> tourists = colonyTourists.get(colonyId);
        if (tourists == null || tourists.isEmpty()) return 0;
        int total = 0;
        for (int sat : tourists.values()) {
            total += sat;
        }
        return total / tourists.size();
    }

    @Override
    public void registerArrival(UUID touristId, UUID colonyId) {
        colonyTourists.computeIfAbsent(colonyId, k -> new ConcurrentHashMap<>())
                .put(touristId, 0);
        NeoForge.EVENT_BUS.post(new TouristArrivedEvent(touristId, colonyId));
        playTouristSound(touristId, WandscapeSounds.TOURIST_ARRIVE);
    }

    @Override
    public void registerDeparture(UUID touristId, UUID colonyId, int satisfaction) {
        Map<UUID, Integer> tourists = colonyTourists.get(colonyId);
        if (tourists != null) {
            tourists.remove(touristId);
            if (tourists.isEmpty()) {
                colonyTourists.remove(colonyId);
            }
        }
        NeoForge.EVENT_BUS.post(new TouristDepartedEvent(touristId, colonyId, satisfaction));
        playTouristSound(touristId, WandscapeSounds.TOURIST_DEPART);
    }

    /** 游客到达/离开音：按 UUID 在服务端主世界找实体播音，找不到则跳过。 */
    private static void playTouristSound(UUID touristId, DeferredHolder<SoundEvent, SoundEvent> sound) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        Entity tourist = overworld.getEntity(touristId);
        if (tourist != null) {
            SoundService.playEntity(tourist, sound, 0.6f, 1.0f);
        }
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

    /** Update a tourist's satisfaction value (called by interaction handlers). */
    public void updateSatisfaction(UUID touristId, UUID colonyId, int satisfaction) {
        Map<UUID, Integer> tourists = colonyTourists.get(colonyId);
        if (tourists != null) {
            tourists.put(touristId, satisfaction);
        }
    }
}
