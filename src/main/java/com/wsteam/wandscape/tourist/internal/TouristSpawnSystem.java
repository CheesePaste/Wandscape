package com.wsteam.wandscape.tourist.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.core.road.RoadEdge;
import com.wsteam.wandscape.core.road.RoadNetwork;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.api.RoadApi;
import com.wsteam.wandscape.shared.api.TouristApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.tourist.entity.TouristEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Spawns short-term tourist entities that visit shops and service buildings.
 *
 * <p>Tourists spawn at colony-boundary road positions during morning hours.
 * Count is scaled by colony three-value evaluation. Each tourist picks a
 * random shop or service building as their first destination.
 */
public final class TouristSpawnSystem {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Tick interval between spawn checks. */
    private static final int SPAWN_CHECK_INTERVAL = 1200;
    /** Morning spawn window start (game time). */
    private static final long MORNING_START = 0;
    /** Morning spawn window end (game time). */
    private static final long MORNING_END = 2000;
    /** Maximum tourists active at once. */
    private static final int MAX_TOURISTS = 10;

    private int tickCounter;
    private boolean spawnedThisMorning;
    private final Random random = new Random();

    private TouristSpawnSystem() {}

    public static TouristSpawnSystem register() {
        var instance = new TouristSpawnSystem();
        NeoForge.EVENT_BUS.register(instance);
        return instance;
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (level == null) return;

        tickCounter++;
        if (tickCounter % SPAWN_CHECK_INTERVAL != 0) return;

        // Clean up tourists that should despawn (energy depleted, nightfall, idle timeout)
        cleanupTourists(level);

        long dayTime = level.getDayTime() % 24000;
        boolean isMorning = dayTime >= MORNING_START && dayTime < MORNING_END;

        if (isMorning && !spawnedThisMorning) {
            spawnedThisMorning = true;
            spawnTourists(level);
        } else if (!isMorning) {
            spawnedThisMorning = false;
        }
    }

    private void spawnTourists(ServerLevel level) {
        BuildingApi buildingApi = getBuildingApi();
        if (buildingApi == null) return;

        List<BuildingData> allBuildings = buildingApi.getColonyBuildings(null);
        if (allBuildings.isEmpty()) return;

        // Collect shop and service buildings as tourist targets
        List<BuildingState> touristTargets = new ArrayList<>();
        BuildingSavedData savedData = BuildingSavedData.get(level);
        for (BuildingData b : allBuildings) {
            String cat = b.getCategory();
            if (!"shop".equals(cat) && !"service".equals(cat)) continue;
            if (b.isShutdown() || !b.isStructureIntact()) continue;
            BuildingState state = savedData.getBuilding(b.getBuildingId());
            if (state != null) touristTargets.add(state);
        }
        if (touristTargets.isEmpty()) {
            LOGGER.debug("[TouristSpawn] No shop/service buildings — skipping spawn");
            return;
        }

        // Count existing tourists
        int existing = countExistingTourists(level);
        int targetCount = computeTargetCount(buildingApi);
        int toSpawn = Math.min(targetCount - existing, MAX_TOURISTS - existing);
        if (toSpawn <= 0) return;

        // Pick spawn positions from road edges (boundary positions)
        List<BlockPos> spawnCandidates = collectSpawnPositions(level, allBuildings);

        for (int i = 0; i < toSpawn; i++) {
            BlockPos spawnPos = pickSpawnPos(spawnCandidates, level);
            if (spawnPos == null) continue;

            BuildingState target = touristTargets.get(random.nextInt(touristTargets.size()));
            BlockPos interactionTarget = buildingApi.getInteractionTarget(target.getBuildingId());
            if (interactionTarget == null) interactionTarget = target.getAnchor();

            TouristEntity tourist = new TouristEntity(
                    com.wsteam.wandscape.Wandscape.TOURIST.get(), level);
            tourist.setTouristName(generateTouristName());
            tourist.setTouristMode(true);
            tourist.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
            tourist.setTargetBuildingId(target.getBuildingId());
            tourist.setTargetBuildingCategory(target.getCategory());
            tourist.setColonyId(target.getColonyId());
            tourist.setCommuteTarget(interactionTarget);
            // Tourist is always in COMMUTING state (moving to building)
            tourist.applyState(com.wsteam.wandscape.citizen.CitizenState.COMMUTING);
            level.addFreshEntity(tourist);

            LOGGER.info("[TouristSpawn] {} heading to {} '{}' at {}",
                    tourist.getTouristName(), target.getCategory(),
                    target.getBuildingTypeId(), interactionTarget.toShortString());
        }
    }

    private int computeTargetCount(BuildingApi api) {
        UUID colonyId = null; // use default colony for now
        int comfort = api.getColonyComfort(colonyId);
        int magic = api.getColonyMagic(colonyId);
        int wonder = api.getColonyWonder(colonyId);
        int total = comfort + magic + wonder;
        int base = Config.TOURIST_BASE_SPAWN_COUNT.get();
        int divisor = Config.TOURIST_EVAL_SCORE_DIVISOR.get();
        return Math.min(base + total / Math.max(1, divisor), MAX_TOURISTS);
    }

