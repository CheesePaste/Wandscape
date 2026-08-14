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
import com.wsteam.wandscape.shared.registry.WandscapeConstants;
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
 * Manages hotel/inn stays for tourists（住店客机制）.
 *
 * <p>Hotels are service buildings with {@link ServiceConfig#maxOccupancy()} &gt; 0.
 * Tourists check in at night (when not fully satisfied — 满条游客夜晚等离场) and become
 * **住店客**：登记常驻（清晨晨起保留登记、白天外出、夜晚回店睡），离场/被杀才退房。
 *
 * <p>During the day, hotels behave as regular service buildings —
 * three bars come from the normal service interaction, not from sleeping.
 *
 * <p>Checked-in tourists are stored in a per-building occupancy set.
 * On morning (dayTime 1000-1200), guests are woken up ({@link #wakeUp}) with full
 * energy restoration but KEEP their registration — the guest list is not cleared.
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
        // 已是该旅店住店客（夜晚回店重上床 / 磁盘加载恢复登记）→ 幂等，跳过容量检查。
        // 磁盘加载时 occupancy 从空重建，若旅店被其它住店客占满，正常 checkIn 会失败而误清住店登记。
        if (isResidentAt(tourist.getUUID(), buildingId)) {
            guests.add(tourist.getUUID());
            touristToHotel.put(tourist.getUUID(), buildingId);
            tourist.setCheckedInBuildingId(buildingId);
            return true;
        }

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
     * 住店客晨起：回入住前站位、清睡觉姿态、精力回 100、住店晚数 +1；**保持酒店登记**
     * （游客仍是该旅店住店客，白天外出逛街、夜晚回来睡，名单上不删除）。
     *
     * <p>幂等：heartbeat 在晨间窗口（1000-1200）每 20 tick 跑一次，已醒且已回位的游客跳过。
     */
    public void wakeUp(TouristEntity tourist, ServerLevel level) {
        if (!tourist.isSleeping() && tourist.getWakeUpPos() == null) return;

        if (tourist.isSleeping()) {
            tourist.stopSleeping();
        }
        BlockPos wakeUp = tourist.getWakeUpPos();
        if (wakeUp != null) {
            double floorY = TouristSimulation.getFloorSurfaceY(level, wakeUp);
            tourist.setPos(wakeUp.getX() + 0.5, floorY, wakeUp.getZ() + 0.5);
            tourist.resetFallDistance();
            tourist.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            tourist.setWakeUpPos(null);
        }
        tourist.applyState(TouristState.IDLE);
        tourist.setHotelCheckinTime(0);
        tourist.setNightsStayed(tourist.getNightsStayed() + 1);
        tourist.setEnergy(WandscapeConstants.TOURIST_MAX_ENERGY);

        // Emit HOTEL_WAKEUP narrative
        UUID buildingId = touristToHotel.get(tourist.getUUID());
        if (buildingId != null) {
            String bldType = getBuildingTypeId(buildingId);
            String bldName = getBuildingDisplayName(buildingId, bldType);
            NarrativeEvent wakeupEvent = NarrativeGenerator.generateHotelWakeup(
                    tourist.getTouristName(), bldType != null ? bldType : "inn",
                    bldName, level.getGameTime());
            emitNarrativeEvent(wakeupEvent);
        }

        Log.info(TAG, "[Tourist] {} woke up at {} (still resident, energy → 100)",
                tourist.getTouristName(), tourist.blockPosition().toShortString());
    }

    /**
     * 最终退房（游客离场/被杀）：从旅店名单删除、清睡觉姿态与登记。晚数与精力恢复由晨起
     * {@link #wakeUp} 负责，离场时不再重复计入。
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
            double floorY = TouristSimulation.getFloorSurfaceY(level, wakeUp);
            tourist.setPos(wakeUp.getX() + 0.5, floorY, wakeUp.getZ() + 0.5);
            tourist.resetFallDistance();
            tourist.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            tourist.setWakeUpPos(null);
        }
        tourist.applyState(TouristState.IDLE);

        tourist.setCheckedInBuildingId(null);
        tourist.setHotelCheckinTime(0);

        Log.info(TAG, "[Tourist] {} checked out of {} (departed)",
                tourist.getTouristName(), shortId(buildingId));
    }

    /**
     * After a successful check-in, force the tourist to sleep — 入住即强制躺床：
     * 有空床躺空床；床不够（全被占用）就躺最近一张床；旅店一张床都没有 → 卡原地不动。
     * 床上睡觉纯视觉（不改床方块占用状态，无占用泄漏），床位分配只记在内存。
     */
    public void settleIntoBed(TouristEntity tourist, ServerLevel level, UUID buildingId) {
        tourist.setWakeUpPos(tourist.blockPosition());
        BlockPos bed = findBed(level, buildingId, tourist.blockPosition(), true);
        if (bed == null) bed = findBed(level, buildingId, tourist.blockPosition(), false); // 床不够 → 躺第一张（最近）
        if (bed != null) {
            tourist.setPos(bed.getX() + 0.5, bed.getY() + 0.6875, bed.getZ() + 0.5);
            tourist.resetFallDistance();
            tourist.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            tourist.setSleepingPos(bed);
            tourist.applyState(TouristState.SLEEPING);
            touristToBed.put(tourist.getUUID(), bed);
            Log.info(TAG, "[Tourist] {} sleeping in bed at {} (hotel {})",
                    tourist.getTouristName(), bed.toShortString(), shortId(buildingId));
            return;
        }
        // 没床 → 卡原地（不动，等清晨晨起）
        Log.info(TAG, "[Tourist] {} checked into {} but the hotel has no beds — staying put",
                tourist.getTouristName(), shortId(buildingId));
    }

    /**
     * 旅店里最近的一张床（head 半）。{@code requireUnassigned}=true 时跳过已分配给其它
     * 住店客的床；false 时不看占用分配（床不够的兜底，纯视觉可共用），仅跳过原版 OCCUPIED 的床。
     */
    @Nullable
    private BlockPos findBed(ServerLevel level, UUID buildingId, BlockPos near, boolean requireUnassigned) {
        BuildingState state = getBuildingState(buildingId);
        if (state == null) return null;
        BoundingBox box = state.getBounds();
        if (box == null) return null;

        Set<BlockPos> assigned = requireUnassigned ? new HashSet<>(touristToBed.values()) : Set.of();
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
        // Morning wake-up window: 1000-1200 (清晨起床，精力回满；住店客保留登记)
        boolean isMorning = dayTime >= 1000 && dayTime < 1200;

        for (var entry : touristToHotel.entrySet()) {
            UUID touristId = entry.getKey();
            TouristEntity tourist = findTourist(level, touristId);
            if (tourist == null || !tourist.isAlive()) {
                // 实体未加载：若影子仍是住店客（sim 驱动），跳过——sim 自己处理晨起与回店；
                // 实体真没了（被杀/离场已由 onTouristKilled/onTouristDepart 清理）才强制退房。
                if (shadowStillResident(touristId)) continue;
                forceCheckOut(touristId);
                continue;
            }

            // Morning wake-up: keep the tourist registered as a resident
            if (isMorning) {
                wakeUp(tourist, level);
                continue;
            }

            // No gradual energy recovery — energy restored to 100 at morning wake-up only
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

    /**
     * 游客是否已是该旅店的住店客（内存登记，或磁盘加载/夜晚回店时影子仍登记在案）。
     * 供 {@link #checkIn} 幂等跳过容量检查，避免住店客被自己占的床位挤掉。
     */
    private boolean isResidentAt(UUID touristId, UUID buildingId) {
        if (buildingId.equals(touristToHotel.get(touristId))) return true;
        TouristSimSystem sim = TouristSimSystem.getActive();
        if (sim != null && sim.getRegistry() != null) {
            TouristShadow s = sim.getRegistry().getShadows().get(touristId);
            if (s != null && buildingId.equals(s.getCheckedInBuildingId())) return true;
        }
        return false;
    }

    /** 影子仍是住店客（实体未加载但 sim 驱动的场景）→ 心跳不应强制退房。 */
    private boolean shadowStillResident(UUID touristId) {
        TouristSimSystem sim = TouristSimSystem.getActive();
        if (sim == null || sim.getRegistry() == null) return false;
        TouristShadow s = sim.getRegistry().getShadows().get(touristId);
        return s != null && s.getCheckedInBuildingId() != null;
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
