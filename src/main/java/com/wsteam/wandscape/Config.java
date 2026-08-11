package com.wsteam.wandscape;

import net.neoforged.neoforge.common.ModConfigSpec;
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue DEBUG = BUILDER
            .comment("Verbose logging: show INFO/DEBUG log messages. "
                    + "When false (default), only WARN/ERROR messages are logged.")
            .define("general.debug", false);

    public static final ModConfigSpec.IntValue COLONY_RADIUS = BUILDER
            .comment("Default colony radius in blocks")
            .defineInRange("general.colonyRadius", 128, 16, 512);

    public static final ModConfigSpec.IntValue MAX_CONCURRENT_BUILDINGS = BUILDER
            .comment("Maximum number of buildings force-loaded for construction/production at once. "
                    + "While a colony's chunks are unloaded, the active building's footprint is "
                    + "force-loaded to run real block placement; this caps the concurrent cost.")
            .defineInRange("general.maxConcurrentBuildings", 3, 1, 32);

    public static final ModConfigSpec.IntValue SCHEDULER_HEARTBEAT_TICKS = BUILDER
            .comment("Scheduler heartbeat interval in ticks: how often idle NPCs are matched "
                    + "to pending tasks (20 ticks = 1 second)")
            .defineInRange("scheduler.heartbeatTicks", 20, 10, 200);

    public static final ModConfigSpec.DoubleValue SAME_BUILDING_CONTINUATION_BONUS = BUILDER
            .comment("Score bonus for NPC continuing tasks on the same building")
            .defineInRange("scheduler.sameBuildingContinuationBonus", 50.0, 0.0, 500.0);

    public static final ModConfigSpec.IntValue STUCK_CHECK_INTERVAL_TICKS = BUILDER
            .comment("Interval in ticks between NPC stuck checks (60 ticks = 3 seconds)")
            .defineInRange("scheduler.stuckCheckIntervalTicks", 60, 20, 200);

    public static final ModConfigSpec.DoubleValue STUCK_MIN_MOVE_DISTANCE = BUILDER
            .comment("Minimum distance an NPC must move within stuck check interval to not be considered stuck")
            .defineInRange("scheduler.stuckMinMoveDistance", 2.0, 0.5, 10.0);

    public static final ModConfigSpec.IntValue STUCK_MAX_RETRIES = BUILDER
            .comment("Number of consecutive stuck checks before NPC teleports")
            .defineInRange("scheduler.stuckMaxRetries", 3, 1, 10);

    public static final ModConfigSpec.IntValue NPC_REGEN_GRACE_TICKS = BUILDER
            .comment("Ticks after taking damage before out-of-combat health regen resumes (100 = 5s)")
            .defineInRange("npc.regenGraceTicks", 100, 20, 1000);

    public static final ModConfigSpec.IntValue NPC_REGEN_INTERVAL_TICKS = BUILDER
            .comment("Ticks per 1 HP healed once out-of-combat regen is active (80 = 4s)")
            .defineInRange("npc.regenIntervalTicks", 80, 20, 400);

    public static final ModConfigSpec.IntValue NPC_MANA_REGEN_TICKS = BUILDER
            .comment("Ticks per 1 mana regenerated (10 = 1 point every 0.5s)")
            .defineInRange("npc.manaRegenTicks", 10, 1, 100);

    public static final ModConfigSpec.IntValue NPC_WALK_THRESHOLD = BUILDER
            .comment("Max distance in blocks for NPC pathfinding; beyond this they teleport")
            .defineInRange("npc.walkThreshold", 64, 16, 256);

    public static final ModConfigSpec.IntValue BASE_OPERATION_RANGE = BUILDER
            .comment("Base operation range for wand operations")
            .defineInRange("wand.baseOperationRange", 16, 4, 64);

    public static final ModConfigSpec.IntValue PER_WAND_LEVEL_RANGE = BUILDER
            .comment("Additional range per wand level")
            .defineInRange("wand.perWandLevelRange", 8, 0, 32);

    // ---- Road system ----

    public static final ModConfigSpec.IntValue ROAD_BUILDING_THRESHOLD = BUILDER
            .comment("Minimum number of buildings before road network is generated")
            .defineInRange("road.buildingThreshold", 3, 2, 50);

    public static final ModConfigSpec.IntValue ROAD_SEGMENT_MAX_LENGTH = BUILDER
            .comment("Maximum tiles per road segment")
            .defineInRange("road.segmentMaxLength", 16, 4, 64);

    public static final ModConfigSpec.IntValue ROAD_DEFAULT_WIDTH = BUILDER
            .comment("Default road width in blocks (1-5)")
            .defineInRange("road.defaultWidth", 3, 1, 5);

    public static final ModConfigSpec.IntValue ROAD_MAX_CUT_DEPTH = BUILDER
            .comment("Maximum depth the road will excavate into terrain before logging a warning (0 = no limit)")
            .defineInRange("road.maxCutDepth", 8, 0, 64);

    public static final ModConfigSpec.IntValue ROAD_MAX_FILL_HEIGHT = BUILDER
            .comment("Maximum height the road will fill below surface before logging a warning (0 = no limit)")
            .defineInRange("road.maxFillHeight", 6, 0, 64);

    public static final ModConfigSpec.ConfigValue<String> ROAD_SURFACE_PALETTE = BUILDER
            .comment("Weighted block palette for road surface. Format: \"modid:block=weight,...\""
                    + " — weights are relative, total need not be 100.")
            .define("road.surfacePalette",
                    "minecraft:stone_bricks=50,minecraft:andesite=25,minecraft:stone=25");

    // ---- Road pillars ----

    public static final ModConfigSpec.BooleanValue ROAD_PILLAR_ENABLED = BUILDER
            .comment("Whether viaduct pillars are generated below elevated road segments")
            .define("road.pillar.enabled", true);

    public static final ModConfigSpec.IntValue ROAD_PILLAR_SPACING = BUILDER
            .comment("Spacing in path points between pillars (higher = sparser)")
            .defineInRange("road.pillar.spacing", 4, 2, 16);

    public static final ModConfigSpec.ConfigValue<String> ROAD_PILLAR_BLOCK = BUILDER
            .comment("Block used for viaduct support pillars under elevated roads")
            .define("road.pillar.block", "minecraft:stone_bricks");

    // ---- Road decoration ----

    public static final ModConfigSpec.BooleanValue ROAD_DECORATION_ENABLED = BUILDER
            .comment("Whether road decoration (lamps, benches) is generated")
            .define("road.decoration.enabled", true);

    public static final ModConfigSpec.IntValue ROAD_DECORATION_LAMP_SPACING = BUILDER
            .comment("Distance in blocks between lamps along roads (0 = disabled)")
            .defineInRange("road.decoration.lampSpacing", 8, 0, 64);

    public static final ModConfigSpec.IntValue ROAD_DECORATION_BENCH_SPACING = BUILDER
            .comment("Distance in blocks between benches along roads (0 = disabled)")
            .defineInRange("road.decoration.benchSpacing", 24, 0, 64);

    public static final ModConfigSpec.ConfigValue<String> ROAD_DECORATION_LAMP_POST = BUILDER
            .comment("Block used for lamp posts")
            .define("road.decoration.lampPost", "minecraft:oak_fence");

    public static final ModConfigSpec.ConfigValue<String> ROAD_DECORATION_LAMP_LIGHT = BUILDER
            .comment("Block used for the light source on top of lamp posts")
            .define("road.decoration.lampLight", "minecraft:lantern");

    public static final ModConfigSpec.ConfigValue<String> ROAD_DECORATION_BENCH_BLOCK = BUILDER
            .comment("Block used for benches (supports [facing=...] state)")
            .define("road.decoration.benchBlock", "minecraft:oak_stairs");

    // ---- Colony autonomy ----

    public static final ModConfigSpec.BooleanValue AUTO_APPROVE_TASKS = BUILDER
            .comment("When true, all colony tasks skip the player-approval gate and are assigned automatically.")
            .comment("Disable to review large build/reconstruction tasks before NPCs start work.")
            .define("general.autoApproveTasks", false);

    // ---- Tourist system ----

    public static final ModConfigSpec.IntValue TOURIST_MAX_PER_COLONY = BUILDER
            .comment("Maximum simultaneous tourists per colony")
            .defineInRange("tourist.maxPerColony", 100, 5, 500);

    public static final ModConfigSpec.IntValue TOURIST_DESPAWN_TIMEOUT_TICKS = BUILDER
            .comment("Ticks before an idle tourist despawns (36000 ticks = 30 minutes)")
            .defineInRange("tourist.despawnTimeoutTicks", 36000, 12000, 72000);

    public static final ModConfigSpec.IntValue TOURIST_BASE_SPAWN_COUNT = BUILDER
            .comment("Daily spawn count lower bound at colony level 1. "
                    + "每日新增数 = 均匀区间 [base+(lv-1)×levelSpawnBonus, base+(lv-1)×levelSpawnBonus+spawnRangeWidth-1]")
            .defineInRange("tourist.baseSpawnCount", 5, 1, 100);

    public static final ModConfigSpec.IntValue TOURIST_LEVEL_SPAWN_BONUS = BUILDER
            .comment("Additional tourists per colony level (both lower and upper bounds +1 per level)")
            .defineInRange("tourist.levelSpawnBonus", 1, 0, 10);

    public static final ModConfigSpec.IntValue TOURIST_SPAWN_RANGE_WIDTH = BUILDER
            .comment("Daily spawn count fluctuation width: target ∈ [lower, lower+width-1]. "
                    + "默认 3 = 1 级 5~7、2 级 6~8、3 级 7~9")
            .defineInRange("tourist.spawnRangeWidth", 3, 0, 50);

    public static final ModConfigSpec.IntValue TOURIST_SPAWN_WINDOW_START = BUILDER
            .comment("Spawn window start (game time tick)")
            .defineInRange("tourist.spawnWindowStart", 1000, 0, 24000);

    public static final ModConfigSpec.IntValue TOURIST_SPAWN_WINDOW_END = BUILDER
            .comment("Spawn window end (game time tick) — no new spawns after this. "
                    + "默认 8000（约 14:00）：游客集中在上午到，最晚的也有整个下午逛、傍晚走向旅店，"
                    + "避免黄昏/夜晚才生成导致当晚因路由不到旅店被清场。")
            .defineInRange("tourist.spawnWindowEnd", 8000, 0, 24000);

    public static final ModConfigSpec.IntValue TOURIST_DEPARTURE_WINDOW_START = BUILDER
            .comment("Night departure window start (game time tick)")
            .defineInRange("tourist.departureWindowStart", 18000, 0, 24000);

    public static final ModConfigSpec.IntValue TOURIST_DEPARTURE_WINDOW_END = BUILDER
            .comment("Night departure window end (game time tick)")
            .defineInRange("tourist.departureWindowEnd", 24000, 0, 24000);

    public static final ModConfigSpec.IntValue TOURIST_DEPARTURE_DELAY_MAX_TICKS = BUILDER
            .comment("Max random delay ticks before night departure (0-this)")
            .defineInRange("tourist.departureDelayMaxTicks", 1500, 0, 6000);

    public static final ModConfigSpec.IntValue TOURIST_RESCUE_ROAD_RADIUS = BUILDER
            .comment("Max blocks a rescue teleport searches for a road before falling back to building periphery")
            .defineInRange("tourist.rescueRoadRadius", 96, 16, 512);

    public static final ModConfigSpec.IntValue TOURIST_RESCUE_PERIPHERY_RADIUS = BUILDER
            .comment("Max blocks a rescue teleport scans outward for open ground outside all buildings")
            .defineInRange("tourist.rescuePeripheryRadius", 24, 8, 128);

    public static final ModConfigSpec.IntValue COLONY_EXP_EQUAL_LEVEL = BUILDER
            .comment("Experience granted when tourist level == colony level (满条离场时)")
            .defineInRange("colony.expEqualLevel", 200, 0, 10000);

    public static final ModConfigSpec.IntValue COLONY_EXP_ABOVE_LEVEL = BUILDER
            .comment("Experience granted when tourist level > colony level")
            .defineInRange("colony.expAboveLevel", 500, 0, 10000);

    // ---- Decoration system ----

    public static final ModConfigSpec.DoubleValue DECORATION_BONUS_CAP = BUILDER
            .comment("Max decoration bonus as fraction of a building's base stat (1.0 = 100%)")
            .defineInRange("decoration.bonusCap", 1.0, 0.0, 5.0);

    public static final ModConfigSpec.IntValue DECORATION_SCAN_INTERVAL_TICKS = BUILDER
            .comment("Interval in ticks between decoration radiation recalculations")
            .defineInRange("decoration.scanIntervalTicks", 200, 40, 1200);

    // ---- Daily settlement ----

    /** Daily settlement happens when timeOfDay ≤ this threshold. */
    public static final ModConfigSpec.IntValue SETTLEMENT_WINDOW_TICKS = BUILDER
            .comment("Settlement window: time-of-day ticks within which daily settlement triggers (0 = exact 0:00 only)")
            .defineInRange("settlement.windowTicks", 10, 0, 100);

    // ---- Service system ----

    public static final ModConfigSpec.IntValue ARRIVAL_RADIUS = BUILDER
            .comment("Distance in blocks at which a tourist is considered to have arrived " +
                     "at a building's interact point or entry point. " +
                     "ARRIVAL_RADIUS controls the arrival distance for tourist navigation.")
            .defineInRange("tourist.arrivalRadius", 3, 1, 16);

    public static final ModConfigSpec.IntValue MICRO_NAV_SWITCH_DISTANCE = BUILDER
            .comment("Distance in blocks from a building's bounding box at which tourists " +
                     "switch from road-based macro navigation to indoor micro-navigation. " +
                     "Micro-navigation supports opening doors and walking around furniture.")
            .defineInRange("tourist.microNavSwitchDistance", 5, 2, 16);

    public static final ModConfigSpec.IntValue TOURIST_BASE_WALLET = BUILDER
            .comment("Starting universal-element wallet for a level-1 tourist. "
                    + "Reference prices: bread ~16, cake ~750, golden apple ~2684.")
            .defineInRange("tourist.baseWallet", 200, 0, 1000000);

    public static final ModConfigSpec.IntValue TOURIST_WALLET_PER_LEVEL = BUILDER
            .comment("Additional universal-element wallet per tourist level. "
                    + "Wallet = baseWallet + level × walletPerLevel.")
            .defineInRange("tourist.walletPerLevel", 300, 0, 1000000);

    // ── 游客经济改造：三条需求条 / 精力循环 / 停留 / 视野 / ATM（Block 0 新增）──

    public static final ModConfigSpec.DoubleValue TOURIST_BAR_GAIN_COEFF = BUILDER
            .comment("每条需求条填充 = round(建筑该维值 × 该系数)，封顶 need。默认 1.0 = 增益等于 JSON 值。")
            .defineInRange("tourist.barGainCoeff", 1.0, 0.1, 10.0);

    public static final ModConfigSpec.DoubleValue TOURIST_ENERGY_RESTORE_THRESHOLD = BUILDER
            .comment("精力低于此比例（0~1）时，游客强烈偏向恢复（relax）建筑。")
            .defineInRange("tourist.energyRestoreThreshold", 0.25, 0.0, 1.0);

    public static final ModConfigSpec.IntValue TOURIST_QUEUE_WAIT_TOLERANCE_TICKS = BUILDER
            .comment("spot 全满排队等待上限（tick），超时放弃去别处。")
            .defineInRange("tourist.queueWaitToleranceTicks", 2400, 0, 24000);

    public static final ModConfigSpec.DoubleValue TOURIST_QUEUE_SLOT_SPACING = BUILDER
            .comment("排队站位间距（格）：队首紧贴正在交互的游客，后续沿该 spot 朝向一个贴一个向后排开。")
            .defineInRange("tourist.queueSlotSpacing", 1.0, 0.5, 8.0);

    public static final ModConfigSpec.IntValue TOURIST_STAY_MIN_DAYS = BUILDER
            .comment("游客最少停留天数（离境截止下限）。")
            .defineInRange("tourist.stayMinDays", 2, 1, 7);

    public static final ModConfigSpec.IntValue TOURIST_STAY_MAX_DAYS = BUILDER
            .comment("游客最多停留天数（离境截止上限）。")
            .defineInRange("tourist.stayMaxDays", 4, 1, 7);

    public static final ModConfigSpec.IntValue TOURIST_VISION_RADIUS = BUILDER
            .comment("游客视野半径（格）：目标选择只看半径内且已加载的建筑；视野内无目标 → 闲逛。")
            .defineInRange("tourist.visionRadius", 64, 8, 256);

    public static final ModConfigSpec.DoubleValue TOURIST_ATM_TRAVEL_FUND_MULTIPLIER = BUILDER
            .comment("生成时 travelFund = 随身现金 × 该系数（ATM 分批取现的池子上限，防无限取现）。")
            .defineInRange("tourist.atmTravelFundMultiplier", 3.0, 1.0, 10.0);

    public static final ModConfigSpec.IntValue TOURIST_ATM_WITHDRAW_COOLDOWN_TICKS = BUILDER
            .comment("ATM 取现冷却（tick，1 游戏日=24000）：上次成功取现后多久才能再去 ATM。"
                    + "配合钱包低阈值控制「分批取现」节奏，防止游客连跑 ATM 一次性清空池子。")
            .defineInRange("tourist.atmWithdrawCooldownTicks", 2400, 0, 24000);

    public static final ModConfigSpec.IntValue TOURIST_NEED_BASE = BUILDER
            .comment("游客总需求基数：totalNeed = BASE + (level-1)×PER_LEVEL，等级越高越难满足。默认 60 = 1 级均衡 20/20/20。")
            .defineInRange("tourist.needBase", 60, 50, 2000);

    public static final ModConfigSpec.IntValue TOURIST_NEED_PER_LEVEL = BUILDER
            .comment("游客每级需求增量。")
            .defineInRange("tourist.needPerLevel", 20, 0, 500);

    public static final ModConfigSpec.IntValue TOURIST_NIGHT_START = BUILDER
            .comment("游客「夜晚」开始时刻（game time tick）：夜晚后游客优先去旅店、可入住、住店客回店睡觉。"
                    + "默认 14000（约 19:40），比原版天黑稍晚，让游客白天/傍晚多逛一会儿。")
            .defineInRange("tourist.nightStart", 14000, 0, 24000);

    public static final ModConfigSpec.IntValue TOURIST_EVENING_ROUTING_START = BUILDER
            .comment("傍晚路由开始时刻（game time tick）：无旅店的未满条游客从此刻起停止当前任务去旅店，"
                    + "避免天黑后还在逛商店、夜晚无旅店被清场。默认 16000（约 21:00，夜晚中）。"
                    + "旅店入住仍要求夜晚（>=nightStart）；此值只需早于离场窗口起点 18000 即可保证来得及。")
            .defineInRange("tourist.eveningRoutingStart", 16000, 0, 24000);

    public static final ModConfigSpec.IntValue TOURIST_HOTEL_TELEPORT_DISTANCE = BUILDER
            .comment("夜晚回/去旅店时，游客与旅店入口水平距离超过此值（格）→ 直接传送（省寻路开销，"
                    + "寻路到远/未加载区块代价大）。默认 64 = 视野半径（超出视野即传送）。")
            .defineInRange("tourist.hotelTeleportDistance", 64, 16, 512);

    // ---- Guard (守卫) system ----

    public static final ModConfigSpec.IntValue GUARD_RANGE = BUILDER
            .comment("Guard threat/attack radius: horizontal X/Z expansion of a building's AABB (Y unchanged). "
                    + "Monsters within this radius are attacked.")
            .defineInRange("guard.range", 10, 1, 64);

    public static final ModConfigSpec.IntValue GUARD_RELEASE_RANGE = BUILDER
            .comment("Guard release radius: the guard task completes only when no monster is within this "
                    + "horizontal X/Z expansion (hysteresis band, should be > guard.range to avoid edge churn). "
                    + "Y unchanged.")
            .defineInRange("guard.releaseRange", 15, 2, 64);

    public static final ModConfigSpec.IntValue GUARD_SELF_DEFENSE_RANGE = BUILDER
            .comment("NPC self-defense aggro radius (blocks): hostile mobs within this spherical distance "
                    + "around an NPC are attacked unconditionally, preempting the NPC's current task. "
                    + "Independent of building guard zones.")
            .defineInRange("guard.selfDefenseRange", 16, 1, 64);

    public static final ModConfigSpec.IntValue GUARD_HATE_RANGE = BUILDER
            .comment("NPC retaliation range (blocks): the NPC fights back against a non-player mob that "
                    + "damaged it while the attacker is within this distance (the hate is refreshed on each hit).")
            .defineInRange("guard.hateRange", 48, 1, 128);

    public static final ModConfigSpec.IntValue GUARD_HATE_DURATION_TICKS = BUILDER
            .comment("NPC hate memory (ticks): how long the NPC keeps a grudge against a non-player attacker "
                    + "before forgetting, unless it gets hurt again (600 = 30s).")
            .defineInRange("guard.hateDurationTicks", 600, 20, 72000);

    // ---- Raid (袭击) system ----

    public static final ModConfigSpec.IntValue RAID_TRIGGER_RANGE = BUILDER
            .comment("Raid trigger radius: a player carrying Bad Omen (RAID_OMEN/BAD_OMEN) within this horizontal "
                    + "X/Z expansion of a non-shutdown building's AABB (Y unchanged) starts a raid centered on the "
                    + "colony's town hall.")
            .defineInRange("raid.triggerRange", 10, 1, 64);

    public static final ModConfigSpec.IntValue RAID_VILLAGE_RANGE = BUILDER
            .comment("Raid village radius: ServerLevel.isVillage returns true within this horizontal distance of a "
                    + "colony's town hall, so the vanilla Raid treats the colony as a village (won't stop/LOSS, "
                    + "spawns waves around it).")
            .defineInRange("raid.villageRange", 16, 1, 64);

    public static final ModConfigSpec.IntValue RAID_NEARBY_RADIUS = BUILDER
            .comment("Raid nearby radius (blocks): while an active raid's center is within this distance of a "
                    + "colony's town hall, no new raid is triggered for that colony (one raid at a time).")
            .defineInRange("raid.nearbyRadius", 64, 8, 256);

    public static final ModConfigSpec.IntValue RAID_CHECK_INTERVAL = BUILDER
            .comment("Raid trigger scan interval (ticks): how often the scanner checks for bad-omen players near "
                    + "buildings.")
            .defineInRange("raid.checkIntervalTicks", 20, 5, 200);

    public static final ModConfigSpec.ConfigValue<String> PARTICLE_LEVEL = BUILDER
            .comment("Particle effect level: OFF disables all mod particles, LOW halves count, "
                    + "NORMAL default, HIGH doubles count.")
            .define("particle.level", "NORMAL");

    static final ModConfigSpec SPEC = BUILDER.build();
}