    private int countExistingTourists(ServerLevel level) {
        int count = 0;
        for (var entity : level.getAllEntities()) {
            if (entity instanceof TouristEntity t && t.isTouristMode() && t.isAlive()) {
                count++;
            }
        }
        return count;
    }

    private List<BlockPos> collectSpawnPositions(ServerLevel level, List<BuildingData> buildings) {
        List<BlockPos> positions = new ArrayList<>();

        // Prefer road edge positions
        RoadApi roadApi = getRoadApiSilently();
        if (roadApi != null) {
            RoadNetwork network = roadApi.getNetwork(null);
            if (network != null && !network.isEmpty()) {
                for (RoadEdge edge : network.getEdges().values()) {
                    var path = edge.getPath();
                    if (path.size() >= 2) {
                        positions.add(new BlockPos(
                                (int) path.get(0).x(),
                                (int) path.get(0).y(),
                                (int) path.get(0).z()));
                        positions.add(new BlockPos(
                                (int) path.get(path.size() - 1).x(),
                                (int) path.get(path.size() - 1).y(),
                                (int) path.get(path.size() - 1).z()));
                    }
                }
            }
        }

        // Fallback: building positions
        if (positions.isEmpty()) {
            for (BuildingData b : buildings) {
                positions.add(b.getPosition());
            }
        }

        return positions;
    }

    private BlockPos pickSpawnPos(List<BlockPos> candidates, ServerLevel level) {
        if (candidates.isEmpty()) return null;
        BlockPos picked = candidates.get(random.nextInt(candidates.size()));
        return findGround(level, picked.offset(
                random.nextInt(10) - 5, 0, random.nextInt(10) - 5));
    }

    private BlockPos findGround(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos(
                pos.getX(), Math.min(level.getMaxBuildHeight() - 1, 120), pos.getZ());
        while (mp.getY() > level.getMinBuildHeight()) {
            if (!level.getBlockState(mp).isAir()
                    && level.getBlockState(mp.above()).isAir())
                return mp.above().immutable();
            mp.move(0, -1, 0);
        }
        return pos;
    }

    // ── Cleanup ──

