package com.wsteam.wandscape.tourist.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.core.event.NarrativeEventTriggered;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.colony.ColonyLevelManager;
import com.wsteam.wandscape.road.core.RoadEdge;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.api.RoadApi;
import com.wsteam.wandscape.shared.api.TouristApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.NarrativeEvent;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.tourist.entity.TouristEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Manages tourist spawning and cleanup with colony-level-driven mechanics.
 *
 * <p><b>Three-phase daily cycle:</b>
 * <ul>
 *   <li>Morning (0-1000): daily reset, hotel checkout
 *   <li>Spawn window (1000-13000): tourists spawn at distributed random times
 *   <li>Evening (13000-18000): no new spawns, existing tourists continue interactions
 *   <li>Night departure (18000-24000): satisfaction-based departure + hotel routing
 * </ul>
 *
 * <p><b>Spawn count:</b> base(6) + colonyLevel × levelSpawnBonus(3), randomized × 0.8~1.2
 * <br><b>Tourist level distribution:</b> colonyLevel-1 (40%), colonyLevel (40%), colonyLevel+1 (20%)
 */
public final class TouristSpawnSystem {
    private static final String TAG = "TouristSpawnSystem";

    /** Tick interval between spawn/cleanup checks. */
    private static final int CHECK_INTERVAL = 100;
    /** Tick offset after departure window start before first purge. */
    private static final int DEPARTURE_INITIAL_DELAY = 200;
    /** Max active tourists hard cap. */
    private static final int MAX_TOURISTS = 30;

    // ── Daily spawn schedule ──

    record PendingSpawn(int level, int spawnTime, BlockPos spawnPos, UUID buildingId) {}

    private int tickCounter;
    private boolean scheduleCreated;
    private List<PendingSpawn> pendingSpawns = new ArrayList<>();
    /** Track which day (dayTime/24000) the schedule was created for. */
    private long scheduleDay = -1;

    // ── Night departure delays ──
    /** touristId → game tick at which departure triggers. */
    private final Map<UUID, Long> pendingDepartures = new HashMap<>();

    private final Random random = new Random();

    // ── Colony level manager (set from outside) ──

    private ColonyLevelManager levelManager;

    private TouristSpawnSystem() {}

    /** Registered singleton, used by debug commands. */
    private static TouristSpawnSystem instance;

    public static TouristSpawnSystem register() {
        instance = new TouristSpawnSystem();
        NeoForge.EVENT_BUS.register(instance);
        return instance;
    }

    /** Inject the colony level manager after it's created. */
    public static void setLevelManager(ColonyLevelManager mgr) {
        if (instance != null) instance.levelManager = mgr;
    }

