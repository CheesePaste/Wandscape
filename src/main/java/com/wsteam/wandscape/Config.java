package com.wsteam.wandscape;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue COLONY_RADIUS = BUILDER
            .comment("Default colony radius in blocks")
            .defineInRange("general.colonyRadius", 128, 16, 512);

    public static final ModConfigSpec.IntValue WAREHOUSE_SAVE_INTERVAL_MINUTES = BUILDER
            .comment("Interval in minutes between warehouse auto-saves")
            .defineInRange("general.warehouseSaveIntervalMinutes", 5, 1, 60);

    public static final ModConfigSpec.IntValue SCHEDULER_HEARTBEAT_TICKS = BUILDER
            .comment("Scheduler heartbeat interval in ticks (40 ticks = 2 seconds)")
            .defineInRange("scheduler.heartbeatTicks", 40, 10, 200);

    public static final ModConfigSpec.DoubleValue SAME_BUILDING_CONTINUATION_BONUS = BUILDER
            .comment("Score bonus for NPC continuing tasks on the same building")
            .defineInRange("scheduler.sameBuildingContinuationBonus", 50.0, 0.0, 500.0);

    public static final ModConfigSpec.IntValue TASK_INTERRUPT_COOLDOWN_TICKS = BUILDER
            .comment("Cooldown in ticks before an interrupted task can be re-assigned (6000 ticks = 5 minutes)")
            .defineInRange("scheduler.taskInterruptCooldownTicks", 6000, 0, 72000);

    public static final ModConfigSpec.IntValue STUCK_CHECK_INTERVAL_TICKS = BUILDER
            .comment("Interval in ticks between NPC stuck checks (60 ticks = 3 seconds)")
            .defineInRange("scheduler.stuckCheckIntervalTicks", 60, 20, 200);

    public static final ModConfigSpec.DoubleValue STUCK_MIN_MOVE_DISTANCE = BUILDER
            .comment("Minimum distance an NPC must move within stuck check interval to not be considered stuck")
            .defineInRange("scheduler.stuckMinMoveDistance", 2.0, 0.5, 10.0);

    public static final ModConfigSpec.IntValue STUCK_MAX_RETRIES = BUILDER
            .comment("Number of consecutive stuck checks before NPC teleports")
            .defineInRange("scheduler.stuckMaxRetries", 3, 1, 10);

    public static final ModConfigSpec.IntValue DEFAULT_NPC_MAX_HEALTH = BUILDER
            .comment("Default NPC max health")
            .defineInRange("npc.defaultMaxHealth", 40, 10, 200);

    public static final ModConfigSpec.IntValue DEFAULT_NPC_MAX_MANA = BUILDER
            .comment("Default NPC max mana")
            .defineInRange("npc.defaultMaxMana", 100, 20, 500);

    public static final ModConfigSpec.IntValue DEFAULT_NPC_SPELL_POWER = BUILDER
            .comment("Default NPC spell power")
            .defineInRange("npc.defaultSpellPower", 1, 1, 10);

    public static final ModConfigSpec.IntValue DEFAULT_NPC_MANA_REGEN = BUILDER
            .comment("Default NPC mana regen per tick")
            .defineInRange("npc.defaultManaRegen", 2, 0, 20);

    public static final ModConfigSpec.DoubleValue HOUSE_MANA_REGEN_MULTIPLIER = BUILDER
            .comment("Mana regen multiplier when NPC is in assigned house")
            .defineInRange("npc.houseManaRegenMultiplier", 3.0, 1.0, 10.0);

    public static final ModConfigSpec.IntValue NPC_WALK_THRESHOLD = BUILDER
            .comment("Max distance in blocks for NPC pathfinding; beyond this they teleport")
            .defineInRange("npc.walkThreshold", 64, 16, 256);

    public static final ModConfigSpec.IntValue BASE_OPERATION_RANGE = BUILDER
            .comment("Base operation range for wand operations")
            .defineInRange("wand.baseOperationRange", 16, 4, 64);

    public static final ModConfigSpec.IntValue PER_WAND_LEVEL_RANGE = BUILDER
            .comment("Additional range per wand level")
            .defineInRange("wand.perWandLevelRange", 8, 0, 32);

    public static final ModConfigSpec.DoubleValue DEFAULT_MANA_COST_MULTIPLIER = BUILDER
            .comment("Default mana cost multiplier for wands (lower = cheaper)")
            .defineInRange("wand.defaultManaCostMultiplier", 1.0, 0.3, 1.0);

    public static final ModConfigSpec.IntValue DEFAULT_WAND_RANGE = BUILDER
            .comment("Default wand range")
            .defineInRange("wand.defaultWandRange", 1, 1, 5);

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

    // ── Soul Projection ──

    public static final ModConfigSpec.DoubleValue PROJECTION_FLYING_SPEED = BUILDER
            .comment("Flying speed multiplier in projection mode (vanilla creative flight = 0.05)")
            .defineInRange("projection.flyingSpeed", 0.15, 0.05, 1.0);

    public static final ModConfigSpec.IntValue PROJECTION_MAX_RANGE = BUILDER
            .comment("Maximum distance in blocks from body anchor before player is pulled back (0 = unlimited)")
            .defineInRange("projection.maxRange", 256, 0, 1024);

    public static final ModConfigSpec.BooleanValue PROJECTION_REQUIRE_WAND = BUILDER
            .comment("Whether the player must hold a wand item to enter projection mode")
            .define("projection.requireWand", true);

    // ---- Tourist system ----

    public static final ModConfigSpec.IntValue TOURIST_SPAWN_INTERVAL_TICKS = BUILDER
            .comment("Interval in ticks between tourist spawn attempts (24000 ticks = 1 MC day)")
            .defineInRange("tourist.spawnIntervalTicks", 24000, 6000, 72000);

    public static final ModConfigSpec.IntValue TOURIST_MAX_PER_COLONY = BUILDER
            .comment("Maximum simultaneous tourists per colony")
            .defineInRange("tourist.maxPerColony", 20, 5, 100);

    public static final ModConfigSpec.IntValue TOURIST_SATISFACTION_THRESHOLD = BUILDER
            .comment("Satisfaction value at which a tourist leaves satisfied (100 = fully satisfied)")
            .defineInRange("tourist.satisfactionThreshold", 80, 10, 100);

    public static final ModConfigSpec.IntValue TOURIST_DESPAWN_TIMEOUT_TICKS = BUILDER
            .comment("Ticks before an idle tourist despawns (36000 ticks = 30 minutes)")
            .defineInRange("tourist.despawnTimeoutTicks", 36000, 12000, 72000);

    public static final ModConfigSpec.IntValue TOURIST_BASE_SPAWN_COUNT = BUILDER
            .comment("Base number of tourists spawned each morning")
            .defineInRange("tourist.baseSpawnCount", 3, 1, 20);

    public static final ModConfigSpec.IntValue TOURIST_EVAL_SCORE_DIVISOR = BUILDER
            .comment("Colony three-value total divided by this gives extra tourists")
            .defineInRange("tourist.evalScoreDivisor", 10, 1, 100);

    // ---- Decoration system ----

    public static final ModConfigSpec.DoubleValue DECORATION_BONUS_CAP = BUILDER
            .comment("Max decoration bonus as fraction of a building's base stat (1.0 = 100%)")
            .defineInRange("decoration.bonusCap", 1.0, 0.0, 5.0);

    public static final ModConfigSpec.IntValue DECORATION_SCAN_INTERVAL_TICKS = BUILDER
            .comment("Interval in ticks between decoration radiation recalculations")
            .defineInRange("decoration.scanIntervalTicks", 200, 40, 1200);

    // ---- Maintenance system ----

    public static final ModConfigSpec.IntValue MAINTENANCE_GRACE_PERIOD_TICKS = BUILDER
            .comment("Grace period in ticks after building placement before maintenance costs begin")
            .defineInRange("maintenance.gracePeriodTicks", 24000, 0, 240000);

    public static final ModConfigSpec.IntValue MAINTENANCE_HEARTBEAT_TICKS = BUILDER
            .comment("Interval in ticks between maintenance system heartbeat scans")
            .defineInRange("maintenance.heartbeatTicks", 1200, 200, 72000);

    // ---- Shop system ----

    public static final ModConfigSpec.IntValue SHOP_RESTOCH_INTERVAL_TICKS = BUILDER
            .comment("Interval in ticks between shop restock cycles (24000 ticks = 1 MC day)")
            .defineInRange("shop.restockIntervalTicks", 24000, 6000, 72000);

    public static final ModConfigSpec.BooleanValue SHOP_CLEAR_UNSOLD_ON_RESTOCH = BUILDER
            .comment("Whether unsold goods are cleared when shops restock")
            .define("shop.clearUnsoldOnRestock", true);

    // ---- Service system ----

    public static final ModConfigSpec.IntValue SERVICE_COOLDOWN_TICKS = BUILDER
            .comment("Cooldown in ticks before a tourist can use the same service building again")
            .defineInRange("service.cooldownTicks", 6000, 1200, 72000);

    // ---- Hotel system ----

    public static final ModConfigSpec.IntValue HOTEL_ENERGY_PER_TICK = BUILDER
            .comment("Energy recovered per tick while a tourist is checked into a hotel")
            .defineInRange("hotel.energyPerTick", 1, 0, 10);

    public static final ModConfigSpec.IntValue HOTEL_SATISFACTION_PER_NIGHT = BUILDER
            .comment("Satisfaction gained for a full night's stay at a hotel")
            .defineInRange("hotel.satisfactionPerNight", 30, 0, 100);

    public static final ModConfigSpec.IntValue TOURIST_SATISFACTION_REWARD_DIVISOR = BUILDER
            .comment("Reserved: tourist satisfaction divided by this yields colony element reward on departure (future use)")
            .defineInRange("tourist.satisfactionRewardDivisor", 10, 1, 100);

    public static final ModConfigSpec.IntValue TOURIST_LEVEL_SATISFACTION_THRESHOLD = BUILDER
            .comment("Per-level three-value threshold. A building's three-value sum must be "
                    + ">= tourist.level × this to grant any satisfaction. Below = 0 gain.")
            .defineInRange("tourist.levelSatisfactionThreshold", 3, 1, 10);

    public static final ModConfigSpec.IntValue TOURIST_MAX_SATISFACTION_PER_VISIT = BUILDER
            .comment("Maximum satisfaction a tourist can gain from a single building visit")
            .defineInRange("tourist.maxSatisfactionPerVisit", 25, 10, 50);

    public static final ModConfigSpec.IntValue TOURIST_PREFERENCE_DECAY = BUILDER
            .comment("How much a tourist's preference for a building type decreases "
                    + "after visiting it.")
            .defineInRange("tourist.preferenceDecay", 15, 0, 30);

    static final ModConfigSpec SPEC = BUILDER.build();
}
