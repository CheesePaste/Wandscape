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

    /** 建筑任务队列优先级（高→低）：玩家手动发布 > 商店补货 > 自动补产/采集。 */
    public static final int TASK_PRIORITY_PLAYER = 80;
    public static final int TASK_PRIORITY_RESTOCK = 60;
    public static final int TASK_PRIORITY_AUTO = 40;

    public static final int NPC_WALK_THRESHOLD = 64;
    /** 酒馆「招募 NPC」自第二次起每种元素的价格。 */
    public static final long TAVERN_RECRUIT_COST_PER_ELEMENT = 10_000;

    public static final int STUCK_CHECK_INTERVAL_TICKS = 60;
    public static final double STUCK_MIN_MOVE_DISTANCE = 2.0;
    public static final int STUCK_MAX_RETRIES = 3;

    /** 游客精力上限：初始 100、清晨晨起回满 100，耗尽(=0)只能去 relax 恢复建筑。 */
    public static final int TOURIST_MAX_ENERGY = 100;
}
