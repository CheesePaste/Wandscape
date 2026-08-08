package com.wsteam.wandscape.tourist.internal;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.core.event.NarrativeEventTriggered;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.shared.data.NarrativeEvent;
import com.wsteam.wandscape.shared.data.ServiceConfig;
import com.wsteam.wandscape.tourist.entity.TouristEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Manages hotel/inn stays for tourists.
 *
 * <p>Hotels are service buildings with {@link ServiceConfig#maxOccupancy()} &gt; 0.
 * Tourists check in at night (when satisfaction &ge; 50, energy &le; 0) and
 * stay until morning checkout at dayTime=1000, when energy is restored to 100.
 *
 * <p>During the day, hotels behave as regular service buildings —
 * satisfaction comes from the normal service interaction, not from sleeping.
 *
 * <p>Checked-in tourists are stored in a per-building occupancy set.
 * On morning (dayTime 1000-1200), all guests are automatically checked out
 * with full energy restoration.
 */
public final class HotelStayHandler {
    private static final String TAG = "HotelStayHandler";

    /** buildingId → set of checked-in tourist UUIDs */
    private final Map<UUID, Set<UUID>> occupancy = new ConcurrentHashMap<>();
    /** touristId → buildingId for reverse lookup */
    private final Map<UUID, UUID> touristToHotel = new ConcurrentHashMap<>();
    /** touristId → bed the tourist is sleeping in (visual-only occupancy tracking) */
    private final Map<UUID, BlockPos> touristToBed = new ConcurrentHashMap<>();

    private int tickCounter;

    @Nullable
    private static HotelStayHandler active;

    private HotelStayHandler() {}

    @Nullable
    public static HotelStayHandler getActive() { return active; }

    public static HotelStayHandler register() {
        var instance = new HotelStayHandler();
        active = instance;
        NeoForge.EVENT_BUS.register(instance);
        return instance;
    }

    // ── Check-in / Check-out ──

    /**
     * Attempt to check a tourist into a hotel.
     *
     * @return true if check-in succeeded
     */
    public boolean checkIn(TouristEntity tourist, UUID buildingId, UUID colonyId) {
        BuildingConfig config = getBuildingConfig(buildingId);
        if (config == null || config.service() == null) return false;

        ServiceConfig svc = config.service();
        int maxOccupancy = svc.maxOccupancy();
        if (maxOccupancy <= 0) return false;

        Set<UUID> guests = occupancy.computeIfAbsent(buildingId, k -> ConcurrentHashMap.newKeySet());
        if (guests.size() >= maxOccupancy) {
            return false;
        }

        guests.add(tourist.getUUID());
        touristToHotel.put(tourist.getUUID(), buildingId);
        tourist.setCheckedInBuildingId(buildingId);
        tourist.setHotelCheckinTime(tourist.tickCount);

        Log.info(TAG, "[Tourist] {} checked into {} (occupancy {}/{})",
                tourist.getTouristName(), shortId(buildingId), guests.size(), maxOccupancy);
        return true;
    }

    /**
     * Check a tourist out of their hotel. Restores energy to 100.
     */
    public void checkOut(TouristEntity tourist, ServerLevel level) {
        UUID buildingId = touristToHotel.remove(tourist.getUUID());
        if (buildingId == null) return;

        Set<UUID> guests = occupancy.get(buildingId);
        if (guests != null) {
            guests.remove(tourist.getUUID());
            if (guests.isEmpty()) occupancy.remove(buildingId);
        }
        touristToBed.remove(tourist.getUUID());

        // Wake up and return to the spot where the tourist stood at check-in,
        // before it teleported into a bed.
        if (tourist.isSleeping()) {
            tourist.stopSleeping();
        }
        BlockPos wakeUp = tourist.getWakeUpPos();
        if (wakeUp != null) {
            tourist.setPos(wakeUp.getX() + 0.5, wakeUp.getY(), wakeUp.getZ() + 0.5);
            tourist.setWakeUpPos(null);
        }
        tourist.applyState(TouristState.IDLE);

        tourist.setCheckedInBuildingId(null);
        tourist.setHotelCheckinTime(0);
        tourist.setEnergy(100);

        // Emit HOTEL_WAKEUP narrative
        String bldType = getBuildingTypeId(buildingId);
        String bldName = getBuildingDisplayName(buildingId, bldType);
        NarrativeEvent wakeupEvent = NarrativeGenerator.generateHotelWakeup(
                tourist.getTouristName(), bldType != null ? bldType : "inn",
                bldName, level.getGameTime());
        emitNarrativeEvent(wakeupEvent);

        Log.info(TAG, "[Tourist] {} checked out of {} (energy → 100)",
                tourist.getTouristName(), shortId(buildingId));
    }

    /**
     * After a successful check-in, teleport the tourist onto a free bed in the
     * hotel to sleep (visual only — no stat effects). If no bed is free, the
     * tourist simply stays where it is until morning checkout.
     */
    public void settleIntoBed(TouristEntity tourist, ServerLevel level, UUID buildingId) {
        tourist.setWakeUpPos(tourist.blockPosition());
        BlockPos bed = findFreeBed(level, buildingId, tourist.blockPosition());
        if (bed == null) {
            Log.info(TAG, "[Tourist] {} checked into {} but no free bed — staying put",
                    tourist.getTouristName(), shortId(buildingId));
            return;
        }
        // Visual-only sleeping: lie on the bed without mutating the bed's occupied
        // property (no block updates, and no stuck-occupied leak if the chunk
        // unloads mid-sleep). Bed assignment is tracked in memory instead.
        tourist.setPos(bed.getX() + 0.5, bed.getY() + 0.6875, bed.getZ() + 0.5);
        tourist.setSleepingPos(bed);
        tourist.applyState(TouristState.SLEEPING);
        touristToBed.put(tourist.getUUID(), bed);
        Log.info(TAG, "[Tourist] {} sleeping in bed at {} (hotel {})",
                tourist.getTouristName(), bed.toShortString(), shortId(buildingId));
    }

    /** Nearest unoccupied bed (head half) inside the hotel's bounding box, or null. */
    @Nullable
    private BlockPos findFreeBed(ServerLevel level, UUID buildingId, BlockPos near) {
        BuildingState state = getBuildingState(buildingId);
        if (state == null) return null;
        BoundingBox box = state.getBounds();
        if (box == null) return null;

        Set<BlockPos> assigned = new HashSet<>(touristToBed.values());
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState bs = level.getBlockState(p);
                    if (!(bs.getBlock() instanceof BedBlock)) continue;
                    if (bs.getValue(BedBlock.OCCUPIED)) continue;
                    BlockPos head = bedHeadPos(bs, p);
                    if (assigned.contains(head)) continue;
                    double sq = head.distSqr(near);
                    if (sq < bestSq) {
                        bestSq = sq;
                        best = head;
                    }
                }
            }
        }
        return best;
    }

    /** The head half of a bed (where the sleeper lies with its head on the pillow). */
    private static BlockPos bedHeadPos(BlockState bs, BlockPos pos) {
        if (bs.getValue(BedBlock.PART) == BedPart.HEAD) return pos;
        return pos.relative(bs.getValue(BedBlock.FACING));
    }

    // ── Query ──

    /** Returns the number of currently checked-in tourists in a hotel.
     *  Derived from the shadow registry so unloaded (sim) guests also count. */
    public int getOccupancy(UUID buildingId) {
        TouristSimSystem sim = TouristSimSystem.getActive();
        if (sim != null && sim.getRegistry() != null) {
            int n = 0;
            for (TouristShadow s : sim.getRegistry().getShadows().values()) {
                if (buildingId.equals(s.getCheckedInBuildingId())) n++;
            }
            return n;
        }
        Set<UUID> guests = occupancy.get(buildingId);
        return guests != null ? guests.size() : 0;
    }

    /** Returns true if the hotel has vacancy. */
    public boolean hasVacancy(UUID buildingId) {
        BuildingConfig config = getBuildingConfig(buildingId);
        if (config == null || config.service() == null) return false;
        int max = config.service().maxOccupancy();
        if (max <= 0) return false;
        return getOccupancy(buildingId) < max;
    }

    /** Returns the building ID the tourist is checked into, or null. */
    @Nullable
    public UUID getCheckedInBuilding(UUID touristId) {
        return touristToHotel.get(touristId);
    }

    /** Returns true if the tourist is currently checked into a hotel. */
    public boolean isCheckedIn(UUID touristId) {
        return touristToHotel.containsKey(touristId);
    }

    /** Returns the names of all guests currently checked into a hotel. */
    public List<String> getGuestNames(UUID buildingId, net.minecraft.world.level.Level level) {
        Set<UUID> guests = occupancy.get(buildingId);
        if (guests == null || guests.isEmpty()) return List.of();
        List<String> names = new java.util.ArrayList<>();
        if (level instanceof net.minecraft.server.level.ServerLevel sl) {
            for (UUID touristId : guests) {
                for (var entity : sl.getAllEntities()) {
                    if (entity instanceof TouristEntity t && t.getUUID().equals(touristId)) {
                        names.add(t.getTouristName());
                        break;
                    }
                }
            }
        }
        return names;
    }

    // ── Heartbeat ──

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;
        if (tickCounter % 20 != 0) return; // every second

        ServerLevel level = getServerLevel();
        if (level == null) return;

        long dayTime = level.getDayTime() % 24000;
        // Morning checkout window: 1000-1200 (清晨退房，精力回满)
        boolean isMorning = dayTime >= 1000 && dayTime < 1200;

        for (var entry : touristToHotel.entrySet()) {
            UUID touristId = entry.getKey();
            TouristEntity tourist = findTourist(level, touristId);
            if (tourist == null || !tourist.isAlive()) {
                forceCheckOut(touristId);
                continue;
            }

            // Morning checkout: energy → 100
            if (isMorning) {
                checkOut(tourist, level);
                continue;
            }

            // No gradual energy recovery — energy restored to 100 at checkout only
        }
    }

    // ── Internal ──

    private void forceCheckOut(UUID touristId) {
        UUID buildingId = touristToHotel.remove(touristId);
        if (buildingId != null) {
            Set<UUID> guests = occupancy.get(buildingId);
            if (guests != null) {
                guests.remove(touristId);
                if (guests.isEmpty()) occupancy.remove(buildingId);
            }
        }
        touristToBed.remove(touristId);
    }

    @Nullable
    private BuildingState getBuildingState(UUID buildingId) {
        ServerLevel level = getServerLevel();
        if (level == null) return null;
        BuildingSavedData sd = BuildingSavedData.get(level);
        return sd != null ? sd.getBuilding(buildingId) : null;
    }

    @Nullable
    private BuildingConfig getBuildingConfig(UUID buildingId) {
        BuildingState state = getBuildingState(buildingId);
        return state != null ? BuildingConfigLoader.getInstance().get(state.getBuildingTypeId()) : null;
    }

    @Nullable
    private static TouristEntity findTourist(ServerLevel level, UUID touristId) {
        for (var entity : level.getAllEntities()) {
            if (entity instanceof TouristEntity t && t.getUUID().equals(touristId)) {
                return t;
            }
        }
        return null;
    }

    @Nullable
    private static ServerLevel getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    // ── Narrative helpers ──

    @Nullable
    private String getBuildingTypeId(UUID buildingId) {
        BuildingConfig config = getBuildingConfig(buildingId);
        return config != null ? config.id() : null;
    }

    private String getBuildingDisplayName(UUID buildingId, @Nullable String typeId) {
        var config = BuildingConfigLoader.getInstance().get(typeId);
        if (config != null && config.displayName() != null && !config.displayName().isEmpty()) {
            return config.displayName();
        }
        return typeId != null ? typeId : "旅馆";
    }

    private static void emitNarrativeEvent(NarrativeEvent ne) {
        var world = WandscapeEngine.getWorld();
        if (world != null && world.eventBus != null) {
            world.eventBus.emit(new NarrativeEventTriggered(ne));
        }
    }
}
