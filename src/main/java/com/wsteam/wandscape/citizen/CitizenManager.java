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
 * Manages tourist entity lifecycle.
 *
 * <p>Spawns tourists based on colony building count. All tourists use
 * {@link com.wsteam.wandscape.tourist.internal.TouristMoveGoal} for unified
 * movement behaviour (visit buildings + explore POIs + wander).
 *
 * <p>There is no schedule-based despawn — entities persist until server stop.
 */
public class CitizenManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final CitizenManager INSTANCE = new CitizenManager();

    private CitizenManager() {}
    public static CitizenManager getInstance() { return INSTANCE; }

    private static final int HARD_CAP = 15;

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
    private final Set<String> usedNames = new HashSet<>();
    private final Map<UUID, BlockPos> bedAssignments = new HashMap<>();
    private final Map<UUID, BlockPos> homeAssignments = new HashMap<>();

    private List<BlockPos> cachedPoiList = List.of();

    @Nullable
    private UUID colonyId;

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

    /** Clean up dead entities from the active map. */
    public void tick(ServerLevel level) {
        this.lastLevel = level;
        for (var e : List.copyOf(active.entrySet())) {
            UUID id = e.getKey();
            TouristEntity t = e.getValue();
            if (t.isRemoved() || !t.isAlive()) {
                cleanup(id, t.getTouristName());
            }
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

        // Extract colonyId from the first building
        this.colonyId = all.get(0).getColonyId();

        // Cache POI positions from all buildings
        List<BlockPos> pois = new ArrayList<>();
        for (BuildingData b : all) {
            List<BlockPos> samples = api.sampleWalkableGround(b.getBuildingId(), 3);
            pois.addAll(samples);
        }
        if (pois.isEmpty()) pois = all.stream().map(BuildingData::getPosition).collect(Collectors.toList());
        cachedPoiList = List.copyOf(pois);

        // Count beds for population cap
        List<BlockPos> allBeds = new ArrayList<>();
        List<BlockPos> resAnchors = new ArrayList<>();
        for (BuildingData b : all) {
            if ("residence".equals(b.getCategory())) {
                List<BlockPos> beds = api.findBeds(b.getBuildingId());
                allBeds.addAll(beds);
                resAnchors.add(b.getPosition());
            }
        }

        int totalBeds = allBeds.size();
        int target = Math.min(totalBeds, HARD_CAP);
        if (target == 0) target = Math.min(all.size(), HARD_CAP); // fallback: 1 per building
        int deficit = target - active.size();

        LOGGER.info("[Citizen] beds={} target={} active={} deficit={}",
                totalBeds, target, active.size(), deficit);

        if (deficit <= 0) {
            LOGGER.info("[Citizen] population at capacity ({}/{}), nothing to spawn", active.size(), target);
            return;
        }

        // Spawn tourists near buildings
        List<BlockPos> spawnPool = new ArrayList<>();
        for (BuildingData b : all) spawnPool.add(b.getPosition());

        for (int i = 0; i < deficit && active.size() < target; i++) {
            BlockPos spawnAnchor = spawnPool.get(random.nextInt(spawnPool.size()));
            BlockPos ground = findGround(level, spawnAnchor.offset(
                    random.nextInt(6) - 3, 0, random.nextInt(6) - 3));

            TouristEntity t = spawnEntity(level, ground, generateUniqueName());
            if (t != null) {
                t.setColonyId(colonyId);

                // Assign a home anchor (residence building position) for wander radius
                if (!resAnchors.isEmpty()) {
                    BlockPos home = resAnchors.get(random.nextInt(resAnchors.size()));
                    homeAssignments.put(t.getUUID(), home);
                    t.setWanderAnchor(home);
                } else {
                    t.setWanderAnchor(ground);
                }
                t.setWanderRadius(12);
                t.setPoiList(cachedPoiList);
                t.applyState(CitizenState.WANDERING);

                // Assign a bed if available
                if (!allBeds.isEmpty()) {
                    Set<BlockPos> assigned = new HashSet<>(bedAssignments.values());
                    for (BlockPos bed : allBeds) {
                        if (!assigned.contains(bed)) {
                            bedAssignments.put(t.getUUID(), bed);
                            break;
                        }
                    }
                }
            }

            if (active.size() >= target) break;
        }

        LOGGER.info("[Citizen] evaluate done: {} active / {} beds / {} homes / {} pois",
                active.size(), bedAssignments.size(), homeAssignments.size(), cachedPoiList.size());
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

    // ──────────────────────── Despawn ────────────────────────

    public void despawnCitizen(UUID id) {
        TouristEntity t = active.remove(id);
        if (t != null) { cleanup(id, t.getTouristName()); t.discard(); }
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
        LOGGER.info("[Citizen] stopping — discarding {} active", active.size());
        for (TouristEntity t : active.values()) t.discard();
        active.clear();
        usedNames.clear();
        bedAssignments.clear();
        homeAssignments.clear();
        cachedPoiList = List.of();
        colonyId = null;
        registered = false;
        lastLevel = null;
    }

    // ──────────────────────── Queries ────────────────────────

    public int countActive() { return active.size(); }
    public Set<UUID> getActiveIds() { return Set.copyOf(active.keySet()); }
    public Collection<TouristEntity> getActiveCitizens() { return List.copyOf(active.values()); }

    // ──────────────────────── Debug ────────────────────────

    public String debugForceState(String filter, CitizenState targetState, ServerLevel level) {
        if ("all".equalsIgnoreCase(filter)) {
            List<TouristEntity> allActive = new ArrayList<>(active.values());
            for (TouristEntity t : allActive) {
                t.applyState(targetState);
                setupStateParams(t, targetState);
            }
            return "All " + allActive.size() + " tourists → " + targetState.getDisplayName();
        }

        for (TouristEntity t : active.values()) {
            if (t.getTouristName().startsWith(filter)) {
                t.applyState(targetState);
                setupStateParams(t, targetState);
                return t.getTouristName() + " → " + targetState.getDisplayName();
            }
        }
        return "No tourist matching '" + filter + "'";
    }

    private void setupStateParams(TouristEntity t, CitizenState state) {
        switch (state) {
            case VISITING, EXPLORING, WANDERING, IDLE -> {
                BlockPos anchor = homeAssignments.get(t.getUUID());
                if (anchor == null) anchor = t.blockPosition();
                t.setWanderAnchor(anchor);
                t.setWanderRadius(state == CitizenState.IDLE ? 10 : 12);
                t.setPoiList(cachedPoiList);
            }
            case SLEEPING -> { /* no-op: pose handled by applyState */ }
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

    private void cleanup(UUID id, String name) {
        usedNames.remove(name);
        bedAssignments.remove(id);
        homeAssignments.remove(id);
        active.remove(id);
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
