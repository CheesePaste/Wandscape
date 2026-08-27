package com.wsteam.wandscape.tourist.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.core.event.NarrativeEventTriggered;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.colony.ColonyActivation;
import com.wsteam.wandscape.engine.colony.ColonyLevelManager;
import com.wsteam.wandscape.engine.service.ChunkLoadManager;
import com.wsteam.wandscape.road.core.RoadEdge;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.api.RoadApi;
import com.wsteam.wandscape.shared.api.TouristApi;
import com.wsteam.wandscape.shared.data.*;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.tourist.entity.TouristEntity;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
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
 *   <li>Spawn window (1000-8000): tourists spawn at distributed random times
 *   <li>Evening (14000-18000): no new spawns, existing tourists continue interactions
 *   <li>Night departure (18000-24000): 满条夜晚离场 / 入旅店 + hotel routing
 * </ul>
 *
 * <p><b>Spawn count:</b> uniform integer range
 * [base+(lv-1)×levelSpawnBonus, base+(lv-1)×levelSpawnBonus+spawnRangeWidth],
 * spawn times spread evenly across the spawn window
 * <br><b>Tourist level distribution:</b> colonyLevel-1 (40%), colonyLevel (40%), colonyLevel+1 (20%)
 */
public final class TouristSpawnSystem {
    private static final String TAG = "TouristSpawnSystem";

    /** Tick interval between spawn/cleanup checks. */
    private static final int CHECK_INTERVAL = 100;
    /** Tick offset after departure window start before first purge. */
    private static final int DEPARTURE_INITIAL_DELAY = 200;

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

