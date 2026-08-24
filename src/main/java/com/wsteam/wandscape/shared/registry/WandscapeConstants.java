package com.wsteam.wandscape.shared.registry;

public final class WandscapeConstants {
    private WandscapeConstants() {}

    public static final String BUILDING_CATEGORY_GOVERNMENT = "government";

    public static final double SAME_BUILDING_CONTINUATION_BONUS = 50.0;

    public static final int QUEUE_TOWNHALL = 5;
    public static final int QUEUE_WORKSTATION = 60;
    public static final int QUEUE_CRAFTING = 60;
    public static final int QUEUE_MAGIC = 10;
    public static final int QUEUE_RITUAL_ALTAR = 10;
    public static final int QUEUE_NODE = 10;
    public static final int QUEUE_HOUSE = 5;
    public static final int QUEUE_TAVERN = 5;

    /** 建筑任务队列优先级（高→低）：玩家手动发布 > 商店补货 > 自动补产/采集。 */
    public static final int TASK_PRIORITY_PLAYER = 80;
    public static final int TASK_PRIORITY_RESTOCK = 60;
    public static final int TASK_PRIORITY_AUTO = 40;

    public static final int WORKSTATION_CRAFT_TICKS_PER_UNIT = 5;
    public static final int CRAFTING_STATION_CRAFT_TICKS_PER_UNIT = 1200;

    /** 每方块建造耗时（建筑CD），与引擎 AsyncTransformExecutor 的 1 tick/块一致。 */
    public static final int CONSTRUCTION_PLACE_TICKS_PER_UNIT = 1;

    public static final int BASE_OPERATION_RANGE = 16;
    public static final int PER_WAND_LEVEL_RANGE = 8;

    public static final int DEFAULT_COLONY_RADIUS = 128;

    public static final int NPC_WALK_THRESHOLD = 64;

    /** 酒馆「招募 NPC」自第二次起每种元素的价格。 */
    public static final long TAVERN_RECRUIT_COST_PER_ELEMENT = 10_000;

    /** 法师小屋「升级法师」/「训练属性」每种元素的价格。 */
    public static final long MAGE_HUT_COST_PER_ELEMENT = 1_000;

    /** 法师小屋休息时长（tick，2 分钟 = 2400）。 */
    public static final int MAGE_HUT_REST_TICKS = 2400;

    public static final int STUCK_CHECK_INTERVAL_TICKS = 60;
    public static final double STUCK_MIN_MOVE_DISTANCE = 2.0;
    public static final int STUCK_MAX_RETRIES = 3;

    /** 游客精力上限：初始 100、清晨晨起回满 100，耗尽(=0)只能去 relax 恢复建筑。 */
    public static final int TOURIST_MAX_ENERGY = 100;
}
