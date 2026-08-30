package com.wsteam.wandscape.task.engine.pool;

import com.google.gson.JsonElement;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Request to create a global task from a blueprint.
 * Published by TaskSources and compiled by BlueprintRegistry.
 *
 * <p>Parameters are typed {@link JsonElement} values (string, int, pos array, list, map).
 *
 * <p><b>殖民地归属（必填参数）：</b>{@code colonyId} 是任务的殖民地所有者，所有任务来源
 * 构造时必须显式给出——任务属于哪个殖民地，就由哪个殖民地的 NPC 执行、从其仓库供料。
 * 没有殖民地上下文的任务（调试命令、无殖民地上下文的事件触发）显式传 {@code null}，
 * 表示"无主"：调度器仍可派给真实殖民地的 NPC，但绝不派给占位殖民地 NPC。
 * {@link GlobalTaskPool} 是 {@code colony_id} 参数的唯一写入点（由本字段归一化），
 * 避免各来源各自写 key 导致漂移。
 */
public record TaskRequest(
        String blueprintId,
        Map<String, JsonElement> params,
        int priority,
        @Nullable UUID colonyId
) {
    public TaskRequest {
        if (params == null) params = Collections.emptyMap();
    }
}