    /**
     * Immediately trigger a spawn cycle regardless of game-time gates.
     * Intended for debug/command use.
     */
    public static void forceSpawn(ServerLevel level) {
        if (instance == null) {
            Log.warn(TAG, "[Tourist] SpawnSystem not registered — cannot force spawn");
            return;
        }
        instance.createSchedule(level);
        // Force all pending spawns immediately (ignore spawn time gating)
        List<PendingSpawn> all = new ArrayList<>(instance.pendingSpawns);
        instance.pendingSpawns.clear();
        BuildingApi buildingApi = getBuildingApi();
        for (PendingSpawn ps : all) {
            var target = buildingApi != null ? buildingApi.getBuilding(ps.buildingId()) : null;
            if (target == null || target.isShutdown() || !target.isStructureIntact() || target.isDemolishing()) {
                continue;
            }
            BlockPos interactionTarget = buildingApi.getTouristInteractionTarget(ps.buildingId());
            if (interactionTarget == null) interactionTarget = target.getPosition();

            TouristEntity tourist = new TouristEntity(
                    com.wsteam.wandscape.Wandscape.TOURIST.get(), level);
            tourist.setTouristName(generateRandomTouristName());
            tourist.setPos(ps.spawnPos.getX() + 0.5, ps.spawnPos.getY(), ps.spawnPos.getZ() + 0.5);
            tourist.setLevel(ps.level);
            tourist.setWallet(startingWallet(ps.level));
            tourist.setInitialWallet(startingWallet(ps.level));
            tourist.setTargetBuildingId(ps.buildingId());
            tourist.setTargetBuildingCategory(target.getCategory());
            tourist.setColonyId(target.getColonyId());
            tourist.setCommuteTarget(interactionTarget);
            tourist.setArrivalTime(level.getGameTime());
            tourist.applyState(TouristState.VISITING);
            level.addFreshEntity(tourist);

            // Register arrival
            var touristApi = getTouristApi();
            if (touristApi != null) {
                touristApi.registerArrival(tourist.getUUID(), target.getColonyId());
            }

            // Create the data shadow so the sim can track this tourist when its chunk unloads.
            TouristSimSystem sim = TouristSimSystem.getActive();
            if (sim != null) sim.adoptTourist(tourist);
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (level == null) return;

        tickCounter++;
        if (tickCounter % CHECK_INTERVAL != 0) return;

        long dayTime = level.getDayTime() % 24000;
        long day = level.getDayTime() / 24000;

        // ── Morning: reset schedule flag + count overnight stayers ──
        if (dayTime < 1000 && scheduleDay != day) {
            scheduleCreated = false;
            pendingSpawns.clear();
            scheduleDay = -1;
            countOvernightStayers(level);
        }

        // ── Spawn window (1000-13000) ──
        boolean inSpawnWindow = dayTime >= Config.TOURIST_SPAWN_WINDOW_START.get()
                && dayTime < Config.TOURIST_SPAWN_WINDOW_END.get();
        if (inSpawnWindow) {
            if (!scheduleCreated || scheduleDay != day) {
                createSchedule(level);
                scheduleDay = day;
            }
            flushPendingSpawns(level);
        }

        // ── Night departure window (18000-24000) ──
        boolean inDepartureWindow = dayTime >= Config.TOURIST_DEPARTURE_WINDOW_START.get()
                && dayTime < Config.TOURIST_DEPARTURE_WINDOW_END.get();

        // Always run cleanup for energy/idle/timeout regardless of time
        cleanupTourists(level, inDepartureWindow);

        // Night departure processing
        if (inDepartureWindow) {
            processNightDepartures(level);
        } else {
            pendingDepartures.clear(); // not in departure window, clear stale delays
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Spawn schedule
    // ════════════════════════════════════════════════════════════════

    /**
     * Calculate today's target spawn count and create pending spawns with
     * distributed spawn times across the spawn window.
     */
    private void createSchedule(ServerLevel level) {
        scheduleCreated = true;
        pendingSpawns.clear();

        BuildingApi buildingApi = getBuildingApi();
        if (buildingApi == null) {
            Log.warn(TAG, "[Tourist] BuildingApi not available — cannot create spawn schedule");
            return;
        }

        List<BuildingData> allBuildings = buildingApi.getColonyBuildings(null);
        if (allBuildings.isEmpty()) {
            Log.warn(TAG, "[Tourist] No buildings found in colony — cannot spawn tourists. "
                    + "Build a town hall and register a colony first.");
            return;
        }

        // Collect valid shop/service targets
        List<BuildingState> touristTargets = getTouristTargets(level, allBuildings);
        if (touristTargets.isEmpty()) {
            Log.warn(TAG, "[Tourist] No intact shop/service buildings available — "
                    + "tourists have no targets. Build shops or service buildings to attract tourists.");
            return;
        }

        // Get colony ID to query colony level
        UUID colonyId = getColonyId();
        if (colonyId == null) {
            Log.warn(TAG, "[Tourist] No colony registered — tourists cannot spawn. "
                    + "Use '/wandscape colony create' to create a colony, then build a town hall within range.");
            return;
        }

        // Count existing tourists
        int existing = countExistingTourists(level);

        // Compute target count: base + colonyLevel × bonus, randomized × 0.8~1.2
        int colonyLevel = levelManager != null ? levelManager.getLevel(colonyId) : 1;
        int base = Config.TOURIST_BASE_SPAWN_COUNT.get();
        int levelBonus = colonyLevel * Config.TOURIST_LEVEL_SPAWN_BONUS.get();
        int rawTarget = base + levelBonus;
        int targetCount = (int) Math.round(rawTarget * (0.8 + random.nextDouble() * 0.4));
        targetCount = Math.max(1, Math.min(targetCount, Config.TOURIST_MAX_PER_COLONY.get()));
        targetCount = Math.min(targetCount, MAX_TOURISTS);

        int toSpawn = Math.max(0, targetCount - existing);
        if (toSpawn <= 0) return;

        // Collect spawn positions
        List<BlockPos> spawnCandidates = collectSpawnPositions(level, allBuildings);

        // Create pending spawns with random levels and spawn times distributed across the window
        int windowStart = Config.TOURIST_SPAWN_WINDOW_START.get();
        int windowDuration = Config.TOURIST_SPAWN_WINDOW_END.get() - windowStart;
        for (int i = 0; i < toSpawn; i++) {
            BlockPos spawnPos = pickSpawnPos(spawnCandidates, level);
            if (spawnPos == null) continue;

            // Pick tourist level based on colony level distribution
            int touristLevel = rollTouristLevel(colonyLevel);

            // Pick target building weighted by preference. Only the buildingId is
            // stored — the target is re-validated and interaction point re-derived
            // at spawn time, so a building demolished after scheduling can't ghost it.
            BuildingState target = touristTargets.get(random.nextInt(touristTargets.size()));

            // Assign random spawn time distributed across the spawn window
            int spawnTime = windowStart + (windowDuration > 0 ? random.nextInt(windowDuration) : 0);

            pendingSpawns.add(new PendingSpawn(touristLevel, spawnTime, spawnPos, target.getBuildingId()));
        }

        if (!pendingSpawns.isEmpty()) {
            Log.info(TAG, "[Tourist] Schedule created: {} tourists (colony Lv.{}), targetCount={}",
                    pendingSpawns.size(), colonyLevel, targetCount);
        }
    }

    /** Spawn pending tourists whose spawn time has passed. */
    private void flushPendingSpawns(ServerLevel level) {
        if (pendingSpawns.isEmpty()) return;

        long dayTime = level.getDayTime() % 24000;
        BuildingApi buildingApi = getBuildingApi();
        if (buildingApi == null) return;

        List<PendingSpawn> remaining = new ArrayList<>();
        for (PendingSpawn ps : pendingSpawns) {
            if (dayTime >= ps.spawnTime()) {
                // Re-validate the target at spawn time — the building may have been
                // demolished after scheduling. Never spawn a tourist heading to a ghost.
                var target = buildingApi.getBuilding(ps.buildingId());
                if (target == null || target.isShutdown() || !target.isStructureIntact() || target.isDemolishing()) {
                    Log.debug(TAG, "[Tourist] dropping pending spawn — target building gone/demolished: {}",
                            ps.buildingId().toString().substring(0, 8));
                    continue;
                }
                BlockPos interactionTarget = buildingApi.getTouristInteractionTarget(ps.buildingId());
                if (interactionTarget == null) interactionTarget = target.getPosition();

                TouristEntity tourist = new TouristEntity(
                        com.wsteam.wandscape.Wandscape.TOURIST.get(), level);
                tourist.setTouristName(generateRandomTouristName());
                tourist.setPos(ps.spawnPos.getX() + 0.5, ps.spawnPos.getY(), ps.spawnPos.getZ() + 0.5);
                tourist.setLevel(ps.level);
                tourist.setWallet(startingWallet(ps.level));
                tourist.setInitialWallet(startingWallet(ps.level));
                tourist.setTargetBuildingId(ps.buildingId());
                tourist.setTargetBuildingCategory(target.getCategory());
                tourist.setColonyId(target.getColonyId());
                tourist.setCommuteTarget(interactionTarget);
                tourist.setArrivalTime(level.getGameTime());
                tourist.applyState(TouristState.VISITING);
                level.addFreshEntity(tourist);

                // Register arrival → updates TouristApi colonyTourists map + fires TouristArrivedEvent
                var touristApi = getTouristApi();
                if (touristApi != null) {
                    touristApi.registerArrival(tourist.getUUID(), target.getColonyId());
                }

                // Create the data shadow so the sim can track this tourist when its chunk unloads.
                TouristSimSystem sim = TouristSimSystem.getActive();
                if (sim != null) sim.adoptTourist(tourist);

                Log.info(TAG, "[Tourist] {} (Lv.{}) spawned, heading to {} '{}' at {}",
                        tourist.getTouristName(), ps.level, target.getCategory(),
                        target.getBuildingTypeId(), interactionTarget.toShortString());
            } else {
                remaining.add(ps);
            }
        }
        pendingSpawns = remaining;
    }

    // ════════════════════════════════════════════════════════════════
    // Tourist level distribution
    // ════════════════════════════════════════════════════════════════

    /**
     * Roll a tourist level based on colony level.
     * Distribution: colonyLevel-1 (40%), colonyLevel (40%), colonyLevel+1 (20%)
     */
    private int rollTouristLevel(int colonyLevel) {
        double roll = random.nextDouble();
        int level;
        if (roll < 0.4) {
            level = colonyLevel - 1;
        } else if (roll < 0.8) {
            level = colonyLevel;
        } else {
            level = colonyLevel + 1;
        }
        return Math.max(1, level);
    }

    // ════════════════════════════════════════════════════════════════
    // Cleanup
    // ════════════════════════════════════════════════════════════════

    /**
     * Cleanup logic applying to all times of day:
     * <ul>
     *   <li>Energy depleted → leave</li>
     *   <li>Idle timeout → leave</li>
     *   <li>Night-specific: handled by {@link #processNightDepartures}</li>
     * </ul>
     */
    private void cleanupTourists(ServerLevel level, boolean inDepartureWindow) {
        List<TouristEntity> toRemove = new ArrayList<>();

        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof TouristEntity t)) continue;
            if (!t.isAlive()) continue;

            // Checked into hotel — safe, HotelStayHandler heartbeat manages them
            if (t.getCheckedInBuildingId() != null) continue;

            // Store mage resume instantly when satisfaction first reaches 100%
            if (t.getSatisfaction() >= 100 && t.isMage() && !t.isMageResumeStored()) {
                storeMageResume(t);
                t.setMageResumeStored(true);
            }

            // In departure window, satisfaction-based logic is handled by processNightDepartures
            if (inDepartureWindow) continue;

            int sat = t.getSatisfaction();
            boolean energyDepleted = t.getEnergy() <= 0;
            boolean isIdle = t.getCommuteTarget() == null;
            boolean idleTimeout = isIdle && t.tickCount > Config.TOURIST_DESPAWN_TIMEOUT_TICKS.get();

            // Daytime/evening: standard departure conditions (energy, idle timeout, night)
            long dayTime = level.getDayTime() % 24000;
            boolean isNight = dayTime >= 13000;

            if (sat >= 100) {
                // 满意度已满 → 离开
                if (energyDepleted || (isNight && isIdle) || idleTimeout) {
                    toRemove.add(t);
                }
            } else if (sat >= 50) {
                // 50-99: 精力耗尽或夜晚 → 引导去酒店
                if (energyDepleted || isNight) {
                    if (!tryRouteToHotel(t, level)) {
                        toRemove.add(t);
                    }
                } else if (idleTimeout) {
                    toRemove.add(t);
                }
            } else {
                // sat < 50: 满意度不足 → 离开
                if (energyDepleted || (isNight && isIdle) || idleTimeout) {
                    toRemove.add(t);
                }
            }
        }

        for (TouristEntity t : toRemove) {
            onTouristDepart(t, level);
            t.discard();
        }
    }

