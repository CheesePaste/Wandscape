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
 * <p>Subscribes to {@link BuildingPlacedEvent} via NeoForge to reactively
 * spawn citizens when new buildings are completed. At server start (before
 * any buildings exist), {@link #spawnInitial} is called once — it scans
 * pre-existing buildings restored from SavedData.
 *
 * <p>Population = total bed count in all "residence" buildings, capped at
 * {@link #HARD_CAP}. Each profession maps to a building category (see
 * {@link #PROFESSION_CATEGORIES}).
 */
public class CitizenManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final CitizenManager INSTANCE = new CitizenManager();

    private CitizenManager() {}

    public static CitizenManager getInstance() {
        return INSTANCE;
    }

    // ──────────────────────── Configuration ────────────────────────

    /** Hard cap regardless of bed count (safety). */
    private static final int HARD_CAP = 15;

    // ──────────────────────── Profession → building category ────────────────────────

    private static final Map<Profession, List<String>> PROFESSION_CATEGORIES = Map.of(
            Profession.FARMER,   List.of("farm"),
            Profession.MERCHANT, List.of("market", "storage"),
            Profession.SCHOLAR,  List.of("library", "academy"),
            Profession.ARTISAN,  List.of("workshop", "production", "crafting_station"),
            Profession.GUARD,    List.of("garrison", "wall")
            // IDLER: remaining slots (no building category)
    );

    // ──────────────────────── Name pool ────────────────────────

    private static final String[] SURNAMES = {
            "李", "王", "张", "刘", "陈", "杨", "赵", "黄", "周", "吴",
            "徐", "孙", "马", "胡", "朱", "郭", "何", "罗", "高", "林",
            "郑", "梁", "谢", "宋", "唐", "许", "韩", "冯", "邓", "曹",
            "彭", "曾", "萧", "田", "董", "潘", "袁", "蔡", "蒋", "余",
            "于", "杜", "叶", "程", "苏", "魏", "吕", "丁", "任", "沈"
    };

    private static final String[] GIVENS = {
            "明", "华", "文", "伟", "芳", "秀英", "丽", "强", "勇", "静",
            "慧", "敏", "俊", "杰", "兰", "玲", "超", "平", "刚", "涛",
            "斌", "霞", "红", "建国", "海燕", "宁", "磊", "洋", "辉", "鑫",
            "怡", "珊", "君", "佳", "晨", "宇", "涵", "浩", "博", "瑞",
            "思远", "晓", "雨", "梦", "毅", "恒", "淑珍", "志强", "雪", "云"
    };

    // ──────────────────────── Runtime state ────────────────────────

    private final Map<UUID, CitizenEntity> activeCitizens = new HashMap<>();
    private final Set<String> usedNames = new HashSet<>();

    /** citizenId → assigned bed world position */
    private final Map<UUID, BlockPos> bedAssignments = new HashMap<>();

    private final Random random = new Random();
    private boolean registered = false;
    private ServerLevel lastLevel = null;

    // ──────────────────────── NeoForge registration ────────────────────────

    /**
     * Register this singleton on the NeoForge event bus for
     * {@link BuildingPlacedEvent}. Idempotent.
     */
    public void register() {
        if (registered) return;
        NeoForge.EVENT_BUS.register(this);
        registered = true;
        LOGGER.info("[Citizen] registered on NeoForge EVENT_BUS");
    }

    // ──────────────────────── Tick ────────────────────────

    /**
     * Called every server tick. Cleans up dead citizens, releases their beds.
     * (Future phases: drives schedule transitions.)
     */
    public void tick(ServerLevel level) {
        this.lastLevel = level;
        for (Iterator<Map.Entry<UUID, CitizenEntity>> it = activeCitizens.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, CitizenEntity> entry = it.next();
            CitizenEntity citizen = entry.getValue();
            if (citizen.isRemoved()) {
                UUID id = entry.getKey();
                it.remove();
                usedNames.remove(citizen.getCitizenName());
                bedAssignments.remove(id);
                LOGGER.debug("[Citizen] {} removed, bed released", citizen.getCitizenName());
            }
        }
    }

    // ──────────────────────── Event-driven spawn ────────────────────────

    /**
     * Called when any building completes construction.
     * Re-evaluates beds + profession allocation and spawns any missing citizens.
     */
    @SubscribeEvent
    public void onBuildingPlaced(BuildingPlacedEvent event) {
        if (lastLevel == null) {
            LOGGER.debug("[Citizen] BuildingPlaced({}) — no server level yet, deferring", event.getBuildingTypeId());
            return;
        }
        BuildingApi api = getBuildingApi();
        if (api == null) return;

        BuildingData building = api.getBuilding(event.getBuildingId());
        if (building == null) {
            LOGGER.debug("[Citizen] BuildingPlaced({}) — building not found in registry yet", event.getBuildingTypeId());
            return;
        }

        String category = building.getCategory();
        LOGGER.info("[Citizen] BuildingPlaced: {} (category={}, colony={})",
                event.getBuildingTypeId(), category,
                event.getColonyId() != null ? event.getColonyId().toString().substring(0, 8) : "null");

        // Re-evaluate — only spawns up to current bed count, never overspawns
        evaluateAndSpawn(lastLevel);
    }

    // ──────────────────────── Scan + spawn logic (shared) ────────────────────────

    /**
     * One-shot scan of all buildings in the colony. Called at server start
     * for buildings that survived a restart. After this, new buildings are
     * handled reactively by {@link #onBuildingPlaced}.
     */
    public void spawnInitial(ServerLevel level) {
        this.lastLevel = level;
        BuildingApi api = getBuildingApi();
        if (api == null) {
            LOGGER.warn("[Citizen] BuildingApi not available — citizen spawn deferred");
            return;
        }
        evaluateAndSpawn(level);
    }

    /**
     * Scan all buildings, compute bed cap + profession allocation,
     * and spawn any citizens below the current cap. Idempotent —
     * only spawns the difference between current count and target.
     */
    private void evaluateAndSpawn(ServerLevel level) {
        BuildingApi api = getBuildingApi();
        if (api == null) return;

        List<BuildingData> allBuildings = api.getColonyBuildings(null);
        if (allBuildings.isEmpty()) {
            LOGGER.debug("[Citizen] no buildings in colony — nothing to evaluate");
            return;
        }

        // ── 1. Index by category ──
        Map<String, List<BuildingData>> byCategory = new HashMap<>();
        for (BuildingData b : allBuildings) {
            byCategory.computeIfAbsent(b.getCategory(), k -> new ArrayList<>()).add(b);
        }

        // ── 2. Scan all residences for beds ──
        List<BlockPos> allBeds = new ArrayList<>();
        List<BuildingData> residences = byCategory.getOrDefault("residence", List.of());
        for (BuildingData res : residences) {
            List<BlockPos> beds = api.findBeds(res.getBuildingId());
            allBeds.addAll(beds);
            LOGGER.info("[Citizen] residence '{}' → {} beds",
                    res.getBuildingTypeId(), beds.size());
        }

        int totalBeds = allBeds.size();
        int targetCount = Math.min(totalBeds, HARD_CAP);
        int currentCount = activeCitizens.size();
        int deficit = targetCount - currentCount;

        LOGGER.info("[Citizen] beds={} target={} active={} deficit={}",
                totalBeds, targetCount, currentCount, deficit);

        if (deficit <= 0) {
            LOGGER.info("[Citizen] population at capacity ({}/{}), nothing to spawn", currentCount, targetCount);
            return;
        }

        // ── 3. Profession allocation for the deficit ──
        List<Profession> allocation = buildAllocation(byCategory, deficit, residences);

        LOGGER.info("[Citizen] spawning {} new citizens: {}", deficit,
                allocation.stream().map(Profession::getDisplayName).collect(Collectors.joining(", ")));

        // ── 4. Spawn each new citizen ──
        int spawned = 0;
        int usedBeds = (int) bedAssignments.values().stream().filter(p -> !p.equals(BlockPos.ZERO)).count();
        for (Profession profession : allocation) {
            // Pick spawn position near a building of matching category
            BlockPos anchor = pickSpawnAnchor(byCategory, profession, residences, allBuildings);
            BlockPos ground = findGround(level, anchor.offset(
                    random.nextInt(6) - 3, 0, random.nextInt(6) - 3));

            CitizenEntity citizen = spawnCitizen(level, ground, profession);
            if (citizen != null) {
                // Assign a bed
                if (!allBeds.isEmpty()) {
                    // Prefer unassigned beds
                    BlockPos bed = findUnassignedBed(allBeds);
                    if (bed != null) {
                        bedAssignments.put(citizen.getUUID(), bed);
                    }
                }
                spawned++;
            }

            if (spawned >= deficit) break;
        }

        LOGGER.info("[Citizen] evaluate complete: {} spawned, now {}/{} citizens, {} beds assigned",
                spawned, activeCitizens.size(), targetCount, bedAssignments.size());
    }

    /** Build a profession allocation list for {@code count} new citizens. */
    private List<Profession> buildAllocation(Map<String, List<BuildingData>> byCategory,
                                             int count, List<BuildingData> residences) {
        List<Profession> allocation = new ArrayList<>();

        // Prioritize non-IDLER professions based on building presence
        for (Map.Entry<Profession, List<String>> e : PROFESSION_CATEGORIES.entrySet()) {
            if (allocation.size() >= count) break;
            for (String cat : e.getValue()) {
                List<BuildingData> buildings = byCategory.get(cat);
                if (buildings != null && !buildings.isEmpty()) {
                    allocation.add(e.getKey());
                    break;
                }
            }
        }

        // Fill remaining with IDLERs
        while (allocation.size() < count) {
            allocation.add(Profession.IDLER);
        }

        return allocation;
    }

    /** Pick a plausible spawn anchor for a given profession. */
    private BlockPos pickSpawnAnchor(Map<String, List<BuildingData>> byCategory,
                                     Profession profession,
                                     List<BuildingData> residences,
                                     List<BuildingData> allBuildings) {
        // Non-IDLER: use the profession's building
        List<String> cats = PROFESSION_CATEGORIES.get(profession);
        if (cats != null) {
            for (String cat : cats) {
                List<BuildingData> buildings = byCategory.get(cat);
                if (buildings != null && !buildings.isEmpty()) {
                    return buildings.get(random.nextInt(buildings.size())).getPosition();
                }
            }
        }
        // IDLER or fallback: prefer residence, then any building
        if (!residences.isEmpty()) {
            return residences.get(random.nextInt(residences.size())).getPosition();
        }
        return allBuildings.get(random.nextInt(allBuildings.size())).getPosition();
    }

    /** Find a bed position that is not already assigned. */
    @Nullable
    private BlockPos findUnassignedBed(List<BlockPos> allBeds) {
        Set<BlockPos> assigned = new HashSet<>(bedAssignments.values());
        // Shuffle to avoid concentrating beds in one spot
        List<BlockPos> free = new ArrayList<>();
        for (BlockPos bed : allBeds) {
            if (!assigned.contains(bed)) free.add(bed);
        }
        if (free.isEmpty()) return null;
        return free.get(random.nextInt(free.size()));
    }

    // ──────────────────────── Spawn single citizen ────────────────────────

    /**
     * Spawn a single citizen with a specific profession at the given position.
     *
     * @return the new entity, or null if the hard cap is reached
     */
    public CitizenEntity spawnCitizen(ServerLevel level, BlockPos pos, Profession profession) {
        if (activeCitizens.size() >= HARD_CAP) {
            LOGGER.debug("[Citizen] spawn skipped — hard cap {} reached", HARD_CAP);
            return null;
        }

        String name = generateUniqueName();

        CitizenEntity citizen = new CitizenEntity(
                com.wsteam.wandscape.Wandscape.CITIZEN.get(),
                level);
        citizen.setCitizenName(name);
        citizen.setProfession(profession);
        citizen.setMood(40 + random.nextInt(41)); // 40–80
        citizen.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);

        level.addFreshEntity(citizen);
        activeCitizens.put(citizen.getUUID(), citizen);

        LOGGER.info("[Citizen] spawned {} ({}), total={}",
                name, profession.getDisplayName(), activeCitizens.size());
        return citizen;
    }

    // ──────────────────────── Despawn ────────────────────────

    public void despawnCitizen(UUID id) {
        CitizenEntity citizen = activeCitizens.remove(id);
        if (citizen != null) {
            usedNames.remove(citizen.getCitizenName());
            bedAssignments.remove(id);
            citizen.discard();
            LOGGER.info("[Citizen] despawned {}", citizen.getCitizenName());
        }
    }

    /** Discard all citizens. Called on server stop. */
    public void onServerStopped() {
        LOGGER.info("[Citizen] stopping — discarding {} citizens", activeCitizens.size());
        for (CitizenEntity citizen : activeCitizens.values()) {
            citizen.discard();
        }
        activeCitizens.clear();
        usedNames.clear();
        bedAssignments.clear();
        registered = false;
        lastLevel = null;
    }

    // ──────────────────────── Queries ────────────────────────

    public int countActive() {
        return activeCitizens.size();
    }

    public Set<UUID> getActiveIds() {
        return Set.copyOf(activeCitizens.keySet());
    }

    @Nullable
    public BlockPos getBedFor(UUID citizenId) {
        return bedAssignments.get(citizenId);
    }

    // ──────────────────────── Name generation ────────────────────────

    private String generateUniqueName() {
        for (int attempt = 0; attempt < 100; attempt++) {
            String surname = SURNAMES[random.nextInt(SURNAMES.length)];
            String given = GIVENS[random.nextInt(GIVENS.length)];
            String candidate = surname + given;
            if (!usedNames.contains(candidate)) {
                usedNames.add(candidate);
                return candidate;
            }
        }
        for (int i = 2; ; i++) {
            String surname = SURNAMES[random.nextInt(SURNAMES.length)];
            String given = GIVENS[random.nextInt(GIVENS.length)];
            String candidate = surname + given + i;
            if (!usedNames.contains(candidate)) {
                usedNames.add(candidate);
                return candidate;
            }
        }
    }

    // ──────────────────────── Helpers ────────────────────────

    @Nullable
    private static BuildingApi getBuildingApi() {
        try {
            return WandscapeApis.getBuildingApi();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static BlockPos findGround(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos(pos.getX(), pos.getY(), pos.getZ());
        mp.setY(Math.min(level.getMaxBuildHeight(), 120));
        while (mp.getY() > level.getMinBuildHeight()) {
            if (!level.getBlockState(mp).isAir()
                    && level.getBlockState(mp.above()).isAir()) {
                return mp.above().immutable();
            }
            mp.move(0, -1, 0);
        }
        return pos;
    }
}
