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
 * Only {@link CitizenState#COMMUTING} and {@link CitizenState#LEISURE}
 * have spawned entities. WORKING, SLEEPING, and IDLE are stored data.
 *
 * <h3>Probability-driven state machine</h3>
 * There is no fixed schedule. Every citizen evaluates independently
 * (≈60s interval, personal phase offset). A weighted roll against the
 * current time-of-day phase determines the target state. Visible slots
 * are capped by town size: maxVisible = max(2, min(total * 0.4, 8)).
 */
public class CitizenManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final CitizenManager INSTANCE = new CitizenManager();

    private CitizenManager() {}
    public static CitizenManager getInstance() { return INSTANCE; }

    // ──────────────────────── Visible cap ────────────────────────

    private static final int HARD_CAP = 15;

    // ──────────────────────── Phases & probability ────────────────────────

    private enum Phase {
        DAWN,     // 23500–1000  sunrise rush
        DAY,      // 1000–11000  workday
        DUSK,     // 11000–12500 heading home
        EVENING,  // 12500–14000 leisure
        NIGHT     // 14000–23500 sleep
    }

    // Probability per phase of each VISIBLE state (COMMUTING, LEISURE).
    // Sums < 1.0 leave probability mass for stored (WORKING/SLEEPING/IDLE).
    // The stored split (WORKING vs IDLE vs SLEEPING) is resolved separately.
    private static final double[] COMMUTE_PROB = { 0.50, 0.10, 0.35, 0.10, 0.05 };  // DAWN..NIGHT
    private static final double[] LEISURE_PROB = { 0.15, 0.10, 0.30, 0.70, 0.10 };  // DAWN..NIGHT

    /** Tick interval between state re-evaluations (1200 = 60s). */
    private static final long EVAL_INTERVAL_TICKS = 1200;

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

    // ──────────────────────── Runtime state ────────────────────────

    private final Map<UUID, CitizenEntity> active = new HashMap<>();
    private final Map<UUID, StoredCitizen> stored = new LinkedHashMap<>();
    private final Set<String> usedNames = new HashSet<>();
    private final Map<UUID, BlockPos> bedAssignments = new HashMap<>();
    private final Map<UUID, BlockPos> workplaceAssignments = new HashMap<>();
    private final Map<UUID, BlockPos> homeAssignments = new HashMap<>();
    /** UUID → next tick at which this citizen may re-evaluate state. */
    private final Map<UUID, Long> nextEvalTick = new HashMap<>();

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

    // ──────────────────────── Tick — probability-driven evaluation ────────────────────────

    public void tick(ServerLevel level) {
        this.lastLevel = level;
        long totalTicks = level.getDayTime();
        long dayTime = totalTicks % 24000;
        Phase phase = getPhase(dayTime);

        // ── 1. Evaluate all citizens (stored + active) ──
        int visibleOnHand = (int) active.values().stream()
                .filter(c -> c.getCurrentState() == CitizenState.COMMUTING
                        || c.getCurrentState() == CitizenState.LEISURE)
                .count();
        int maxVisible = computeVisibleCap();

        // Collect evaluation candidates (don't modify while viewing)
        List<UUID> candidateIds = new ArrayList<>();
        candidateIds.addAll(stored.keySet());
        candidateIds.addAll(active.keySet());
        Collections.shuffle(candidateIds, random); // fairness — no fixed order bias

        for (UUID id : candidateIds) {
            long curTick = totalTicks;
            if (curTick < nextEvalTick.getOrDefault(id, 0L)) continue; // locked

            CitizenState target = rollTargetState(id, phase);
            boolean targetVisible = (target == CitizenState.COMMUTING || target == CitizenState.LEISURE);
            boolean isActive = active.containsKey(id);

            if (targetVisible) {
                if (!isActive) {
                    // stored → spawn
                    StoredCitizen sc = stored.get(id);
                    if (sc == null) continue;
                    if (visibleOnHand >= maxVisible) continue; // cap full
                    BlockPos spawnAt = pickSpawnPos(sc, level);
                    BlockPos ground = findGround(level, spawnAt.offset(
                            random.nextInt(4) - 2, 0, random.nextInt(4) - 2));
                    CitizenEntity c = spawnEntity(level, ground, sc);
                    if (c != null) {
                        UUID newId = c.getUUID();
                        transferAssignments(id, newId, sc);
                        stored.remove(id);
                        applyStateWithParams(newId, c, target, sc.workplace(), sc.home());
                        visibleOnHand++;
                        nextEvalTick.put(newId, curTick + EVAL_INTERVAL_TICKS
                                + random.nextInt(400) - 200); // ±10s jitter
                        LOGGER.debug("[Citizen] {} stored→{} (visible {}/{})",
                                sc.name(), target.getDisplayName(), visibleOnHand, maxVisible);
                    }
                    continue;
                }
                // Already active — only change state if different
                CitizenEntity c = active.get(id);
                if (c.getCurrentState() == CitizenState.COMMUTING) continue;   // don't interrupt commute
                if (c.getCurrentState() != target) {
                    applyStateWithParams(id, c, target, workplaceAssignments.get(id), homeAssignments.get(id));
                }
                nextEvalTick.put(id, curTick + EVAL_INTERVAL_TICKS
                        + random.nextInt(400) - 200);
            } else {
                // target is stored state
                if (isActive) {
                    // active → store
                    CitizenEntity c = active.get(id);
                    if (c.getCurrentState() == CitizenState.COMMUTING) continue; // finish commute first
                    storeAndDespawn(id, c, target);
                    nextEvalTick.put(id, curTick + EVAL_INTERVAL_TICKS
                            + random.nextInt(400) - 200);
                    LOGGER.debug("[Citizen] {} active→stored({}) (visible {}/{})",
                            stored.get(id) != null ? stored.get(id).name() : "?",
                            target.getDisplayName(), visibleOnHand - 1, maxVisible);
                } else {
                    // Already stored — maybe update storedState
                    // (fine as-is; no entity to touch)
                    nextEvalTick.put(id, curTick + EVAL_INTERVAL_TICKS
                            + random.nextInt(400) - 200);
                }
            }
        }

        // ── 2. Active citizens: commute-arrived transitions ──
        for (var e : List.copyOf(active.entrySet())) {
            UUID id = e.getKey();
            CitizenEntity c = e.getValue();
            if (c.isRemoved() || !active.containsKey(id)) {
                cleanup(id, c.getCitizenName());
                nextEvalTick.remove(id);
                continue;
            }
            if (c.getCurrentState() == CitizenState.COMMUTING && c.isCommuteArrived()) {
                c.setCommuteArrived(false);
                // Arrived → store as appropriate state for this phase
                CitizenState dest;
                if (workplaceAssignments.containsKey(id)) {
                    dest = CitizenState.WORKING;
                } else if (phase == Phase.NIGHT) {
                    dest = CitizenState.SLEEPING;
                } else {
                    dest = CitizenState.IDLE;
                }
                storeAndDespawn(id, c, dest);
                LOGGER.debug("[Citizen] {} commute arrived → stored({})",
                        stored.get(id) != null ? stored.get(id).name() : "?", dest.getDisplayName());
            }
        }
    }

    // ── Phase detection ──

    private static Phase getPhase(long dayTime) {
        if (dayTime >= 23500 || dayTime < 1000) return Phase.DAWN;
        if (dayTime < 11000) return Phase.DAY;
        if (dayTime < 12500) return Phase.DUSK;
        if (dayTime < 14000) return Phase.EVENING;
        return Phase.NIGHT;
    }

    // ── Visible cap ──

    private int computeVisibleCap() {
        int total = active.size() + stored.size();
        if (total == 0) return 2;
        return Math.max(2, Math.min((int) (total * 0.4), 8));
    }

    // ── Roll target state ──

    private CitizenState rollTargetState(UUID id, Phase phase) {
        int p = phase.ordinal();
        double r = random.nextDouble();
        double cp = COMMUTE_PROB[p];
        double lp = LEISURE_PROB[p];

        if (r < cp) return CitizenState.COMMUTING;
        if (r < cp + lp) return CitizenState.LEISURE;

        // Stored roll: WORKING > IDLE > SLEEPING
        boolean hasWorkplace = workplaceAssignments.containsKey(id);
        StoredCitizen sc = stored.get(id);
        boolean hadWorkplace = sc != null && sc.workplace() != null;
        boolean worker = hasWorkplace || hadWorkplace;

        if (worker) {
            // Worker: during DAY high WORKING, NIGHT SLEEPING
            double s = random.nextDouble();
            if (phase == Phase.NIGHT || phase == Phase.DAWN) {
                return s < 0.85 ? CitizenState.SLEEPING : CitizenState.IDLE;
            }
            return s < 0.90 ? CitizenState.WORKING
                    : s < 0.95 ? CitizenState.IDLE : CitizenState.SLEEPING;
        }
        // IDLER: mostly IDLE, NIGHT SLEEPING
        double s = random.nextDouble();
        if (phase == Phase.NIGHT) {
            return s < 0.85 ? CitizenState.SLEEPING : CitizenState.IDLE;
        }
        return s < 0.80 ? CitizenState.IDLE : s < 0.95 ? CitizenState.SLEEPING : CitizenState.WORKING;
    }

    // ── Pick spawn position ──

    private BlockPos pickSpawnPos(StoredCitizen sc, ServerLevel level) {
        if (sc.workplace() != null) return sc.workplace();
        if (sc.home() != null) return sc.home();
        if (sc.bed() != null) return sc.bed();
        return level.getSharedSpawnPos();
    }

    // ── Apply state + set commute/wander params ──

    private void applyStateWithParams(UUID id, CitizenEntity c, CitizenState state,
                                      @Nullable BlockPos workplace, @Nullable BlockPos home) {
        c.applyState(state);
        switch (state) {
            case COMMUTING -> {
                if (workplace != null) c.setCommuteTarget(workplace);
                c.setCommuteArrived(false);
            }
            case LEISURE -> {
                BlockPos a = home != null ? home : bedAssignments.get(id);
                c.setWanderAnchor(a);
                c.setWanderRadius(12);
                c.setPoiList(cachedPoiList);
            }
            default -> {} // stored states don't need params
        }
    }

    // ──────────────────────── Store / despawn ────────────────────────

    private void storeAndDespawn(UUID id, CitizenEntity c, CitizenState newState) {
        stored.put(id, new StoredCitizen(
                c.getCitizenName(), c.getProfession(), c.getMood(),
                workplaceAssignments.get(id), homeAssignments.get(id),
                bedAssignments.get(id), newState));
        active.remove(id);
        c.discard();
    }

    private void transferAssignments(UUID oldId, UUID newId, StoredCitizen sc) {
        if (sc.workplace() != null) workplaceAssignments.put(newId, sc.workplace());
        if (sc.home() != null) homeAssignments.put(newId, sc.home());
        if (sc.bed() != null) bedAssignments.put(newId, sc.bed());
    }

    private void cleanup(UUID id, String name) {
        usedNames.remove(name);
        bedAssignments.remove(id);
        workplaceAssignments.remove(id);
        homeAssignments.remove(id);
        nextEvalTick.remove(id);
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

        Map<String, List<BuildingData>> byCat = new HashMap<>();
        for (BuildingData b : all) byCat.computeIfAbsent(b.getCategory(), k -> new ArrayList<>()).add(b);

        // POI cache
        List<BlockPos> pois = new ArrayList<>();
        for (BuildingData b : all) {
            List<BlockPos> samples = api.sampleWalkableGround(b.getBuildingId(), 3);
            pois.addAll(samples);
        }
        if (pois.isEmpty()) pois = all.stream().map(BuildingData::getPosition).collect(Collectors.toList());
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

        long curTick = level.getDayTime();
        Phase phase = getPhase(curTick % 24000);
        int maxVisible = computeVisibleCap();
        int visibleCount = (int) active.values().stream()
                .filter(c -> c.getCurrentState() == CitizenState.COMMUTING
                        || c.getCurrentState() == CitizenState.LEISURE)
                .count();

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

                // Roll initial state for this time of day
                CitizenState initialState = rollTargetState(cid, phase);
                boolean iv = (initialState == CitizenState.COMMUTING || initialState == CitizenState.LEISURE);
                if (iv && visibleCount < maxVisible) {
                    applyStateWithParams(cid, c, initialState, workplace, home);
                    visibleCount++;
                } else {
                    storeAndDespawn(cid, c,
                            iv ? CitizenState.IDLE : initialState);
                }
                nextEvalTick.put(cid, curTick + random.nextInt((int) EVAL_INTERVAL_TICKS));
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

    @Nullable private BlockPos findWorkplace(Map<String, List<BuildingData>> byCat, Profession p) {
        List<String> cats = PROF_CATS.get(p);
        if (cats == null) return null;
        for (String cat : cats) {
            List<BuildingData> blds = byCat.get(cat);
            if (blds != null && !blds.isEmpty())
                return blds.get(random.nextInt(blds.size())).getPosition();
        }
        return null;
    }

    @Nullable private BlockPos findUnassignedBed(List<BlockPos> pool, List<BlockPos> all) {
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

    public void onServerStopping() {
        LOGGER.info("[Citizen] ServerStopping — discarding {} active entities", active.size());
        for (CitizenEntity c : active.values()) c.discard();
        active.clear();
    }

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
        nextEvalTick.clear();
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

    // ──────────────────────── Debug ────────────────────────

    public String debugForceState(String filter, CitizenState targetState, ServerLevel level) {
        if ("all".equalsIgnoreCase(filter)) {
            List<CitizenEntity> justSpawned = new ArrayList<>();
            List<UUID> toRemove = new ArrayList<>();
            for (var e : stored.entrySet()) {
                UUID id = e.getKey();
                StoredCitizen sc = e.getValue();
                BlockPos spawnAt = pickSpawnPos(sc, level);
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

            List<CitizenEntity> allActive = new ArrayList<>(active.values());
            for (CitizenEntity c : justSpawned) {
                c.applyState(targetState);
                setupStateParams(c.getUUID(), c, targetState);
            }
            for (CitizenEntity c : allActive) {
                c.applyState(targetState);
                setupStateParams(c.getUUID(), c, targetState);
            }
            int storedN = stored.size();
            return "All " + totalN + " citizens → " + targetState.getDisplayName()
                    + " (" + storedN + " now stored)";
        }

        for (var e : List.copyOf(active.entrySet())) {
            CitizenEntity c = e.getValue();
            if (c.getCitizenName().startsWith(filter)) {
                c.applyState(targetState);
                setupStateParams(e.getKey(), c, targetState);
                return c.getCitizenName() + " → " + targetState.getDisplayName();
            }
        }
        UUID foundId = null; StoredCitizen foundSc = null;
        for (var e : stored.entrySet()) {
            StoredCitizen sc = e.getValue();
            if (sc.name().startsWith(filter)) { foundId = e.getKey(); foundSc = sc; break; }
        }
        if (foundSc != null) {
            BlockPos spawnAt = pickSpawnPos(foundSc, level);
            if (spawnAt == null) spawnAt = level.getSharedSpawnPos();
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
            case WORKING -> { if (c.isAlive()) storeAndDespawn(id, c, CitizenState.WORKING); }
            case SLEEPING -> { if (c.isAlive()) storeAndDespawn(id, c, CitizenState.SLEEPING); }
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

    @Nullable private static BuildingApi getBuildingApi() {
        try { return WandscapeApis.getBuildingApi(); }
        catch (IllegalStateException e) { return null; }
    }

    private static BlockPos findGround(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos(pos.getX(), pos.getY(), pos.getZ());
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
