package com.wsteam.wandscape.foundation.registry;
import com.wsteam.wandscape.content.task.boundary.AsyncTransformExecutor;

import java.util.Set;

public final class WandscapeConstants {
    private WandscapeConstants() {}

    public static final String BUILDING_CATEGORY_GOVERNMENT = "government";
    public static final String BUILDING_CATEGORY_STORAGE = "storage";
    public static final String BUILDING_CATEGORY_WORKSTATION = "workstation";

    /**
     * 受拆除保护的建筑类别：拆到 0 座会破坏殖民地运转（无市政厅无法定位小镇、
     * 无仓库资源落入死账户 UUID(0,0)、无工作站生产停摆），故只剩最后一座时禁止拆除。
     * 按类别保护而非按类型，未来新增同类建筑自动纳入。
     */
    public static final Set<String> PROTECTED_LAST_CATEGORIES = Set.of(
            BUILDING_CATEGORY_GOVERNMENT, BUILDING_CATEGORY_STORAGE, BUILDING_CATEGORY_WORKSTATION);

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

    /** 施法决策：单体攻击在敌数 ≤ 该值时优先放（敌多时交给群发）。 */
    public static final int CAST_SINGLE_TARGET_MAX_ENEMIES = 3;
    /** 施法决策：群体攻击在敌数 ≥ 该值时放（敌少时先用单发）。 */
    public static final int CAST_AOE_MIN_ENEMIES = 3;

    /** 酒馆「招募 NPC」自第二次起每种元素的价格。 */
    public static final long TAVERN_RECRUIT_COST_PER_ELEMENT = 10_000;

    /** 法师小屋休息时长（tick，2 分钟 = 2400）。 */
    public static final int MAGE_HUT_REST_TICKS = 2400;

    public static final int STUCK_CHECK_INTERVAL_TICKS = 60;
    public static final double STUCK_MIN_MOVE_DISTANCE = 2.0;
    public static final int STUCK_MAX_RETRIES = 3;

    /** 游客精力上限：初始 100、清晨晨起回满 100，耗尽(=0)只能去 relax 恢复建筑。 */
    public static final int TOURIST_MAX_ENERGY = 100;
}
