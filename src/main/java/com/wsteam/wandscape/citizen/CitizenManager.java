package com.wsteam.wandscape.citizen;

import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.event.BuildingPlacedEvent;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Singleton manager for citizen NPC lifecycle.
 *
 * <h3>Visibility rule</h3>
 * Citizens only exist as spawned entities during visible states
 * ({@link CitizenState#COMMUTING}, {@link CitizenState#LEISURE},
 * {@link CitizenState#IDLE}). During {@link CitizenState#WORKING} and
 * {@link CitizenState#SLEEPING} they are discarded and held as
 * {@link StoredCitizen} data — no entity exists in the world.
 *
 * <h3>Daily schedule</h3>
 * <pre>
 *   06:00  SLEEPING → respawn → COMMUTING(→workplace)
 *   06:30  arrived → WORKING (despawn)
 *   17:30  WORKING → respawn → COMMUTING(→home)
 *   18:00  arrived → LEISURE (city wandering, rendered)
 *   22:00  LEISURE → SLEEPING (despawn)
 * </pre>
 */
public class CitizenManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final CitizenManager INSTANCE = new CitizenManager();

    private CitizenManager() {}
    public static CitizenManager getInstance() { return INSTANCE; }

    // ──────────────────────── Schedule ────────────────────────

    private static final int HARD_CAP = 15;

    /** MC dayTime (0–24000). 0 = 6:00 AM. */
    static final long T_WAKE_UP      =    0; //  6:00
    static final long T_WORK_START   =  500; //  6:30
    static final long T_HOME_COMMUTE = 11500; // 17:30
    static final long T_LEISURE      = 12000; // 18:00
    static final long T_SLEEP        = 14000; // 22:00

    // ──────────────────────── Profession → building category ────────────────────────

    private static final Map<Profession, List<String>> PROF_CATS = Map.of(
            Profession.FARMER,   List.of("farm"),
            Profession.MERCHANT, List.of("market", "storage"),
            Profession.SCHOLAR,  List.of("library", "academy"),
            Profession.ARTISAN,  List.of("workshop", "production", "crafting_station"),
            Profession.GUARD,    List.of("garrison", "wall")
    );

    // ──────────────────────── Name pool ────────────────────────

    private static final String[] SURNAMES = {
            "李","王","张","刘","陈","杨","赵","黄","周","吴",
            "徐","孙","马","胡","朱","郭","何","罗","高","林",
            "郑","梁","谢","宋","唐","许","韩","冯","邓","曹",
            "彭","曾","萧","田","董","潘","袁","蔡","蒋","余",
            "于","杜","叶","程","苏","魏","吕","丁","任","沈"
    };
    private static final String[] GIVENS = {
            "明","华","文","伟","芳","秀英","丽","强","勇","静",
            "慧","敏","俊","杰","兰","玲","超","平","刚","涛",
            "斌","霞","红","建国","海燕","宁","磊","洋","辉","鑫",
            "怡","珊","君","佳","晨","宇","涵","浩","博","瑞",
            "思远","晓","雨","梦","毅","恒","淑珍","志强","雪","云"
    };

    // ──────────────────────── Stored citizen (despawned state) ────────────────────────

    // ──────────────────────── Runtime state ────────────────────────

    /** Currently spawned entities. */
    private final Map<UUID, CitizenEntity> active = new HashMap<>();
    /** Despawned citizens (WORKING or SLEEPING). */
    private final Map<UUID, StoredCitizen> stored = new LinkedHashMap<>();
    private final Set<String> usedNames = new HashSet<>();
    private final Map<UUID, BlockPos> bedAssignments = new HashMap<>();
    private final Map<UUID, BlockPos> workplaceAssignments = new HashMap<>();
    private final Map<UUID, BlockPos> homeAssignments = new HashMap<>();

    /** Cached list of all building anchors (for LEISURE POIs). */
    private List<BlockPos> cachedPoiList = List.of();

    private final Random random = new Random();
    private boolean registered = false;
    private ServerLevel lastLevel = null;

    // ──────────────────────── NeoForge ────────────────────────

    public void register() {
        if (registered) return;
        NeoForge.EVENT_BUS.register(this);
        registered = true;
        LOGGER.info("[Citizen] registered on NeoForge EVENT_BUS");
    }

    // ──────────────────────── Tick ────────────────────────

    public void tick(ServerLevel level) {
        this.lastLevel = level;
        long dayTime = level.getDayTime() % 24000;

        // ── 1. Stored citizens: check if any should respawn ──
        for (var it = stored.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            UUID id = e.getKey();
            StoredCitizen sc = e.getValue();

            BlockPos spawnAt = null;
            BlockPos commuteTo = null;

            if (sc.storedState() == CitizenState.SLEEPING
                    && dayTime >= T_WAKE_UP && dayTime < T_WORK_START) {
                spawnAt = sc.home() != null ? sc.home() : sc.bed();
                commuteTo = sc.workplace() != null ? sc.workplace() : spawnAt;
            }
            if (sc.storedState() == CitizenState.WORKING
                    && dayTime >= T_HOME_COMMUTE && dayTime < T_LEISURE) {
                spawnAt = sc.workplace();
                commuteTo = sc.home() != null ? sc.home() : sc.bed();
            }
            if (sc.storedState() == CitizenState.IDLE
                    && dayTime >= T_LEISURE && dayTime < T_SLEEP) {
                // IDLER out for evening leisure
                spawnAt = sc.home() != null ? sc.home() : sc.bed();
                commuteTo = null; // leisure, no commute target
            }

            if (spawnAt != null) {
                BlockPos ground = findGround(level, spawnAt.offset(
                        random.nextInt(4) - 2, 0, random.nextInt(4) - 2));
                CitizenEntity c = spawnEntity(level, ground, sc);
                if (c != null) {
                    UUID newId = c.getUUID();
                    transferAssignments(id, newId, sc);
                    if (commuteTo != null) {
                        c.applyState(CitizenState.COMMUTING);
                        c.setCommuteTarget(commuteTo);
                        LOGGER.info("[Citizen] {} respawned ({}→COMMUTING, target={})",
                                sc.name(), sc.storedState().getDisplayName(), commuteTo);
                    } else {
                        transitionTo(newId, c, CitizenState.LEISURE);
                        LOGGER.info("[Citizen] {} respawned ({}→LEISURE)",
                                sc.name(), sc.storedState().getDisplayName());
                    }
                    it.remove();
                }
            }
        }

        // ── 2. Active citizens: state machine (snapshot — storeAndDespawn may modify active)
        for (var e : List.copyOf(active.entrySet())) {
            UUID id = e.getKey();
            CitizenEntity c = e.getValue();
            if (c.isRemoved() || !active.containsKey(id)) {
                cleanup(id, c.getCitizenName());
                continue;
            }
            driveActive(id, c, dayTime);
        }
    }

    // ──────────────────────── Active citizen state machine ────────────────────────

    private void driveActive(UUID id, CitizenEntity c, long dayTime) {
        CitizenState cur = c.getCurrentState();

        // ── Commute arrived → transition to destination ──
        if (cur == CitizenState.COMMUTING && c.isCommuteArrived()) {
            c.setCommuteArrived(false);

            // Evening leisure (18:00-22:00): arrived home → wander city
            if (dayTime >= T_LEISURE && dayTime < T_SLEEP) {
                transitionTo(id, c, CitizenState.LEISURE);
                return;
            }
            // Night: arrived home → sleep
            if (dayTime >= T_SLEEP || dayTime < T_WAKE_UP) {
                storeAndDespawn(id, c, CitizenState.SLEEPING);
                return;
            }
            // Morning (06:00-06:30): arrived at workplace → work
            if (dayTime >= T_WAKE_UP && dayTime < T_WORK_START) {
                storeAndDespawn(id, c, CitizenState.WORKING);
                return;
            }
            // Workday hours: if arrived and has workplace → work
            if (workplaceAssignments.containsKey(id)) {
                storeAndDespawn(id, c, CitizenState.WORKING);
                return;
            }
            // Evening commute (17:30-18:00): arrived home → leisure
            if (dayTime >= T_HOME_COMMUTE && dayTime < T_LEISURE) {
                transitionTo(id, c, CitizenState.LEISURE);
                return;
            }
            // Fallback
            transitionTo(id, c, CitizenState.IDLE);
            return;
        }

        // ── Morning: wake → commute to work ──
        if (dayTime >= T_WAKE_UP && dayTime < T_WORK_START) {
            if (cur == CitizenState.COMMUTING) return;
            if (cur == CitizenState.LEISURE || cur == CitizenState.IDLE) {
                transitionTo(id, c, CitizenState.COMMUTING);
                c.setCommuteTarget(workplaceAssignments.get(id));
            }
            return;
        }

        // ── Workday ──
        if (dayTime >= T_WORK_START && dayTime < T_HOME_COMMUTE) {
            if (cur == CitizenState.COMMUTING) return; // en route
            if (workplaceAssignments.containsKey(id)) {
                storeAndDespawn(id, c, CitizenState.WORKING);
            } else {
                storeAndDespawn(id, c, CitizenState.IDLE);
            }
            return;
        }

        // ── Evening commute home ──
        if (dayTime >= T_HOME_COMMUTE && dayTime < T_LEISURE) {
            if (cur == CitizenState.COMMUTING) return;
            if (cur == CitizenState.LEISURE) return;
            transitionTo(id, c, CitizenState.COMMUTING);
            BlockPos home = homeAssignments.get(id);
            if (home == null) home = bedAssignments.get(id);
            c.setCommuteTarget(home);
            return;
        }

        // ── Evening leisure ──
        if (dayTime >= T_LEISURE && dayTime < T_SLEEP) {
            if (cur == CitizenState.COMMUTING) return; // en route
            if (cur != CitizenState.LEISURE) {
                transitionTo(id, c, CitizenState.LEISURE);
            }
            return;
        }

        // ── Night: sleep ──
        if (dayTime >= T_SLEEP || dayTime < T_WAKE_UP) {
            if (cur == CitizenState.COMMUTING) return;
            if (cur != CitizenState.SLEEPING) {
                storeAndDespawn(id, c, CitizenState.SLEEPING);
            }
        }
    }

    // ──────────────────────── Store / despawn / respawn ────────────────────────

    /** Discard the entity and store its data for later respawn. */
    private void storeAndDespawn(UUID id, CitizenEntity c, CitizenState newState) {
        stored.put(id, new StoredCitizen(
                c.getCitizenName(), c.getProfession(), c.getMood(),
                workplaceAssignments.get(id), homeAssignments.get(id),
                bedAssignments.get(id), newState));
        active.remove(id);
        // Keep name in usedNames so it doesn't get reused
        c.discard();
        LOGGER.debug("[Citizen] {} stored as {} (active={}, stored={})",
                stored.get(id).name(), newState.getDisplayName(),
                active.size(), stored.size());
    }

    /** Transfer assignment maps from old UUID to new UUID after respawn. */
    private void transferAssignments(UUID oldId, UUID newId, StoredCitizen sc) {
        if (sc.workplace() != null) workplaceAssignments.put(newId, sc.workplace());
        if (sc.home() != null) homeAssignments.put(newId, sc.home());
        if (sc.bed() != null) bedAssignments.put(newId, sc.bed());
    }

    /** Clean up all maps for a removed citizen. */
    private void cleanup(UUID id, String name) {
        usedNames.remove(name);
        bedAssignments.remove(id);
        workplaceAssignments.remove(id);
        homeAssignments.remove(id);
    }

    // ──────────────────────── State transition ────────────────────────

    private void transitionTo(UUID id, CitizenEntity c, CitizenState newState) {
        CitizenState old = c.getCurrentState();
        c.applyState(newState);

        switch (newState) {
            case LEISURE -> {
                BlockPos home = homeAssignments.get(id);
                if (home == null) home = bedAssignments.get(id);
                c.setWanderAnchor(home);
                c.setWanderRadius(12);
                c.setPoiList(cachedPoiList);
            }
            case IDLE -> {
                BlockPos home = homeAssignments.get(id);
                if (home == null) home = bedAssignments.get(id);
                c.setWanderAnchor(home);
                c.setWanderRadius(10);
            }
            case COMMUTING -> c.setCommuteArrived(false);
        }
    }

    // ──────────────────────── Event-driven spawn ────────────────────────

    @SubscribeEvent
    public void onBuildingPlaced(BuildingPlacedEvent event) {
        if (lastLevel == null) return;
        BuildingApi api = getBuildingApi();
        if (api == null) return;
        BuildingData b = api.getBuilding(event.getBuildingId());
        if (b == null) return;
        LOGGER.info("[Citizen] BuildingPlaced: {} (cat={})", event.getBuildingTypeId(), b.getCategory());
        evaluateAndSpawn(lastLevel);
    }

    public void spawnInitial(ServerLevel level) {
        this.lastLevel = level;
        evaluateAndSpawn(level);
    }

    // ──────────────────────── Evaluate & spawn ────────────────────────

    private void evaluateAndSpawn(ServerLevel level) {
        BuildingApi api = getBuildingApi();
        if (api == null) return;

        List<BuildingData> all = api.getColonyBuildings(null);
        if (all.isEmpty()) { LOGGER.debug("[Citizen] no buildings"); return; }

        // Index
        Map<String, List<BuildingData>> byCat = new HashMap<>();
        for (BuildingData b : all) byCat.computeIfAbsent(b.getCategory(), k -> new ArrayList<>()).add(b);

        // Update POI cache: sample walkable ground within each building's boundary.
        // 3 samples per building gives variety for LEISURE city wandering.
        List<BlockPos> pois = new ArrayList<>();
        for (BuildingData b : all) {
            List<BlockPos> samples = api.sampleWalkableGround(b.getBuildingId(), 3);
            pois.addAll(samples);
        }
        if (pois.isEmpty()) {
            // Fallback: use building anchors
            pois = all.stream().map(BuildingData::getPosition).collect(Collectors.toList());
        }
        cachedPoiList = List.copyOf(pois);

        // Beds
        List<BlockPos> allBeds = new ArrayList<>();
        Map<UUID, BlockPos> resAnchors = new LinkedHashMap<>();
        for (BuildingData res : byCat.getOrDefault("residence", List.of())) {
            List<BlockPos> beds = api.findBeds(res.getBuildingId());
            allBeds.addAll(beds);
            resAnchors.put(res.getBuildingId(), res.getPosition());
            LOGGER.info("[Citizen] residence {} → {} beds", res.getBuildingTypeId(), beds.size());
        }

        int totalBeds = allBeds.size();
        int totalPopulation = active.size() + stored.size();
        int target = Math.min(totalBeds, HARD_CAP);
        int deficit = target - totalPopulation;

        LOGGER.info("[Citizen] beds={} target={} active={} stored={} deficit={}",
                totalBeds, target, active.size(), stored.size(), deficit);

        if (deficit <= 0) {
            LOGGER.info("[Citizen] population at capacity ({}/{}), nothing to spawn", totalPopulation, target);
            return;
        }

        List<Profession> allocation = buildAllocation(byCat, deficit);
        List<BlockPos> bedPool = new ArrayList<>(allBeds);
        List<BlockPos> resAnchorPool = new ArrayList<>(resAnchors.values());

        for (Profession profession : allocation) {
            BlockPos workplace = findWorkplace(byCat, profession);
            BlockPos home = !resAnchorPool.isEmpty()
                    ? resAnchorPool.get(random.nextInt(resAnchorPool.size())) : null;
            BlockPos spawnAnchor = workplace != null ? workplace
                    : home != null ? home : all.get(0).getPosition();

            BlockPos ground = findGround(level, spawnAnchor.offset(
                    random.nextInt(6) - 3, 0, random.nextInt(6) - 3));

            CitizenEntity c = spawnEntity(level, ground,
                    generateUniqueName(), profession, 40 + random.nextInt(41));
            if (c != null) {
                UUID cid = c.getUUID();
                if (workplace != null) workplaceAssignments.put(cid, workplace);
                if (home != null) homeAssignments.put(cid, home);

                if (!bedPool.isEmpty()) {
                    BlockPos bed = findUnassignedBed(bedPool, allBeds);
                    if (bed != null) bedAssignments.put(cid, bed);
                }

                // ── Always store immediately. Tick loop respawns at the right
                //     schedule window. Only COMMUTING/LEISURE are visible. ──
                long dayTime = level.getDayTime() % 24000;
                if (dayTime >= T_LEISURE && dayTime < T_SLEEP) {
                    // Right in the visible window → spawn as LEISURE
                    BlockPos anchor = home != null ? home : bedAssignments.get(cid);
                    c.applyState(CitizenState.LEISURE);
                    c.setWanderAnchor(anchor);
                    c.setWanderRadius(12);
                    c.setPoiList(cachedPoiList);
                } else if (dayTime >= T_SLEEP || dayTime < T_WAKE_UP) {
                    storeAndDespawn(cid, c, CitizenState.SLEEPING);
                } else if (workplace != null) {
                    storeAndDespawn(cid, c, CitizenState.WORKING);
                } else {
                    storeAndDespawn(cid, c, CitizenState.IDLE);
                }
            }

            if (active.size() + stored.size() >= target) break;
        }

        LOGGER.info("[Citizen] evaluate done: {}+{} stored / {} beds / {} workplaces / {} homes",
                active.size(), stored.size(), bedAssignments.size(),
                workplaceAssignments.size(), homeAssignments.size());
    }

    private List<Profession> buildAllocation(Map<String, List<BuildingData>> byCat, int count) {
        List<Profession> a = new ArrayList<>();
        for (var e : PROF_CATS.entrySet()) {
            if (a.size() >= count) break;
            for (String cat : e.getValue()) {
                if (byCat.containsKey(cat)) { a.add(e.getKey()); break; }
            }
        }
        while (a.size() < count) a.add(Profession.IDLER);
        return a;
    }

    @Nullable
    private BlockPos findWorkplace(Map<String, List<BuildingData>> byCat, Profession p) {
        List<String> cats = PROF_CATS.get(p);
        if (cats == null) return null;
        for (String cat : cats) {
            List<BuildingData> blds = byCat.get(cat);
            if (blds != null && !blds.isEmpty())
                return blds.get(random.nextInt(blds.size())).getPosition();
        }
        return null;
    }

    @Nullable
    private BlockPos findUnassignedBed(List<BlockPos> pool, List<BlockPos> all) {
        Set<BlockPos> assigned = new HashSet<>(bedAssignments.values());
        for (BlockPos b : all) if (!assigned.contains(b)) return b;
        return null;
    }

    // ──────────────────────── Entity factory ────────────────────────

    private CitizenEntity spawnEntity(ServerLevel level, BlockPos pos, String name,
                                       Profession profession, int mood) {
        CitizenEntity c = new CitizenEntity(
                com.wsteam.wandscape.Wandscape.CITIZEN.get(), level);
        c.setCitizenName(name);
        c.setProfession(profession);
        c.setMood(mood);
        c.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        level.addFreshEntity(c);
        active.put(c.getUUID(), c);
        return c;
    }

    /** Spawn from stored data (respawn). */
    private CitizenEntity spawnEntity(ServerLevel level, BlockPos pos, StoredCitizen sc) {
        return spawnEntity(level, pos, sc.name(), sc.profession(), sc.mood());
    }

    // ──────────────────────── Despawn ────────────────────────

    public void despawnCitizen(UUID id) {
        CitizenEntity c = active.remove(id);
        if (c != null) { cleanup(id, c.getCitizenName()); c.discard(); }
        stored.remove(id);
    }

    // ──────────────────────── Persistence guard ────────────────────────

    /**
     * Discard all spawned citizens BEFORE the world saves to disk.
     * Called from {@code ServerStoppingEvent} — guarantees no citizen
     * entities in the save.
     */
    public void onServerStopping() {
        LOGGER.info("[Citizen] ServerStopping — discarding {} active entities", active.size());
        for (CitizenEntity c : active.values()) c.discard();
        active.clear();
        // Don't clear stored/assignments — onServerStopped does full reset
    }

    /**
     * Safety sweep: kill any citizen entities still in the world.
     * Called on server start before spawning new ones. Handles the
     * edge case where a dirty shutdown left entities behind.
     */
    public static void killAllStrayCitizens(ServerLevel level) {
        List<CitizenEntity> strays = new ArrayList<>();
        for (var e : level.getAllEntities()) {
            if (e instanceof CitizenEntity) strays.add((CitizenEntity) e);
        }
        if (!strays.isEmpty()) {
            for (CitizenEntity c : strays) c.discard();
            LOGGER.warn("[Citizen] killed {} stray citizen entities from previous session", strays.size());
        }
    }

    public void onServerStopped() {
        LOGGER.info("[Citizen] stopping — discarding {} active + {} stored",
                active.size(), stored.size());
        for (CitizenEntity c : active.values()) c.discard();
        active.clear();
        stored.clear();
        usedNames.clear();
        bedAssignments.clear();
        workplaceAssignments.clear();
        homeAssignments.clear();
        cachedPoiList = List.of();
        registered = false;
        lastLevel = null;
    }

    // ──────────────────────── Queries ────────────────────────

    public int countActive() { return active.size(); }
    public int countStored() { return stored.size(); }
    public int countTotal() { return active.size() + stored.size(); }
    public Set<UUID> getActiveIds() { return Set.copyOf(active.keySet()); }
    public Collection<CitizenEntity> getActiveCitizens() { return List.copyOf(active.values()); }
    public Map<UUID, StoredCitizen> getStoredCitizens() { return Map.copyOf(stored); }

    // ──────────────────────── Debug: force state transition ────────────────────────

    /** Spawn a stored citizen or force-transition an active one for testing. */
    public String debugForceState(String filter, CitizenState targetState, ServerLevel level) {
        // ── Filter "all" ──
        if ("all".equalsIgnoreCase(filter)) {
            // Phase 1: spawn all stored → temp list (avoid CME when
            //          setupStateParams calls storeAndDespawn mid-iteration)
            List<CitizenEntity> justSpawned = new ArrayList<>();
            List<UUID> toRemove = new ArrayList<>();
            for (var e : stored.entrySet()) {
                UUID id = e.getKey();
                StoredCitizen sc = e.getValue();
                BlockPos spawnAt = sc.workplace() != null ? sc.workplace()
                        : sc.home() != null ? sc.home() : sc.bed();
                if (spawnAt == null) spawnAt = level.getSharedSpawnPos();
                BlockPos ground = findGround(level, spawnAt.offset(
                        random.nextInt(4) - 2, 0, random.nextInt(4) - 2));
                CitizenEntity c = spawnEntity(level, ground, sc);
                if (c != null) {
                    transferAssignments(id, c.getUUID(), sc);
                    toRemove.add(id);
                    justSpawned.add(c);
                }
            }
            for (UUID id : toRemove) stored.remove(id);
            int totalN = active.size() + stored.size();

            // Phase 2: apply target state. Snapshot IDs first because
            //          setupStateParams may call storeAndDespawn which modifies maps.
            List<CitizenEntity> allActive = new ArrayList<>(active.values());
            for (CitizenEntity c : justSpawned) {
                c.applyState(targetState);
                setupStateParams(c.getUUID(), c, targetState);
            }
            for (CitizenEntity c : allActive) {
                c.applyState(targetState);
                setupStateParams(c.getUUID(), c, targetState);
            }
            // Ensure any WORKING/SLEEPING stored during setupStateParams are also counted
            int storedFromTransition = stored.size();
            return "All " + totalN + " citizens → " + targetState.getDisplayName()
                    + " (" + storedFromTransition + " now stored)";
        }

        // ── Filter by name prefix ──
        // Snapshot active before iterating — setupStateParams may modify it.
        for (var e : List.copyOf(active.entrySet())) {
            CitizenEntity c = e.getValue();
            if (c.getCitizenName().startsWith(filter)) {
                c.applyState(targetState);
                setupStateParams(e.getKey(), c, targetState);
                return c.getCitizenName() + " → " + targetState.getDisplayName();
            }
        }
        // Check stored — snapshot keys to avoid CME on removal.
        UUID foundId = null;
        StoredCitizen foundSc = null;
        for (var e : stored.entrySet()) {
            StoredCitizen sc = e.getValue();
            if (sc.name().startsWith(filter)) { foundId = e.getKey(); foundSc = sc; break; }
        }
        if (foundSc != null) {
            BlockPos spawnAt = foundSc.workplace() != null ? foundSc.workplace()
                    : foundSc.home() != null ? foundSc.home() : foundSc.bed();
            if (spawnAt == null && lastLevel != null) spawnAt = lastLevel.getSharedSpawnPos();
            if (spawnAt != null && level != null) {
                BlockPos ground = findGround(level, spawnAt.offset(
                        random.nextInt(4) - 2, 0, random.nextInt(4) - 2));
                CitizenEntity c = spawnEntity(level, ground, foundSc);
                if (c != null) {
                    transferAssignments(foundId, c.getUUID(), foundSc);
                    stored.remove(foundId);
                    c.applyState(targetState);
                    setupStateParams(c.getUUID(), c, targetState);
                    return foundSc.name() + " (stored) → " + targetState.getDisplayName();
                }
            }
        }
        return "No citizen matching '" + filter + "'";
    }

    private void setupStateParams(UUID id, CitizenEntity c, CitizenState state) {
        switch (state) {
            case COMMUTING -> {
                c.setCommuteTarget(workplaceAssignments.get(id));
                c.setCommuteArrived(false);
            }
            case LEISURE -> {
                BlockPos home = homeAssignments.get(id);
                c.setWanderAnchor(home != null ? home : bedAssignments.get(id));
                c.setWanderRadius(12);
                c.setPoiList(cachedPoiList);
            }
            case IDLE -> {
                BlockPos home = homeAssignments.get(id);
                c.setWanderAnchor(home != null ? home : bedAssignments.get(id));
                c.setWanderRadius(10);
            }
            case WORKING -> {
                if (c.isAlive()) storeAndDespawn(id, c, CitizenState.WORKING);
            }
            case SLEEPING -> {
                if (c.isAlive()) storeAndDespawn(id, c, CitizenState.SLEEPING);
            }
        }
    }

    // ──────────────────────── Name gen ────────────────────────

    private String generateUniqueName() {
        for (int attempt = 0; attempt < 100; attempt++) {
            String c = SURNAMES[random.nextInt(SURNAMES.length)]
                    + GIVENS[random.nextInt(GIVENS.length)];
            if (!usedNames.contains(c)) { usedNames.add(c); return c; }
        }
        for (int i = 2; ; i++) {
            String c = SURNAMES[random.nextInt(SURNAMES.length)]
                    + GIVENS[random.nextInt(GIVENS.length)] + i;
            if (!usedNames.contains(c)) { usedNames.add(c); return c; }
        }
    }

    // ──────────────────────── Helpers ────────────────────────

    @Nullable
    private static BuildingApi getBuildingApi() {
        try { return WandscapeApis.getBuildingApi(); }
        catch (IllegalStateException e) { return null; }
    }

    private static BlockPos findGround(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos(
                pos.getX(), pos.getY(), pos.getZ());
        mp.setY(Math.min(level.getMaxBuildHeight(), 120));
        while (mp.getY() > level.getMinBuildHeight()) {
            if (!level.getBlockState(mp).isAir()
                    && level.getBlockState(mp.above()).isAir())
                return mp.above().immutable();
            mp.move(0, -1, 0);
        }
        return pos;
    }
}
