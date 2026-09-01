package com.wsteam.wandscape;
import com.wsteam.wandscape.foundation.log.Log;

import net.neoforged.neoforge.common.ModConfigSpec;
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue DEBUG = BUILDER
            .comment("Verbose logging: show INFO/DEBUG log messages. "
                    + "When false (default), only WARN/ERROR messages are logged.")
            .define("general.debug", false);

    // ---- Transport (物品运输) ----

    public static final ModConfigSpec.IntValue TRANSPORT_TICKS_PER_BLOCK_ON_ROAD = BUILDER
            .comment("物品贴路巡航运输速度（tick/格，越小越快）：默认 2（10 格/秒），旧版为 10（2 格/秒）。")
            .defineInRange("transport.ticksPerBlockOnRoad", 2, 1, 20);

    public static final ModConfigSpec.IntValue TRANSPORT_TICKS_PER_BLOCK_OFF_ROAD = BUILDER
            .comment("物品离路/直飞运输速度（tick/格，越小越快）：默认 4（5 格/秒），旧版为 20（1 格/秒）。")
            .defineInRange("transport.ticksPerBlockOffRoad", 4, 1, 40);

    public static final ModConfigSpec.IntValue SCHEDULER_HEARTBEAT_TICKS = BUILDER
            .comment("Scheduler heartbeat interval in ticks: how often idle NPCs are matched "
                    + "to pending tasks (20 ticks = 1 second)")
            .defineInRange("scheduler.heartbeatTicks", 20, 10, 200);

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
            .comment("Ticks per mana regen settlement (10 = settle every 0.5s)")
            .defineInRange("npc.manaRegenTicks", 10, 1, 100);

    public static final ModConfigSpec.DoubleValue NPC_MANA_REGEN_FRACTION = BUILDER
            .comment("Fraction of max mana regenerated per settlement (0.01 = 1% of max; full refill ~50s)")
            .defineInRange("npc.manaRegenFraction", 0.01, 0.00, 1.0);

    public static final ModConfigSpec.IntValue NPC_WALK_THRESHOLD = BUILDER
            .comment("Max distance in blocks for NPC pathfinding; beyond this they teleport")
            .defineInRange("npc.walkThreshold", 64, 16, 256);

    public static final ModConfigSpec.IntValue REVIVE_NEAR_BUILDING_RANGE = BUILDER
            .comment("Colony-defense revive radius: a colony NPC that dies within this 3D distance of any "
                    + "building of its colony is revived immediately at the town hall door (reuses the "
                    + "all-dead fallback revive), instead of requiring an altar ritual.")
            .defineInRange("revive.nearBuildingRange", 20, 1, 64);

    // ---- Colony autonomy ----

    public static final ModConfigSpec.BooleanValue AUTO_APPROVE_TASKS = BUILDER
            .comment("When true, all colony tasks skip the player-approval gate and are assigned automatically.")
            .comment("Disable to review large build/reconstruction tasks before NPCs start work.")
            .define("general.autoApproveTasks", false);

    public static final ModConfigSpec.DoubleValue COLONY_OFFLINE_INCOME_MULTIPLIER = BUILDER
            .comment("创始人不在线时小镇的离线收益系数（0~1）：商店利润、服务设施元素产出、殖民地经验获取 × 该系数。"
                    + "默认 0.2 = 离线收益降为 20%（1.0 = 离线与在线同收益）。"
                    + "只打折收入侧：物品售价不变（商店按成本+折减利润入账，永不亏损），"
                    + "NPC 建造/商店补货的元素消耗照常 100%。"
                    + "设为 0 = 玩家不在线时其小镇整体冻结（NPC 建造/生产、游客经济、每日结算暂停，上线恢复）。"
                    + "无创始人的小镇视为始终满收益。")
            .defineInRange("colony.offlineIncomeMultiplier", 0.2, 0.0, 1.0);

    public static final ModConfigSpec.IntValue INITIAL_ELEMENT_COUNT = BUILDER
            .comment("每种元素在小镇仓库首次建立时的初始数量（每小镇一次，只种一次）。")
            .defineInRange("colony.initialElementCount", 6000, 0, 1000000);

    // ---- Element system ----

    public static final ModConfigSpec.DoubleValue ELEMENT_DECOMPOSE_DIVISOR = BUILDER
            .comment("Workstation 分解产出除数：分解物品返回其映射元素值的 1/N。"
                    + "默认 5 = 1/5（原为硬编码 1/10，回收率偏低）。")
            .defineInRange("element.decomposeDivisor", 5.0, 1.0, 100.0);

    public static final ModConfigSpec.DoubleValue ELEMENT_CRAFT_COST_MULTIPLIER = BUILDER
            .comment("合成/制作消耗倍率：Workstation 合成、法杖制作、酿造消耗的元素 × 该系数。"
                    + "默认 1.0；设为 2.0 则消耗翻倍（消耗向上取整，不会少扣）。"
                    + "警告：修改会导致利润率低于该数值的商店不盈利。")
            .defineInRange("element.craftCostMultiplier", 1.0, 0.1, 10.0);

    // ---- Tourist system ----

    public static final ModConfigSpec.IntValue TOURIST_MAX_PER_COLONY = BUILDER
            .comment("Maximum simultaneous tourists per colony")
            .defineInRange("tourist.maxPerColony", 150, 5, 500);

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
            .comment("Experience granted when tourist level == colony level (满条离场时)。"
                    + "700/1400（上调自 250/500）+ 低于小镇等级给一半 + expToNext 二次曲线，"
                    + "标定：5级≈5天、10级≈12天、15级≈22天、20级≈34天、30级满≈68天（sim 保守口径）。")
            .defineInRange("colony.expEqualLevel", 700, 0, 10000);

    public static final ModConfigSpec.IntValue COLONY_EXP_ABOVE_LEVEL = BUILDER
            .comment("Experience granted when tourist level > colony level")
            .defineInRange("colony.expAboveLevel", 1400, 0, 10000);

    public static final ModConfigSpec.IntValue COLONY_MAX_LEVEL = BUILDER
            .comment("城镇等级上限：达到后不再累积经验、不再升级")
            .defineInRange("colony.maxLevel", 30, 1, 100);

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
                    + "Wallet = baseWallet + level × walletPerLevel; travelFund = 3×wallet（总消费上限 ≈ 4×钱包）。"
                    + "参考: 1 级游客总消费 ≈ 3000，20 级 ≈ 22000（外部 sim 标定，防元素产出泛滥）。"
                    + "参考物价: 面包 ~16, 蛋糕 ~750, 金苹果 ~2684.")
            .defineInRange("tourist.baseWallet", 500, 0, 1000000);

    public static final ModConfigSpec.IntValue TOURIST_WALLET_PER_LEVEL = BUILDER
            .comment("Additional universal-element wallet per tourist level. "
                    + "Wallet = baseWallet + level × walletPerLevel.")
            .defineInRange("tourist.walletPerLevel", 200, 0, 1000000);

    // ── 游客经济改造：三条需求条 / 精力循环 / 停留 / 视野 / ATM（Block 0 新增）──


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
            .comment("游客每级需求增量。默认 10（原 20）——需求增长放缓使高级游客可被喂满，"
                    + "配合经验上调达到 5级≈5天、10级≈10天、20级≈30-40天、30级满≈60-80天。")
            .defineInRange("tourist.needPerLevel", 10, 0, 500);

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

    public static final ModConfigSpec.IntValue GUARD_FOLLOW_ATTACK_DURATION_TICKS = BUILDER
            .comment("NPC follow-combat memory (ticks): how long a follow-mode NPC keeps pursuing the mob its "
                    + "follower player attacked before giving up, unless the player attacks it again (300 = 15s). "
                    + "Pursuit range reuses guard.hateRange.")
            .defineInRange("guard.followAttackDurationTicks", 300, 20, 72000);

    public static final ModConfigSpec.DoubleValue GUARD_KITE_START_DIST = BUILDER
            .comment("Combat kiting trigger distance (blocks, horizontal): the NPC starts backing away once a "
                    + "visible enemy gets within this distance. 9 keeps a margin past creeper lethal blast (~4, "
                    + "charged ~8) and melee reach (~3) so the NPC isn't caught in the blast during recheck gaps.")
            .defineInRange("guard.kiteStartDist", 9.0, 2.0, 32.0);

    public static final ModConfigSpec.DoubleValue GUARD_KITE_STANDOFF = BUILDER
            .comment("Combat kiting standoff (blocks): the NPC backs away to this distance from the threat point. "
                    + "13 is 1 block past the normal-creeper safe radius (~8); beam range 200 keeps output up.")
            .defineInRange("guard.kiteStandoff", 13.0, 3.0, 64.0);

    public static final ModConfigSpec.IntValue GUARD_SWAY_FLIP_TICKS = BUILDER
            .comment("Lateral sway direction random-check interval (ticks): at the kiting standoff with a "
                    + "visible target and no active beam, the NPC strafes sideways like a vanilla skeleton "
                    + "instead of standing still. On this cadence it rolls whether to switch sides (35%) and "
                    + "re-rolls its movement magnitude, so the weave is organic and non-pendulum.")
            .defineInRange("guard.swayFlipTicks", 20, 10, 200);

    public static final ModConfigSpec.DoubleValue GUARD_ENGAGE_STANDOFF = BUILDER
            .comment("Approach landing distance (blocks) when LOS is blocked: the NPC pathfinds to a standable, "
                    + "LOS-visible spot this far from the target instead of walking into melee/creeper range. "
                    + "Kept >= kiteStartDist so the approach doesn't immediately re-trigger kiting.")
            .defineInRange("guard.engageStandoff", 9.0, 2.0, 32.0);

    // ── scepter: 玩家权杖（庇护/敌对）──
    public static final ModConfigSpec.DoubleValue SCEPTER_HOSTILE_RANGE = BUILDER
            .comment("Hostile-wand forced-target range (blocks): when a player marks a creature with the "
                    + "hostile wand, every colony mage within this distance of that creature is forced to "
                    + "prioritize attacking it until the mark is cleared or it dies.")
            .defineInRange("scepter.hostileRange", 128.0, 16.0, 512.0);

    public static final ModConfigSpec.DoubleValue GUARD_FLEE_HP_THRESHOLD = BUILDER
            .comment("Low-HP flee threshold (0-1 hp ratio): below this the NPC enters a flee state with larger "
                    + "kiting distances (fleeStartDist/fleeStandoff) and stops walking toward LOS-blocked "
                    + "targets, prioritizing survival. Runs as an L0 override before player spell strategy.")
            .defineInRange("guard.fleeHpThreshold", 0.30, 0.05, 1.0);

    public static final ModConfigSpec.DoubleValue GUARD_FLEE_START_DIST = BUILDER
            .comment("Flee-state kiting trigger distance (blocks): the fleeing NPC starts backing away once an "
                    + "enemy gets within this distance.")
            .defineInRange("guard.fleeStartDist", 12.0, 3.0, 64.0);

    public static final ModConfigSpec.DoubleValue GUARD_FLEE_STANDOFF = BUILDER
            .comment("Flee-state standoff (blocks): the fleeing NPC backs away to this distance from the threat. "
                    + "18 is far beyond even a charged creeper's lethal blast (~8).")
            .defineInRange("guard.fleeStandoff", 18.0, 4.0, 64.0);

    // ---- Raid (袭击) system ----

    public static final ModConfigSpec.IntValue RAID_TRIGGER_RANGE = BUILDER
            .comment("Raid trigger radius: a player carrying Bad Omen (RAID_OMEN/BAD_OMEN) within this horizontal "
                    + "X/Z expansion of a building's AABB (Y unchanged) starts a raid centered on the "
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

    // ---- Building no-spawn zone (建筑防刷怪区) ----

    public static final ModConfigSpec.BooleanValue BUILDING_NO_SPAWN_IN_AREA = BUILDER
            .comment("No natural mob spawns inside intact building bounding boxes.")
            .define("building.noSpawnInBuildingArea", true);

    static final ModConfigSpec SPEC = BUILDER.build();
}
