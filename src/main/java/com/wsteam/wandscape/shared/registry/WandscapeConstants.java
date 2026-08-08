package com.wsteam.wandscape.shared.registry;

public final class WandscapeConstants {
    private WandscapeConstants() {}

    public static final String BUILDING_CATEGORY_GOVERNMENT = "government";

    public static final int SCHEDULER_HEARTBEAT_TICKS = 40;

    public static final double SAME_BUILDING_CONTINUATION_BONUS = 50.0;

    public static final int QUEUE_TOWNHALL = 5;
    public static final int QUEUE_WORKSTATION = 60;
    public static final int QUEUE_CRAFTING = 60;
    public static final int QUEUE_POTION = 10;
    public static final int QUEUE_RITUAL_ALTAR = 10;
    public static final int QUEUE_NODE = 10;
    public static final int QUEUE_HOUSE = 5;
    public static final int QUEUE_TAVERN = 5;

    public static final int WORKSTATION_CRAFT_TICKS_PER_UNIT = 10;
    public static final int CRAFTING_STATION_CRAFT_TICKS_PER_UNIT = 1200;

    public static final int BASE_OPERATION_RANGE = 16;
    public static final int PER_WAND_LEVEL_RANGE = 8;

    public static final int DEFAULT_COLONY_RADIUS = 128;

    public static final int NPC_WALK_THRESHOLD = 64;

    /** 酒馆「招募 NPC」自第二次起每种元素的价格。 */
    public static final long TAVERN_RECRUIT_COST_PER_ELEMENT = 10_000;

    /** 分解产出 = 物品元素值 / 该除数（1/5，防物品复制）。 */
    public static final long DECOMPOSE_DIVISOR = 5;

    public static final int STUCK_CHECK_INTERVAL_TICKS = 60;
    public static final double STUCK_MIN_MOVE_DISTANCE = 2.0;
    public static final int STUCK_MAX_RETRIES = 3;
}