            ChunkPos cp = new ChunkPos(ps.spawnPos);
            ChunkLoadManager.get().acquireChunk(cp);
            try {
                // Reuse the stuck-rescue picker so a spawn never lands on a roof or
                // inside a building: road first, then safe ground near the building.
                BlockPos ground = TouristTeleport.findSafeSpot(level, ps.spawnPos(), target.getColonyId(), ps.buildingId());
                if (ground == null) continue;
                TouristEntity tourist = new TouristEntity(
                        com.wsteam.wandscape.Wandscape.TOURIST.get(), level);
                tourist.setTouristName(generateRandomTouristName(target.getColonyId()));
                tourist.setPos(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5);
                tourist.setLevel(ps.level);
                tourist.setWallet(startingWallet(ps.level));
                tourist.setInitialWallet(startingWallet(ps.level));
                // Block 2：生成默认值（画像 need / 停留截止 / 总旅费）；不指派初始目标，出生即闲逛，目标由 Block 3 视野内 Find-Best-Action 决定。
                instance.applySpawnDefaults(tourist, ps.level, level.getGameTime());
                tourist.setColonyId(target.getColonyId());
                tourist.setArrivalTime(level.getGameTime());
                tourist.applyState(TouristState.VISITING);
                level.addFreshEntity(tourist);

                // Create the data shadow so the sim can track this tourist when its chunk unloads.
                // 到达登记由 TouristEntity.onAddedToLevel 单点完成（覆盖刷怪蛋/命令路径）。
                TouristSimSystem sim = TouristSimSystem.getActive();
                if (sim != null) sim.adoptTourist(tourist);
            } finally {
                ChunkLoadManager.get().releaseChunk(cp);
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        try (var span = com.wsteam.wandscape.shared.util.TickProfiler.INSTANCE.start("tourist.spawn.on_server_tick")) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (level == null) return;

        long dayTime = level.getDayTime() % 24000;
        long day = level.getDayTime() / 24000;

        // 创始人不在线且关闭离线运行 → 冻结小镇：不生成新游客、不清冻结游客
        UUID colonyId = getColonyId();
        boolean colonyFrozen = colonyId != null
                && !ColonyActivation.isColonyActive(colonyId);

        // ── Morning: reset schedule flag + count overnight stayers（每 tick 检查，便宜）──
        if (dayTime < 1000 && scheduleDay != day) {
            scheduleCreated = false;
            pendingSpawns.clear();
            scheduleDay = day;
            countOvernightStayers(level);
        }

        // ── Spawn window (1000-8000)：每 tick flush，不等 CHECK_INTERVAL ──
        // 高 tick rate（如 1000）下游戏时间推进更快：若只在每 CHECK_INTERVAL tick 才
        // flush，生成窗口可能被跳过去、每日游客「来不及生成」。改为每 tick flush，
        // 每个 pending 的随机 spawnTime 一到就立即生成，窗口内绝不漏。
        boolean inSpawnWindow = dayTime >= Config.TOURIST_SPAWN_WINDOW_START.get()
                && dayTime < Config.TOURIST_SPAWN_WINDOW_END.get();
        if (inSpawnWindow && !colonyFrozen) {
            if (!scheduleCreated || scheduleDay != day) {
                createSchedule(level);
                scheduleDay = day;
            }
            flushPendingSpawns(level);
        }

        // ── 周期性重型工作（每 CHECK_INTERVAL tick）──
        tickCounter++;
        if (tickCounter % CHECK_INTERVAL != 0) return;

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

        // Collect valid four-category tourist targets (shop/service/relax/atm)
        List<BuildingState> touristTargets = getTouristTargets(level, allBuildings);
        if (touristTargets.isEmpty()) {
            Log.warn(TAG, "[Tourist] No intact tourist-target buildings available — "
                    + "tourists have no targets. Build shop/service/relax/atm buildings to attract tourists.");
            return;
        }

        // Get colony ID to query colony level
        UUID colonyId = getColonyId();
        if (colonyId == null) {
            Log.warn(TAG, "[Tourist] No colony registered — tourists cannot spawn. "
                    + "Use '/wandscape colony create' to create a colony, then build a town hall within range.");
            return;
        }

        // 每天固定新增这批游客，不因小镇已有游客（含住店客）而扣减
        int colonyLevel = levelManager != null ? levelManager.getLevel(colonyId) : 1;
        int lower = Config.TOURIST_BASE_SPAWN_COUNT.get()
                + (colonyLevel - 1) * Config.TOURIST_LEVEL_SPAWN_BONUS.get();
        int upper = lower + Config.TOURIST_SPAWN_RANGE_WIDTH.get();
        int toSpawn = lower + (upper > lower ? random.nextInt(upper - lower) : 0);
        toSpawn = Math.max(1, Math.min(toSpawn, Config.TOURIST_MAX_PER_COLONY.get()));

        // Collect spawn positions
        List<BlockPos> spawnCandidates = collectSpawnPositions(level, allBuildings);

        // Create pending spawns. 生成时间在 [windowStart, windowEnd) 内随机取，
        // 每天游客错峰到达。高 tick rate 防护在 onServerTick（每 tick flush），
        // 不在这里——这里只负责把到达时间随机分布到生成窗口内。
        int windowStart = Config.TOURIST_SPAWN_WINDOW_START.get();
        int windowDuration = Config.TOURIST_SPAWN_WINDOW_END.get() - windowStart;
        for (int i = 0; i < toSpawn; i++) {
            BlockPos spawnPos = pickSpawnPos(spawnCandidates);
            if (spawnPos == null) continue;

            // Pick tourist level based on colony level distribution
            int touristLevel = rollTouristLevel(colonyLevel);

            // Pick target building weighted by preference. Only the buildingId is
            // stored — the target is re-validated and interaction point re-derived
            // at spawn time, so a building demolished after scheduling can't ghost it.
            BuildingState target = touristTargets.get(random.nextInt(touristTargets.size()));

            int spawnTime = windowDuration > 0
                    ? windowStart + random.nextInt(windowDuration) : windowStart;

            pendingSpawns.add(new PendingSpawn(touristLevel, spawnTime, spawnPos, target.getBuildingId()));
        }

        if (!pendingSpawns.isEmpty()) {
            Log.info(TAG, "[Tourist] Schedule created: {} tourists (colony Lv.{}), dailyArrivals={}",
                    pendingSpawns.size(), colonyLevel, toSpawn);
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
                // demolished after scheduling. Never spawn a tourist near a ghost.
                var target = buildingApi.getBuilding(ps.buildingId());
                if (target == null || target.isShutdown() || !target.isStructureIntact() || target.isDemolishing()) {
                    continue;
                }

                // Momentarily force-load the spawn chunk so the landing spot is resolved
                // against real blocks (block reads on unloaded chunks return AIR) and to
                // avoid addFreshEntity-on-unloaded-chunk edge cases. The tourist then sims
                // from its shadow until the chunk is loaded again.
                ChunkPos cp = new ChunkPos(ps.spawnPos);
                ChunkLoadManager.get().acquireChunk(cp);
                try {
                    // Reuse the stuck-rescue picker so a spawn never lands on a roof or
                    // inside a building: road first, then safe ground near the building.
                    BlockPos ground = TouristTeleport.findSafeSpot(level, ps.spawnPos(), target.getColonyId(), ps.buildingId());
                    if (ground == null) continue;
                    TouristEntity tourist = new TouristEntity(
                            com.wsteam.wandscape.Wandscape.TOURIST.get(), level);
                    tourist.setTouristName(generateRandomTouristName(target.getColonyId()));
                    tourist.setPos(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5);
                    tourist.setLevel(ps.level);
                    tourist.setWallet(startingWallet(ps.level));
                    tourist.setInitialWallet(startingWallet(ps.level));
                    // Block 2：生成默认值（画像 need / 停留截止 / 总旅费）；不指派初始目标，出生即闲逛，目标由 Block 3 视野内 Find-Best-Action 决定。
                    applySpawnDefaults(tourist, ps.level, level.getGameTime());
                    tourist.setColonyId(target.getColonyId());
                    tourist.setArrivalTime(level.getGameTime());
                    tourist.applyState(TouristState.VISITING);
                    level.addFreshEntity(tourist);

                    // Create the data shadow so the sim can track this tourist when its chunk unloads.
                    // 到达登记由 TouristEntity.onAddedToLevel 单点完成（覆盖刷怪蛋/命令路径）。
                    TouristSimSystem sim = TouristSimSystem.getActive();
                    if (sim != null) sim.adoptTourist(tourist);

                    Log.info(TAG, "[Tourist] {} (Lv.{}) spawned at {} (colony {})",
                            tourist.getTouristName(), ps.level, ground.toShortString(),
                            target.getColonyId());
                } finally {
                    ChunkLoadManager.get().releaseChunk(cp);
                }
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

    /**
     * Block 2 生成默认值：画像 roll（按等级缩放三条 need）＋ 停留截止（2~4 天）＋ 总旅费（ATM 取现池）。
     * 出生不指派目标建筑，由 Block 3 的视野内 Find-Best-Action 决定。
     */
    private void applySpawnDefaults(TouristEntity tourist, int touristLevel, long gameTime) {
        rollAndSetPersona(tourist, touristLevel);
        int stayMin = Config.TOURIST_STAY_MIN_DAYS.get();
        int stayMax = Config.TOURIST_STAY_MAX_DAYS.get();
        long stayTicks = (stayMin + random.nextInt(stayMax - stayMin + 1)) * 24000L;
        tourist.setDepartureDeadline(gameTime + stayTicks);
        tourist.setTravelFund((int) Math.round(startingWallet(touristLevel)
                * Config.TOURIST_ATM_TRAVEL_FUND_MULTIPLIER.get()));
    }

    /**
     * 刷怪蛋/命令生成的游客补齐随机生成默认值，使其等同于随机生成的游客：
     * 按小镇等级 roll 等级（无小镇/等级管理器时按 1）、按等级算随身现金与总旅费、
     * roll 画像三条 need、补 2~4 天停留窗口与 arrivalTime。磁盘加载（有 NBT）的游客不走这里。
     */
    public static void applyRandomSpawnDefaults(TouristEntity tourist, @Nullable UUID colonyId, long gameTime) {
        if (instance == null) {
            Log.warn(TAG, "[Tourist] SpawnSystem not registered — cannot apply random defaults");
            return;
        }
        int colonyLevel = (colonyId != null && instance.levelManager != null)
                ? instance.levelManager.getLevel(colonyId) : 1;
        int touristLevel = instance.rollTouristLevel(colonyLevel);
        tourist.setLevel(touristLevel);
        int start = instance.startingWallet(touristLevel);
        tourist.setWallet(start);
        tourist.setInitialWallet(start);
        instance.applySpawnDefaults(tourist, touristLevel, gameTime);
        tourist.setArrivalTime(gameTime);
    }

    /**
     * 画像 roll：40% 均衡 {1,1,1}；20% 舒适 {1.6,0.7,0.7}；20% 魔法 {0.7,1.6,0.7}；20% 奇观 {0.7,0.7,1.6}。
     * 三条 need = 总需求 × 画像权重占比，总需求 = BASE + (level-1)×PER_LEVEL（等级越高越难满足）。
     * 1 级（totalNeed=60）：均衡 → 20/20/20；侧重 → 32/14/14（及其置换）。
     */
    private void rollAndSetPersona(TouristEntity t, int touristLevel) {
        double r = random.nextDouble();
        double[] w = r < 0.4 ? PERSONA_WEIGHTS[0]
                  : r < 0.6 ? PERSONA_WEIGHTS[1]
                  : r < 0.8 ? PERSONA_WEIGHTS[2]
                  : PERSONA_WEIGHTS[3];
        int totalNeed = Config.TOURIST_NEED_BASE.get() + (touristLevel - 1) * Config.TOURIST_NEED_PER_LEVEL.get();
        int[] need = personaNeeds(totalNeed, w);
        t.setComfortNeed(need[0]);
        t.setMagicNeed(need[1]);
        t.setWonderNeed(need[2]);
    }

    private static final double[][] PERSONA_WEIGHTS = {
            {1.0, 1.0, 1.0}, {1.6, 0.7, 0.7}, {0.7, 1.6, 0.7}, {0.7, 0.7, 1.6}
    };

    /** 纯计算（可单测）：把 totalNeed 按画像权重占比分配到三条 need。 */
    static int[] personaNeeds(int totalNeed, double[] w) {
        double sum = w[0] + w[1] + w[2];
        return new int[]{
                (int) Math.round(totalNeed * w[0] / sum),
                (int) Math.round(totalNeed * w[1] / sum),
                (int) Math.round(totalNeed * w[2] / sum)
        };
    }

    // ════════════════════════════════════════════════════════════════
    // Cleanup
    // ════════════════════════════════════════════════════════════════

    /**
     * Cleanup logic applying to all times of day（Block 2 D6）：
     * <ul>
     *   <li>满条法师 → 即时存简历</li>
     *   <li>到点（departureDeadline）→ 离场（住店客也只按此离场——无论多晚不被清）</li>
     *   <li>idle 超时 → 离场（仅非住店客）</li>
     *   <li>精力 0 不再离场（goal.md：无恢复建筑 → 闲逛，不离场）</li>
     *   <li>夜晚离场由 {@link #processNightDepartures} 处理</li>
     * </ul>
     */
    private void cleanupTourists(ServerLevel level, boolean inDepartureWindow) {
        List<TouristEntity> toRemove = new ArrayList<>();

        for (TouristEntity t : TouristSimSystem.getLiveTourists()) {
            if (!t.isAlive()) continue;
            if (t.isPreview()) continue; // 预览假人：不参与生成/离开

            // 创始人不在线 → 冻结小镇：不清除其游客（原地冻结）
            UUID cid = t.getColonyId();
            if (cid != null && !ColonyActivation.isColonyActive(cid)) {
                continue;
            }

            // Store mage resume instantly when fully satisfied
            if (t.isFullySatisfied() && t.isMage() && !t.isMageResumeStored()) {
                storeMageResume(t);
                t.setMageResumeStored(true);
            }

            // 住店客：只按停留截止离场（不被夜晚/闲置清掉；夜晚回店睡觉由 TouristMoveGoal 管）
            if (t.getCheckedInBuildingId() != null) {
                if (level.getGameTime() >= t.getDepartureDeadline()) {
                    toRemove.add(t);
                }
                continue;
            }

            // In departure window, night logic is handled by processNightDepartures
            if (inDepartureWindow) continue;

            // 白天/傍晚：到点 或 idle 超时 → 离场
            boolean deadlineReached = level.getGameTime() >= t.getDepartureDeadline();
            boolean idleTimeout = t.getCommuteTarget() == null
                    && t.tickCount > Config.TOURIST_DESPAWN_TIMEOUT_TICKS.get();
            if (deadlineReached || idleTimeout) {
                toRemove.add(t);
            }
        }

        for (TouristEntity t : toRemove) {
            onTouristDepart(t, level);
            t.discard();
        }
    }

    /**
     * Night departure window（18000-24000，Block 2 D6）：
     * <ul>
     *   <li>到点 → 离场（满条才给经验；住店客也适用）</li>
     *   <li>满条 → 开心离场（随机延迟错峰，简历已存；住店客满条当晚也离场）</li>
     *   <li>非满条住店客 → 留店（无论多晚不被清）</li>
     *   <li>非满条非住店客 → 入旅店；无旅店/满 → 离场</li>
     * </ul>
     */
    private void processNightDepartures(ServerLevel level) {
        long gameTime = level.getGameTime();
        List<TouristEntity> toRemove = new ArrayList<>();

        for (TouristEntity t : TouristSimSystem.getLiveTourists()) {
            if (!t.isAlive()) continue;
            if (t.isPreview()) continue; // 预览假人：不参与生成/离开

            // 创始人不在线 → 冻结小镇：不安排其游客离场（原地冻结）
            UUID cid = t.getColonyId();
            if (cid != null && !ColonyActivation.isColonyActive(cid)) {
                continue;
            }

            // Store mage resume instantly when fully satisfied
            if (t.isFullySatisfied() && t.isMage() && !t.isMageResumeStored()) {
                storeMageResume(t);
                t.setMageResumeStored(true);
            }

            // 到点 → 离场（住店客也适用）
            if (gameTime >= t.getDepartureDeadline()) {
                toRemove.add(t);
                pendingDepartures.remove(t.getUUID());
                continue;
            }

            if (t.getCheckedInBuildingId() != null) {
                // 住店客：满条 → 当晚开心离场；未满条 → 留店（不被清）
                if (t.isFullySatisfied()) {
                    Long departAt = pendingDepartures.get(t.getUUID());
                    if (departAt == null) {
                        int delay = random.nextInt(Config.TOURIST_DEPARTURE_DELAY_MAX_TICKS.get() + 1);
                        departAt = gameTime + delay;
                        pendingDepartures.put(t.getUUID(), departAt);
                    }
                    if (gameTime >= departAt) {
                        toRemove.add(t);
                        pendingDepartures.remove(t.getUUID());
                    }
                }
                continue;
            }

            if (t.isFullySatisfied()) {
                // 满条 → 开心离场：随机延迟错峰
                Long departAt = pendingDepartures.get(t.getUUID());
                if (departAt == null) {
                    int delay = random.nextInt(Config.TOURIST_DEPARTURE_DELAY_MAX_TICKS.get() + 1);
                    departAt = gameTime + delay;
                    pendingDepartures.put(t.getUUID(), departAt);
                }
                if (gameTime >= departAt) {
                    toRemove.add(t);
                    pendingDepartures.remove(t.getUUID());
                }
            } else {
                // 非满条 → 夜晚入旅店；无旅店/满 → 离场
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
     * Grant colony experience when a tourist departs with all three bars full.
     * 创始人离线时经验 × offlineIncomeMultiplier（满条离场是唯一经验来源）。
     */
    private void grantExperience(TouristEntity t) {
        if (!t.isFullySatisfied()) return;
        if (levelManager == null) return;
        UUID colonyId = t.getColonyId();
        if (colonyId == null) return;
        int colonyLevel = levelManager.getLevel(colonyId);
        int contribution = ColonyLevelManager.computeExpContribution(colonyLevel, t.getLevel());
        int scaled = (int) ColonyActivation.scaleIncome(contribution,
                ColonyActivation.getIncomeMultiplier(colonyId));
        if (scaled > 0) {
            levelManager.addExperience(colonyId, scaled);
            Log.info(TAG, "[Tourist] {} (Lv.{}) granted {} exp to colony Lv.{} (满条)",
                    t.getTouristName(), t.getLevel(), scaled, colonyLevel);
        }
    }

    private void storeMageResume(TouristEntity t) {
        UUID colonyId = t.getColonyId();
        if (colonyId == null) return;
        try {
            var tavernApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getTavernApi();
            tavernApi.receiveMageResume(colonyId, t.getTouristName(), t.getLevel(),
                    t.getMaxHp(), t.getMoveSpeed(), t.getSpellPower(),
                    t.getWorkSpeed(), t.getSpellSpeed(), t.getArmor(),
                    t.getMaxMana(), t.getSkinVariant());
            t.setMageResumeStored(true);
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
        BarRatio fill = BarRatio.of(t.getComfortSat(), t.getComfortNeed(),
                t.getMagicSat(), t.getMagicNeed(), t.getWonderSat(), t.getWonderNeed());
        int barRatioPct = fill.minPct(); // 离场语调（min-ratio×100）

        // Grant colony experience only when fully satisfied
        if (t.isFullySatisfied()) {
            grantExperience(t);
        }

        // Safety net: store mage resume at departure if not already stored
        if (t.isMage() && t.isFullySatisfied() && !t.isMageResumeStored()) {
            storeMageResume(t);
        }

        // Register departure via TouristApi → fires TouristDepartedEvent
        var touristApi = getTouristApi();
        if (touristApi != null && colonyId != null) {
            touristApi.registerDeparture(t.getUUID(), colonyId, fill);
        }

        // Remove the data shadow — a departed tourist has no sim state left.
        TouristSimSystem sim = TouristSimSystem.getActive();
        if (sim != null) sim.removeShadow(t.getUUID());

        // Generate departure narrative (no on-screen text — silent by design)
        int visitCount = t.getRecentVisits().size();

        NarrativeEvent departureEvent = NarrativeGenerator.generateDeparture(
                t.getTouristName(), barRatioPct, visitCount, t.level().getGameTime());
        emitNarrativeEvent(departureEvent);

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
            // Only route to a hotel whose chunk is loaded — a loaded tourist can't
            // path into an unloaded chunk.
            if (!level.isLoaded(b.getPosition())) continue;

            BlockPos target = api.getTouristInteractionTarget(b.getBuildingId());
            if (target == null) continue;

            t.setTargetBuildingId(b.getBuildingId());
            t.setTargetBuildingCategory("service");
            t.setCommuteTarget(target);
            Log.info(TAG, "[Tourist] {} routed to hotel {} (bars={}/{}/{} energy={})",
                    t.getTouristName(), b.getBuildingId().toString().substring(0, 8),
                    t.getComfortSat(), t.getMagicSat(), t.getWonderSat(), t.getEnergy());
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
            if (!"shop".equals(cat) && !"service".equals(cat) && !"relax".equals(cat) && !"atm".equals(cat)) {
                continue;
            }
            if (b.isShutdown() || !b.isStructureIntact()) continue;
            BuildingState state = savedData.getBuilding(b.getBuildingId());
            if (state != null) targets.add(state);
        }
        return targets;
    }

    /** Count tourists currently checked into hotels per colony and store as overnight stayers. */
    private void countOvernightStayers(ServerLevel level) {
        java.util.Map<UUID, Integer> overnightCounts = new java.util.HashMap<>();
        for (TouristEntity t : TouristSimSystem.getLiveTourists()) {
            if (t.isAlive() && t.getCheckedInBuildingId() != null) {
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

    /** Pick a random spawn candidate (road point or building anchor) with a small jitter. */
    private BlockPos pickSpawnPos(List<BlockPos> candidates) {
        if (candidates.isEmpty()) return null;
        BlockPos picked = candidates.get(random.nextInt(candidates.size()));
        return picked.offset(
                random.nextInt(10) - 5, 0, random.nextInt(10) - 5);
    }

    // ── Name generation ──

    /**
     * Roll a name key for a tourist of the given colony: the colony's naming
     * style decides the pool, and display names already in use by the colony's
     * live tourists are excluded so the single-name fantasy pool avoids dupes.
     */
    public static String generateRandomTouristName(UUID colonyId) {
        NameStyle style = getNamingStyle(colonyId);
        Set<String> used = new HashSet<>();
        for (TouristEntity t : TouristSimSystem.getLiveTourists()) {
            if (t.getColonyId() != null && t.getColonyId().equals(colonyId)) {
                used.add(t.getTouristName());
            }
        }
        return CharacterNames.generateRandomNameKey(style, used);
    }

    @Nullable
    private static NameStyle getNamingStyle(@Nullable UUID colonyId) {
        ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi != null && colonyId != null) {
            return colonyApi.getNamingStyle(colonyId);
        }
        return NameStyle.FANTASY;
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