    /**
     * Satisfaction bands for departure behaviour:
     * <ul>
     *   <li><b>&ge; 100</b>: fully satisfied — mage resume stored instantly;
     *       leaves under normal conditions (energy depleted, night+idle, idle timeout).
     *       Does NOT seek hotels.</li>
     *   <li><b>&lt; 70</b>: unsatisfied — leaves under normal conditions
     *       (energy depleted, night + idle, idle timeout).</li>
     *   <li><b>70–99</b>: moderately satisfied — when energy depleted or at night,
     *       seek a hotel. If no vacancy, leave. Idle timeout also triggers
     *       departure.</li>
     * </ul>
     */
    private void cleanupTourists(ServerLevel level) {
        long dayTime = level.getDayTime() % 24000;
        boolean isNight = dayTime >= 13000;
        List<TouristEntity> toRemove = new ArrayList<>();

        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof TouristEntity t)) continue;
            if (!t.isTouristMode() || !t.isAlive()) continue;

            // Checked into hotel — safe, HotelStayHandler heartbeat manages them
            if (t.getCheckedInBuildingId() != null) continue;

            int sat = t.getSatisfaction();
            boolean energyDepleted = t.getEnergy() <= 0;
            boolean isIdle = t.getCommuteTarget() == null;
            boolean idleTimeout = isIdle && t.tickCount > Config.TOURIST_DESPAWN_TIMEOUT_TICKS.get();

            // Store mage resume instantly when satisfaction first reaches 100%
            if (sat >= 100 && t.isMage() && !t.isMageResumeStored()) {
                storeMageResume(t);
                t.setMageResumeStored(true);
            }

            if (sat < 70 || sat >= 100) {
                // Unsatisfied or fully satisfied — no hotel, leave under normal conditions
                if (energyDepleted || (isNight && isIdle) || idleTimeout) {
                    toRemove.add(t);
                }
            } else {
                // 70–99: seek hotel when energy low or at night
                if (energyDepleted || isNight) {
                    if (!tryRouteToHotel(t, level)) {
                        toRemove.add(t); // no vacancy → leave
                    }
                } else if (idleTimeout) {
                    toRemove.add(t);
                }
            }
        }

        for (TouristEntity t : toRemove) {
            onTouristDepart(t, level);
            t.discard();
        }
    }

    private void storeMageResume(TouristEntity t) {
        UUID colonyId = t.getColonyId();
        if (colonyId == null) return;
        try {
            var tavernApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getTavernApi();
            tavernApi.receiveMageResume(colonyId, t.getTouristName(), t.getLevel(),
                    t.getMaxMana(), t.getManaRegenRate(), t.getSpellPower(),
                    t.getSkinVariant());
            LOGGER.info("[TouristSpawn] Mage resume stored: {} (Lv.{})", t.getTouristName(), t.getLevel());
        } catch (IllegalStateException e) {
            LOGGER.warn("[TouristSpawn] TavernApi not available — mage resume lost: {}",
                    t.getTouristName());
        }
    }

    /**
     * Attempt to route a tourist to an available hotel.
     * If the tourist is already heading to a hotel, leaves them alone.
     *
     * @return true if a hotel with vacancy was found and the tourist was routed to it
     */
    private boolean tryRouteToHotel(TouristEntity t, ServerLevel level) {
        // Already heading to a hotel — don't interrupt
        UUID currentTarget = t.getTargetBuildingId();
        if (currentTarget != null && isHotelBuilding(currentTarget)) return true;

        HotelStayHandler hotel = HotelStayHandler.getActive();
        if (hotel == null) return false;

        BuildingApi api = getBuildingApi();
        if (api == null) return false;

        UUID colonyId = t.getColonyId();
        if (colonyId == null) return false;

        for (BuildingData b : api.getColonyBuildings(colonyId)) {
            if (!"service".equals(b.getCategory())) continue;
            if (b.isShutdown() || !b.isStructureIntact()) continue;
            if (t.hasVisitedBuilding(b.getBuildingId())) continue;
            if (!isHotelBuilding(b.getBuildingId())) continue;
            if (!hotel.hasVacancy(b.getBuildingId())) continue;

            BlockPos target = api.getInteractionTarget(b.getBuildingId());
            if (target == null) continue;

            t.setTargetBuildingId(b.getBuildingId());
            t.setTargetBuildingCategory("service");
            t.setCommuteTarget(target);
            LOGGER.info("[TouristSpawn] {} routed to hotel {} (sat={} energy={})",
                    t.getTouristName(), b.getBuildingId().toString().substring(0, 8),
                    t.getSatisfaction(), t.getEnergy());
            return true;
        }
        return false;
    }

    private boolean isHotelBuilding(UUID buildingId) {
        BuildingApi api = getBuildingApi();
        if (api == null) return false;
        var data = api.getBuilding(buildingId);
        if (data == null) return false;
        var config = BuildingConfigLoader.getInstance().get(data.getBuildingTypeId());
        return config != null && config.service() != null && config.service().maxOccupancy() > 0;
    }

    /** Register departure and fire events. Colony reward logic is deferred to
     * subscribers of {@link com.wsteam.wandscape.shared.event.TouristDepartedEvent}. */
    private void onTouristDepart(TouristEntity t, ServerLevel level) {
        // Check out of hotel if still checked in
        HotelStayHandler hotel = HotelStayHandler.getActive();
        if (hotel != null && hotel.isCheckedIn(t.getUUID())) {
            hotel.checkOut(t);
        }

        UUID colonyId = t.getColonyId();
        int satisfaction = t.getSatisfaction();

        // Register departure via TouristApi → fires TouristDepartedEvent
        var touristApi = getTouristApi();
        if (touristApi != null && colonyId != null) {
            touristApi.registerDeparture(t.getUUID(), colonyId, satisfaction);
        }

        // Safety net: store mage resume at departure if not already stored
        if (t.isMage() && satisfaction >= 100 && !t.isMageResumeStored()) {
            storeMageResume(t);
        }

        LOGGER.debug("[TouristSpawn] {} departed (energy={} satisfaction={} mage={})",
                t.getTouristName(), t.getEnergy(), satisfaction, t.isMage());
    }

    // ── Name generation ──

    private static final String[] TOURIST_SURNAMES = {
        "王","李","张","刘","陈","杨","赵","黄","周","吴",
        "游客","旅人","行者","访客","商贾"
    };
    private static final String[] TOURIST_GIVENS = {
        "明","华","文","伟","芳","丽","强","勇","静",
        "慧","敏","俊","杰","兰","玲","超","平","刚","涛"
    };

    private String generateTouristName() {
        String surname = TOURIST_SURNAMES[random.nextInt(TOURIST_SURNAMES.length)];
        String given = TOURIST_GIVENS[random.nextInt(TOURIST_GIVENS.length)];
        return surname + given;
    }

    // ── API helpers ──

    @javax.annotation.Nullable
    private static BuildingApi getBuildingApi() {
        try { return WandscapeApis.getBuildingApi(); }
        catch (IllegalStateException e) { return null; }
    }

    @javax.annotation.Nullable
    private static RoadApi getRoadApiSilently() {
        try { return WandscapeApis.getRoadApi(); }
        catch (IllegalStateException e) { return null; }
    }

    @javax.annotation.Nullable
    private static TouristApi getTouristApi() {
        try { return WandscapeApis.getTouristApi(); }
        catch (IllegalStateException e) { return null; }
    }
}
