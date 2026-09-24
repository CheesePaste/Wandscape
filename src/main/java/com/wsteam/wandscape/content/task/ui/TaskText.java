package com.wsteam.wandscape.content.task.ui;

import com.wsteam.wandscape.content.task.network.MageSummaryDto;
import com.wsteam.wandscape.content.task.network.ProductionItemDto;
import com.wsteam.wandscape.content.task.network.ResourceShortageDto;
import com.wsteam.wandscape.content.task.network.TaskSummaryDto;
import com.wsteam.wandscape.foundation.ui.I18n;

/**
 * 任务面板与 NPC 头顶状态里那些**服务端发来的文本**的客户端解析。
 *
 * <p>服务端不知道玩家语言，所以 DTO 里这些字段发的是 lang 键或原文（数据包裸 id、物品名、
 * 作者写死的标签）。这里统一按客户端语言过一遍：是键就吃语言文件，不是键原样透传——
 * 同一段代码既接得住模组自己发的键，也接得住数据包作者写的原文。
 */
public final class TaskText {

    /** 序列标签的位置后缀，由 {@code BlueprintDefaults#label} 拼出；位置本身与语言无关，原样接回。 */
    private static final String LABEL_POS = " at (";

    private TaskText() {}

    /** 键 → 本地语言；非键（裸 id、名字）原样返回。 */
    public static String resolve(String keyOrText) {
        return (keyOrText == null || keyOrText.isEmpty()) ? "" : I18n.string(keyOrText, keyOrText);
    }

    /** 序列标签：{@code <键> at (<x>, <y>, <z>)}。 */
    public static String sequenceLabel(String label) {
        if (label == null || label.isEmpty()) return "";
        int at = label.indexOf(LABEL_POS);
        if (at < 0) return resolve(label);
        return resolve(label.substring(0, at)) + label.substring(at);
    }

    /** 任务标题：序列标签 → 建筑名 +「任务」→ 蓝图 id → 未知任务 #id。 */
    public static String taskTitle(TaskSummaryDto task) {
        String label = sequenceLabel(task.title());
        if (!label.isEmpty()) return label;
        if (task.buildingId() != null && !task.buildingTypeId().isEmpty()) {
            return I18n.string("gui.wandscape.task.title.building", "%s 任务",
                    I18n.buildingName(task.buildingTypeId(), task.buildingName()).getString());
        }
        return I18n.string("gui.wandscape.task.title.unknown", "未知任务 #%s", String.valueOf(task.taskId()));
    }

    /** 法师名册里的当前任务：同一张任务卡，标题回任务列表取（只有那里带建筑类型 id）。 */
    public static String mageTaskTitle(MageSummaryDto mage) {
        if (mage.currentTaskTitle().isEmpty()) return "";
        for (TaskSummaryDto task : TaskManagementClientState.getAllTasks()) {
            if (task.taskId() == mage.currentTaskId()) return taskTitle(task);
        }
        return sequenceLabel(mage.currentTaskTitle());
    }

    /**
     * 生产项名：物品 id（带命名空间）用服务端解析好的物品名，元素产出走元素键，
     * 连物品与蓝图 id 都没有时才是「未知生产项」（服务端那串中文同样只是兜底）。
     */
    public static String productionItemName(ProductionItemDto item) {
        String id = item.itemOrRecipeId();
        if (id == null || id.isEmpty()) {
            return hasText(item.blueprintId())
                    ? item.displayName()
                    : I18n.string("gui.wandscape.task.prod.unknown_item", item.displayName());
        }
        return id.indexOf(':') < 0 ? I18n.string("element.wandscape." + id, item.displayName()) : item.displayName();
    }

    /** 缺料条目的名字：元素走元素键，其余用服务端给的兜底名（物品名自带语言）。 */
    public static String shortageName(ResourceShortageDto shortage) {
        return "element".equals(shortage.kind())
                ? I18n.string("element.wandscape." + shortage.resourceId(), shortage.displayName())
                : shortage.displayName();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }
}
