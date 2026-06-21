package com.wsteam.wandscape;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue COLONY_RADIUS = BUILDER
            .comment("Default colony radius in blocks")
            .defineInRange("general.colonyRadius", 128, 16, 512);

    public static final ModConfigSpec.IntValue MAINTENANCE_INTERVAL_MINUTES = BUILDER
            .comment("Interval in minutes between maintenance cost deductions")
            .defineInRange("general.maintenanceIntervalMinutes", 20, 1, 120);

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

    static final ModConfigSpec SPEC = BUILDER.build();
}
