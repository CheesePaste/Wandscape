package com.wsteam.wandscape;

import net.neoforged.neoforge.common.ModConfigSpec;
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue COLONY_RADIUS = BUILDER
            .comment("Default colony radius in blocks")
            .defineInRange("general.colonyRadius", 128, 16, 512);

    public static final ModConfigSpec.IntValue SCHEDULER_HEARTBEAT_TICKS = BUILDER
            .comment("Scheduler heartbeat interval in ticks (40 ticks = 2 seconds)")
            .defineInRange("scheduler.heartbeatTicks", 40, 10, 200);

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

    // ---- Tourist system ----

    public static final ModConfigSpec.IntValue TOURIST_MAX_PER_COLONY = BUILDER
            .comment("Maximum simultaneous tourists per colony")
            .defineInRange("tourist.maxPerColony", 20, 5, 100);

    public static final ModConfigSpec.IntValue TOURIST_DESPAWN_TIMEOUT_TICKS = BUILDER
            .comment("Ticks before an idle tourist despawns (36000 ticks = 30 minutes)")
            .defineInRange("tourist.despawnTimeoutTicks", 36000, 12000, 72000);

    public static final ModConfigSpec.IntValue TOURIST_BASE_SPAWN_COUNT = BUILDER
            .comment("Base number of tourists spawned each morning")
            .defineInRange("tourist.baseSpawnCount", 6, 1, 50);

    public static final ModConfigSpec.IntValue TOURIST_LEVEL_SPAWN_BONUS = BUILDER
            .comment("Additional tourists per colony level")
            .defineInRange("tourist.levelSpawnBonus", 3, 1, 20);

    public static final ModConfigSpec.IntValue TOURIST_SPAWN_WINDOW_START = BUILDER
            .comment("Spawn window start (game time tick)")
            .defineInRange("tourist.spawnWindowStart", 1000, 0, 24000);

    public static final ModConfigSpec.IntValue TOURIST_SPAWN_WINDOW_END = BUILDER
            .comment("Spawn window end (game time tick) — no new spawns after this")
            .defineInRange("tourist.spawnWindowEnd", 13000, 0, 24000);

    public static final ModConfigSpec.IntValue TOURIST_DEPARTURE_WINDOW_START = BUILDER
            .comment("Night departure window start (game time tick)")
            .defineInRange("tourist.departureWindowStart", 18000, 0, 24000);

    public static final ModConfigSpec.IntValue TOURIST_DEPARTURE_WINDOW_END = BUILDER
            .comment("Night departure window end (game time tick)")
            .defineInRange("tourist.departureWindowEnd", 24000, 0, 24000);

    public static final ModConfigSpec.IntValue TOURIST_DEPARTURE_DELAY_MAX_TICKS = BUILDER
            .comment("Max random delay ticks before night departure (0-this)")
            .defineInRange("tourist.departureDelayMaxTicks", 1500, 0, 6000);

    public static final ModConfigSpec.IntValue COLONY_EXP_EQUAL_LEVEL = BUILDER
            .comment("Experience granted when tourist level == colony level (at 100% satisfaction)")
            .defineInRange("colony.expEqualLevel", 100, 0, 10000);

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

    // ---- Maintenance system ----

    public static final ModConfigSpec.IntValue MAINTENANCE_GRACE_PERIOD_TICKS = BUILDER
            .comment("Grace period in ticks after building placement before maintenance costs begin")
            .defineInRange("maintenance.gracePeriodTicks", 24000, 0, 240000);

    /** Daily settlement happens when timeOfDay ≤ this threshold. */
    public static final ModConfigSpec.IntValue SETTLEMENT_WINDOW_TICKS = BUILDER
            .comment("Settlement window: time-of-day ticks within which daily settlement triggers (0 = exact 0:00 only)")
            .defineInRange("maintenance.settlementWindowTicks", 10, 0, 100);

    public static final ModConfigSpec.IntValue MAINTENANCE_RESERVE_DAYS = BUILDER
            .comment("How many days of maintenance to keep as reserve. " +
                     "If reserves fall below this, the forecast system triggers proactive gathering.")
            .defineInRange("maintenance.reserveDays", 2, 1, 14);

    public static final ModConfigSpec.BooleanValue AUTO_RESTART_SHUTDOWN = BUILDER
            .comment("Whether to automatically restart maintenance-shutdown buildings when surplus elements become available")
            .define("maintenance.autoRestart", true);

    public static final ModConfigSpec.IntValue FORECAST_INTERVAL_TICKS = BUILDER
            .comment("Interval in ticks between maintenance forecast scans (6000 ticks = 1/4 MC day)")
            .defineInRange("maintenance.forecastIntervalTicks", 6000, 1200, 24000);

    // ---- ImGui developer tools ----

    public static final ModConfigSpec.BooleanValue IMGUI_ENABLED = BUILDER
            .comment("Enable ImGui developer tools (F12 debug GUI). Disable to prevent accidental F12 toggles.")
            .define("client.imguiEnabled", false);

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

    public static final ModConfigSpec.IntValue SERVICE_COOLDOWN_TICKS = BUILDER
            .comment("Cooldown in ticks before a tourist can use the same service building again")
            .defineInRange("service.cooldownTicks", 1200, 1200, 72000);

    public static final ModConfigSpec.IntValue TOURIST_LEVEL_SATISFACTION_THRESHOLD = BUILDER
            .comment("Per-level three-value threshold. A building's three-value sum must be "
                    + ">= tourist.level × this to grant any satisfaction. Below = 0 gain.")
            .defineInRange("tourist.levelSatisfactionThreshold", 3, 1, 10);

    public static final ModConfigSpec.IntValue TOURIST_MAX_SATISFACTION_PER_VISIT = BUILDER
            .comment("Maximum satisfaction a tourist can gain from a single building visit")
            .defineInRange("tourist.maxSatisfactionPerVisit", 30, 10, 50);

    public static final ModConfigSpec.IntValue TOURIST_PREFERENCE_DECAY = BUILDER
            .comment("How much a tourist's preference for a building type decreases "
                    + "after visiting it.")
            .defineInRange("tourist.preferenceDecay", 15, 0, 30);

    static final ModConfigSpec SPEC = BUILDER.build();
}
