package com.wsteam.wandscape.content.building.data;
import com.wsteam.wandscape.content.building.source.BuildingTaskSource;

import com.google.gson.JsonElement;

import java.util.Collections;
import java.util.Map;
/**
 * A queued work item inside a building's internal FIFO queue.
 * Contains enough information to construct a {@code TaskRequest} when
 * {@code BuildingTaskSource} polls the building.
 *
 * @param blueprintId the blueprint key (e.g. "build:stone_bricks")
 * @param params      positional and contextual parameters for blueprint generation
 *                    (typed {@link JsonElement} values — string, int, pos, list, map)
 * @param priority    scheduling priority (higher = sooner)
 * @param batchBuild  是否由建筑模板展开（唯一写入点是
 *                    {@link com.wsteam.wandscape.content.building.internal.EnqueueHelper#buildWorkItem}）。
 *                    只有这类任务能被拆成多法师批次：修复任务的参数来自世界扫描、生产任务的参数来自配方，
 *                    读档都无法从建筑 JSON 重建，拆了就会丢参数（见 {@code ConstructionBatches#isSplittable}）。
 */
public record WorkItem(
        String blueprintId,
        Map<String, JsonElement> params,
        int priority,
        boolean batchBuild
) {
    public WorkItem {
        if (params == null) params = Collections.emptyMap();
    }

    /** 普通任务（修复/生产/采集/拆除）：天然不参与模板拆批。 */
    public WorkItem(String blueprintId, Map<String, JsonElement> params, int priority) {
        this(blueprintId, params, priority, false);
    }

    public WorkItem(String blueprintId, int priority) {
        this(blueprintId, Collections.emptyMap(), priority, false);
    }
}
