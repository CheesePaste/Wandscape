package com.wsteam.wandscape.shared.registry;

public final class WandscapeConstants {
    private WandscapeConstants() {}

    public static final int SCHEDULER_HEARTBEAT_TICKS = 40;

    public static final int DEFAULT_NPC_MAX_HEALTH = 40;
    public static final int DEFAULT_NPC_MAX_MANA = 100;
    public static final int DEFAULT_NPC_SPELL_POWER = 1;
    public static final int DEFAULT_NPC_MANA_REGEN = 2;

    public static final float HOUSE_MANA_REGEN_MULTIPLIER = 3.0f;

    public static final float DEFAULT_MANA_COST_MULTIPLIER = 1.0f;
    public static final int DEFAULT_WAND_RANGE = 1;

    public static final double SAME_BUILDING_CONTINUATION_BONUS = 50.0;

    public static final int QUEUE_TOWNHALL = 5;
    public static final int QUEUE_WORKSTATION = 60;
    public static final int QUEUE_CRAFTING = 60;
    public static final int QUEUE_POTION = 10;
    public static final int QUEUE_RITUAL_ALTAR = 10;
    public static final int QUEUE_NODE = 10;
    public static final int QUEUE_HOUSE = 5;
    public static final int QUEUE_MANA_POOL = 10;
    public static final int QUEUE_TAVERN = 5;

    public static final int WORKSTATION_CRAFT_TICKS = 1200;
    public static final int WORKSTATION_DECOMPOSE_TICKS = 1200;

    public static final int BASE_OPERATION_RANGE = 16;
    public static final int PER_WAND_LEVEL_RANGE = 8;

    public static final int DEFAULT_COLONY_RADIUS = 128;

    public static final int NPC_WALK_THRESHOLD = 64;

    public static final int STUCK_CHECK_INTERVAL_TICKS = 60;
    public static final double STUCK_MIN_MOVE_DISTANCE = 2.0;
    public static final int STUCK_MAX_RETRIES = 3;
}