    /**
     * Night departure window: satisfaction-based departure and hotel routing.
     *
     * <ul>
     *   <li>Satisfaction &lt; 50: leave (with 0-1500 tick random delay)</li>
     *   <li>Satisfaction = 100: leave (with delay, resume already stored)</li>
     *   <li>Satisfaction 50-99: route to hotel (no delay), no vacancy → leave</li>
     * </ul>
     */
    private void processNightDepartures(ServerLevel level) {
        long gameTime = level.getGameTime();
        List<TouristEntity> toRemove = new ArrayList<>();

        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof TouristEntity t)) continue;
            if (!t.isAlive()) continue;
            if (t.getCheckedInBuildingId() != null) continue;

            // Store mage resume instantly (already done in cleanupTourists, but double-check)
            if (t.getSatisfaction() >= 100 && t.isMage() && !t.isMageResumeStored()) {
                storeMageResume(t);
                t.setMageResumeStored(true);
            }

            int sat = t.getSatisfaction();

            if (sat < 50 || sat >= 100) {
                // Check if departure delay is already assigned
                Long departAt = pendingDepartures.get(t.getUUID());
                if (departAt == null) {
                    // Assign random delay 0-1500 ticks
                    int delay = random.nextInt(Config.TOURIST_DEPARTURE_DELAY_MAX_TICKS.get() + 1);
                    departAt = gameTime + delay;
                    pendingDepartures.put(t.getUUID(), departAt);
                }
                // Check if delay elapsed
                if (gameTime >= departAt) {
                    toRemove.add(t);
                    pendingDepartures.remove(t.getUUID());
                }
            } else if (sat >= 50) {
                // 50-99: route to hotel, no vacancy → leave
                pendingDepartures.remove(t.getUUID());
                if (!tryRouteToHotel(t, level)) {
                    toRemove.add(t);
                }
            }
        }

        for (TouristEntity t : toRemove) {
            onTouristDepart(t, level);
            t.discard();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Experience & resume
    // ════════════════════════════════════════════════════════════════

    /**
     * Grant colony experience when a tourist departs with 100% satisfaction.
     */
    private void grantExperience(TouristEntity t) {
        if (t.getSatisfaction() < 100) return;
        if (levelManager == null) return;
        UUID colonyId = t.getColonyId();
        if (colonyId == null) return;
        int colonyLevel = levelManager.getLevel(colonyId);
        int contribution = ColonyLevelManager.computeExpContribution(colonyLevel, t.getLevel());
        if (contribution > 0) {
            levelManager.addExperience(colonyId, contribution);
            Log.info(TAG, "[Tourist] {} (Lv.{}) granted {} exp to colony Lv.{} (sat=100%)",
                    t.getTouristName(), t.getLevel(), contribution, colonyLevel);
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
            Log.info(TAG, "[Tourist] Mage resume stored: {} (Lv.{})", t.getTouristName(), t.getLevel());
        } catch (IllegalStateException e) {
            Log.warn(TAG, "[Tourist] TavernApi not available — mage resume lost: {}",
                    t.getTouristName());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Departure
    // ════════════════════════════════════════════════════════════════

    /** Register departure, grant experience, fire events, generate narrative. */
    private void onTouristDepart(TouristEntity t, ServerLevel level) {
        // Check out of hotel if still checked in
        HotelStayHandler hotel = HotelStayHandler.getActive();
        if (hotel != null && hotel.isCheckedIn(t.getUUID())) {
            hotel.checkOut(t, level);
        }

        UUID colonyId = t.getColonyId();
        int satisfaction = t.getSatisfaction();

        // Grant colony experience if satisfaction 100%
        if (satisfaction >= 100) {
            grantExperience(t);
        }

        // Safety net: store mage resume at departure if not already stored
        if (t.isMage() && satisfaction >= 100 && !t.isMageResumeStored()) {
            storeMageResume(t);
        }

        // Register departure via TouristApi → fires TouristDepartedEvent
        var touristApi = getTouristApi();
        if (touristApi != null && colonyId != null) {
            touristApi.registerDeparture(t.getUUID(), colonyId, satisfaction);
        }

        // Remove the data shadow — a departed tourist has no sim state left.
        TouristSimSystem sim = TouristSimSystem.getActive();
        if (sim != null) sim.removeShadow(t.getUUID());

        // Generate departure narrative (no on-screen text — silent by design)
        int visitCount = t.getRecentVisits().size();

        NarrativeEvent departureEvent = NarrativeGenerator.generateDeparture(
                t.getTouristName(), satisfaction, visitCount, t.level().getGameTime());
        emitNarrativeEvent(departureEvent);

        Log.debug(TAG, "[Tourist] {} departed (energy={} satisfaction={} mage={})",
                t.getTouristName(), t.getEnergy(), satisfaction, t.isMage());
    }

    // ════════════════════════════════════════════════════════════════
    // Hotel routing
    // ════════════════════════════════════════════════════════════════

    /**
     * Attempt to route a tourist to an available hotel.
     */
    private boolean tryRouteToHotel(TouristEntity t, ServerLevel level) {
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
            if (!isHotelBuilding(b.getBuildingId())) continue;
            // 酒店豁免 visitedBuildings：白天逛过 inn 不应阻止夜晚入住
            if (!hotel.hasVacancy(b.getBuildingId())) continue;

            BlockPos target = api.getTouristInteractionTarget(b.getBuildingId());
            if (target == null) continue;

            t.setTargetBuildingId(b.getBuildingId());
            t.setTargetBuildingCategory("service");
            t.setCommuteTarget(target);
            Log.info(TAG, "[Tourist] {} routed to hotel {} (sat={} energy={})",
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

    // ════════════════════════════════════════════════════════════════
    // Target & spawn position helpers
    // ════════════════════════════════════════════════════════════════

    private List<BuildingState> getTouristTargets(ServerLevel level, List<BuildingData> allBuildings) {
        List<BuildingState> targets = new ArrayList<>();
        BuildingSavedData savedData = BuildingSavedData.get(level);
        for (BuildingData b : allBuildings) {
            String cat = b.getCategory();
            if (!"shop".equals(cat) && !"service".equals(cat)) continue;
            if (b.isShutdown() || !b.isStructureIntact()) continue;
            BuildingState state = savedData.getBuilding(b.getBuildingId());
            if (state != null) targets.add(state);
        }
        return targets;
    }

    private int computeTargetCount(BuildingApi api) {
        UUID colonyId = null;
        int base = Config.TOURIST_BASE_SPAWN_COUNT.get();
        int levelBonus = 0;
        if (levelManager != null && colonyId != null) {
            levelBonus = levelManager.getLevel(colonyId) * Config.TOURIST_LEVEL_SPAWN_BONUS.get();
        }
        int raw = base + levelBonus;
        return (int) Math.round(raw * (0.8 + random.nextDouble() * 0.4));
    }

    private int countExistingTourists(ServerLevel level) {
        int count = 0;
        for (var entity : level.getAllEntities()) {
            if (entity instanceof TouristEntity t && t.isAlive()) {
                count++;
            }
        }
        return count;
    }

    /** Count tourists currently checked into hotels per colony and store as overnight stayers. */
    private void countOvernightStayers(ServerLevel level) {
        java.util.Map<UUID, Integer> overnightCounts = new java.util.HashMap<>();
        for (var entity : level.getAllEntities()) {
            if (entity instanceof TouristEntity t && t.isAlive() && t.getCheckedInBuildingId() != null) {
                UUID colonyId = t.getColonyId();
                if (colonyId != null) overnightCounts.merge(colonyId, 1, Integer::sum);
            }
        }
        // Include unloaded (sim) guests — their shadows carry the check-in state.
        TouristSimSystem sim = TouristSimSystem.getActive();
        if (sim != null && sim.getRegistry() != null) {
            for (TouristShadow s : sim.getRegistry().getShadows().values()) {
                if (s.getCheckedInBuildingId() != null && s.getColonyId() != null) {
                    overnightCounts.merge(s.getColonyId(), 1, Integer::sum);
                }
            }
        }
        var touristApi = getTouristApi();
        if (touristApi instanceof TouristApiImpl impl) {
            for (var entry : overnightCounts.entrySet()) {
                impl.setOvernightStayerCount(entry.getKey(), entry.getValue());
            }
            Log.info(TAG, "[Tourist] Overnight stayers counted: {}", overnightCounts);
        }
    }

    private List<BlockPos> collectSpawnPositions(ServerLevel level, List<BuildingData> buildings) {
        List<BlockPos> positions = new ArrayList<>();
        RoadApi roadApi = getRoadApiSilently();
        if (roadApi != null) {
            RoadNetwork network = roadApi.getNetwork(null);
            if (network != null && !network.isEmpty()) {
                for (RoadEdge edge : network.getEdges().values()) {
                    if (edge.getStatus() != RoadEdge.EdgeStatus.COMPLETE) continue;
                    var path = edge.getPath();
                    if (path.size() >= 2) {
                        positions.add(new BlockPos(
                                (int) path.get(0).x(), (int) path.get(0).y(), (int) path.get(0).z()));
                        positions.add(new BlockPos(
                                (int) path.get(path.size() - 1).x(),
                                (int) path.get(path.size() - 1).y(),
                                (int) path.get(path.size() - 1).z()));
                    }
                }
            }
        }
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

    // ── Name generation ──

    private static final String[] TOURIST_SURNAMES = {
        "王","李","张","刘","陈","杨","赵","黄","周","吴",
        "游客","旅人","行者","访客","商贾"
    };
    private static final String[] TOURIST_GIVENS = {
        "明","华","文","伟","芳","丽","强","勇","静",
        "慧","敏","俊","杰","兰","玲","超","平","刚","涛"
    };

    private static final Random NAME_RANDOM = new Random();

    public static String generateRandomTouristName() {
        String surname = TOURIST_SURNAMES[NAME_RANDOM.nextInt(TOURIST_SURNAMES.length)];
        String given = TOURIST_GIVENS[NAME_RANDOM.nextInt(TOURIST_GIVENS.length)];
        return surname + given;
    }

    /** Universal-element starting wallet: base + level × per-level bonus. */
    private static int startingWallet(int level) {
        return Config.TOURIST_BASE_WALLET.get() + Math.max(0, level) * Config.TOURIST_WALLET_PER_LEVEL.get();
    }

    // ── Colony ID helper ──

    @javax.annotation.Nullable
    private static UUID getColonyId() {
        ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi == null) return null;
        var ids = colonyApi.getAllColonyIds();
        if (ids.isEmpty()) return null;
        // Single-colony MVP: return the first colony UUID
        return ids.iterator().next();
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

    private static void emitNarrativeEvent(NarrativeEvent ne) {
        var world = WandscapeEngine.getWorld();
        if (world != null && world.eventBus != null) {
            world.eventBus.emit(new NarrativeEventTriggered(ne));
        }
    }
}
