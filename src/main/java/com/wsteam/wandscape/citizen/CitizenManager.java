package com.wsteam.wandscape.citizen;

import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.event.BuildingPlacedEvent;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.tourist.entity.TouristEntity;

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
 * Singleton manager for tourist NPC lifecycle.
 *
 * <h3>Visibility rule</h3>
 * Only {@link CitizenState#COMMUTING} and {@link CitizenState#LEISURE}
 * have spawned entities. WORKING, SLEEPING, and IDLE are stored data.
 *
 * <h3>Probability-driven state machine</h3>
 * There is no fixed schedule. Every tourist evaluates independently
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
        DAWN,     // 23500-1000  sunrise rush
        DAY,      // 1000-11000  workday
        DUSK,     // 11000-12500 heading home
        EVENING,  // 12500-14000 leisure
        NIGHT     // 14000-23500 sleep
    }

    private static final double[] COMMUTE_PROB = { 0.50, 0.10, 0.35, 0.10, 0.05 };
    private static final double[] LEISURE_PROB = { 0.15, 0.10, 0.30, 0.70, 0.10 };

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

    private final Map<UUID, TouristEntity> active = new HashMap<>();
    private final Map<UUID, StoredCitizen> stored = new LinkedHashMap<>();
    private final Set<String> usedNames = new HashSet<>();
    private final Map<UUID, BlockPos> bedAssignments = new HashMap<>();
    private final Map<UUID, BlockPos> workplaceAssignments = new HashMap<>();
    private final Map<UUID, BlockPos> homeAssignments = new HashMap<>();
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

    // ──────────────────────── Tick ────────────────────────

    public void tick(ServerLevel level) {
        this.lastLevel = level;
        long totalTicks = level.getDayTime();
        long dayTime = totalTicks % 24000;
        Phase phase = getPhase(dayTime);

        int visibleOnHand = (int) active.values().stream()
                .filter(c -> c.getCurrentState() == CitizenState.COMMUTING
                        || c.getCurrentState() == CitizenState.LEISURE)
                .count();
        int maxVisible = computeVisibleCap();

        List<UUID> candidateIds = new ArrayList<>();
        candidateIds.addAll(stored.keySet());
        candidateIds.addAll(active.keySet());
        Collections.shuffle(candidateIds, random);

        for (UUID id : candidateIds) {
            long curTick = totalTicks;
            if (curTick < nextEvalTick.getOrDefault(id, 0L)) continue;

            CitizenState target = rollTargetState(id, phase);
            boolean targetVisible = (target == CitizenState.COMMUTING || target == CitizenState.LEISURE);
            boolean isActive = active.containsKey(id);

            if (targetVisible) {
                if (!isActive) {
                    StoredCitizen sc = stored.get(id);
                    if (sc == null) continue;
                    if (visibleOnHand >= maxVisible) continue;
                    BlockPos spawnAt = pickSpawnPos(sc, level);
                    BlockPos ground = findGround(level, spawnAt.offset(
                            random.nextInt(4) - 2, 0, random.nextInt(4) - 2));
                    TouristEntity t = spawnEntity(level, ground, sc);
                    if (t != null) {
                        UUID newId = t.getUUID();
                        transferAssignments(id, newId, sc);
                        stored.remove(id);
                        applyStateWithParams(newId, t, target, sc.workplace(), sc.home());
                        visibleOnHand++;
                        nextEvalTick.put(newId, curTick + EVAL_INTERVAL_TICKS
                                + random.nextInt(400) - 200);
                        LOGGER.debug("[Citizen] {} stored→{} (visible {}/{})",
                                sc.name(), target.getDisplayName(), visibleOnHand, maxVisible);
                    }
                    continue;
                }
                TouristEntity t = active.get(id);
                if (t.getCurrentState() == CitizenState.COMMUTING) continue;
                if (t.getCurrentState() != target) {
                    applyStateWithParams(id, t, target, workplaceAssignments.get(id), homeAssignments.get(id));
                }
                nextEvalTick.put(id, curTick + EVAL_INTERVAL_TICKS
                        + random.nextInt(400) - 200);
            } else {
                if (isActive) {
                    TouristEntity t = active.get(id);
                    if (t.getCurrentState() == CitizenState.COMMUTING) continue;
                    storeAndDespawn(id, t, target);
                    nextEvalTick.put(id, curTick + EVAL_INTERVAL_TICKS
                            + random.nextInt(400) - 200);
                } else {
                    nextEvalTick.put(id, curTick + EVAL_INTERVAL_TICKS
                            + random.nextInt(400) - 200);
                }
            }
        }

        // Commute-arrived transitions
        for (var e : List.copyOf(active.entrySet())) {
            UUID id = e.getKey();
            TouristEntity t = e.getValue();
            if (t.isRemoved() || !active.containsKey(id)) {
                cleanup(id, t.getTouristName());
                nextEvalTick.remove(id);
                continue;
            }
            if (t.getCurrentState() == CitizenState.COMMUTING && t.isCommuteArrived()) {
                t.setCommuteArrived(false);
                CitizenState dest;
                if (workplaceAssignments.containsKey(id)) {
                    dest = CitizenState.WORKING;
                } else if (phase == Phase.NIGHT) {
                    dest = CitizenState.SLEEPING;
                } else {
                    dest = CitizenState.IDLE;
                }
                storeAndDespawn(id, t, dest);
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

        boolean hasWorkplace = workplaceAssignments.containsKey(id);
        StoredCitizen sc = stored.get(id);
        boolean hadWorkplace = sc != null && sc.workplace() != null;
        boolean worker = hasWorkplace || hadWorkplace;

        if (worker) {
            double s = random.nextDouble();
            if (phase == Phase.NIGHT || phase == Phase.DAWN) {
                return s < 0.85 ? CitizenState.SLEEPING : CitizenState.IDLE;
            }
            return s < 0.90 ? CitizenState.WORKING
                    : s < 0.95 ? CitizenState.IDLE : CitizenState.SLEEPING;
        }
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

    // ── Apply state ──

    private void applyStateWithParams(UUID id, TouristEntity t, CitizenState state,
                                      @Nullable BlockPos workplace, @Nullable BlockPos home) {
        t.applyState(state);
        switch (state) {
            case COMMUTING -> {
                if (workplace != null) t.setCommuteTarget(workplace);
                t.setCommuteArrived(false);
            }
            case LEISURE -> {
                BlockPos a = home != null ? home : bedAssignments.get(id);
                t.setWanderAnchor(a);
                t.setWanderRadius(12);
                t.setPoiList(cachedPoiList);
            }
            default -> {}
        }
    }

    // ──────────────────────── Store / despawn ────────────────────────

    private void storeAndDespawn(UUID id, TouristEntity t, CitizenState newState) {
        stored.put(id, new StoredCitizen(
                t.getTouristName(),
                workplaceAssignments.get(id), homeAssignments.get(id),
                bedAssignments.get(id), newState));
        active.remove(id);
        t.discard();
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

        List<BlockPos> pois = new ArrayList<>();
        for (BuildingData b : all) {
            List<BlockPos> samples = api.sampleWalkableGround(b.getBuildingId(), 3);
            pois.addAll(samples);
        }
        if (pois.isEmpty()) pois = all.stream().map(BuildingData::getPosition).collect(Collectors.toList());
        cachedPoiList = List.copyOf(pois);

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

            TouristEntity t = spawnEntity(level, ground, generateUniqueName());
            if (t != null) {
                UUID cid = t.getUUID();
                if (workplace != null) workplaceAssignments.put(cid, workplace);
                if (home != null) homeAssignments.put(cid, home);
                if (!bedPool.isEmpty()) {
                    BlockPos bed = findUnassignedBed(bedPool, allBeds);
                    if (bed != null) bedAssignments.put(cid, bed);
                }

                CitizenState initialState = rollTargetState(cid, phase);
                boolean iv = (initialState == CitizenState.COMMUTING || initialState == CitizenState.LEISURE);
                if (iv && visibleCount < maxVisible) {
                    applyStateWithParams(cid, t, initialState, workplace, home);
                    visibleCount++;
                } else {
                    storeAndDespawn(cid, t,
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

    private TouristEntity spawnEntity(ServerLevel level, BlockPos pos, String name) {
        TouristEntity t = new TouristEntity(
                com.wsteam.wandscape.Wandscape.TOURIST.get(), level);
        t.setTouristName(name);
        t.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        level.addFreshEntity(t);
        active.put(t.getUUID(), t);
        return t;
    }

    private TouristEntity spawnEntity(ServerLevel level, BlockPos pos, StoredCitizen sc) {
        return spawnEntity(level, pos, sc.name());
    }

    // ──────────────────────── Despawn ────────────────────────

    public void despawnCitizen(UUID id) {
        TouristEntity t = active.remove(id);
        if (t != null) { cleanup(id, t.getTouristName()); t.discard(); }
        stored.remove(id);
    }

    // ──────────────────────── Persistence guard ────────────────────────

    public void onServerStopping() {
        LOGGER.info("[Citizen] ServerStopping — discarding {} active entities", active.size());
        for (TouristEntity t : active.values()) t.discard();
        active.clear();
    }

    public static void killAllStrayCitizens(ServerLevel level) {
        List<TouristEntity> strays = new ArrayList<>();
        for (var e : level.getAllEntities()) {
            if (e instanceof TouristEntity) strays.add((TouristEntity) e);
        }
        if (!strays.isEmpty()) {
            for (TouristEntity t : strays) t.discard();
            LOGGER.warn("[Citizen] killed {} stray tourist entities from previous session", strays.size());
        }
    }

    public void onServerStopped() {
        LOGGER.info("[Citizen] stopping — discarding {} active + {} stored",
                active.size(), stored.size());
        for (TouristEntity t : active.values()) t.discard();
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
    public Collection<TouristEntity> getActiveCitizens() { return List.copyOf(active.values()); }
    public Map<UUID, StoredCitizen> getStoredCitizens() { return Map.copyOf(stored); }

    // ──────────────────────── Debug ────────────────────────

    public String debugForceState(String filter, CitizenState targetState, ServerLevel level) {
        if ("all".equalsIgnoreCase(filter)) {
            List<TouristEntity> justSpawned = new ArrayList<>();
            List<UUID> toRemove = new ArrayList<>();
            for (var e : stored.entrySet()) {
                UUID id = e.getKey();
                StoredCitizen sc = e.getValue();
                BlockPos spawnAt = pickSpawnPos(sc, level);
                BlockPos ground = findGround(level, spawnAt.offset(
                        random.nextInt(4) - 2, 0, random.nextInt(4) - 2));
                TouristEntity t = spawnEntity(level, ground, sc);
                if (t != null) {
                    transferAssignments(id, t.getUUID(), sc);
                    toRemove.add(id);
                    justSpawned.add(t);
                }
            }
            for (UUID id : toRemove) stored.remove(id);
            int totalN = active.size() + stored.size();

            List<TouristEntity> allActive = new ArrayList<>(active.values());
            for (TouristEntity t : justSpawned) {
                t.applyState(targetState);
                setupStateParams(t.getUUID(), t, targetState);
            }
            for (TouristEntity t : allActive) {
                t.applyState(targetState);
                setupStateParams(t.getUUID(), t, targetState);
            }
            int storedN = stored.size();
            return "All " + totalN + " tourists → " + targetState.getDisplayName()
                    + " (" + storedN + " now stored)";
        }

        for (var e : List.copyOf(active.entrySet())) {
            TouristEntity t = e.getValue();
            if (t.getTouristName().startsWith(filter)) {
                t.applyState(targetState);
                setupStateParams(e.getKey(), t, targetState);
                return t.getTouristName() + " → " + targetState.getDisplayName();
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
            TouristEntity t = spawnEntity(level, ground, foundSc);
            if (t != null) {
                transferAssignments(foundId, t.getUUID(), foundSc);
                stored.remove(foundId);
                t.applyState(targetState);
                setupStateParams(t.getUUID(), t, targetState);
                return foundSc.name() + " (stored) → " + targetState.getDisplayName();
            }
        }
        return "No tourist matching '" + filter + "'";
    }

    private void setupStateParams(UUID id, TouristEntity t, CitizenState state) {
        switch (state) {
            case COMMUTING -> {
                t.setCommuteTarget(workplaceAssignments.get(id));
                t.setCommuteArrived(false);
            }
            case LEISURE -> {
                BlockPos home = homeAssignments.get(id);
                t.setWanderAnchor(home != null ? home : bedAssignments.get(id));
                t.setWanderRadius(12);
                t.setPoiList(cachedPoiList);
            }
            case IDLE -> {
                BlockPos home = homeAssignments.get(id);
                t.setWanderAnchor(home != null ? home : bedAssignments.get(id));
                t.setWanderRadius(10);
            }
            case WORKING -> { if (t.isAlive()) storeAndDespawn(id, t, CitizenState.WORKING); }
            case SLEEPING -> { if (t.isAlive()) storeAndDespawn(id, t, CitizenState.SLEEPING); }
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
