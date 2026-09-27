package com.wsteam.wandscape.content.task.boundary;

import com.wsteam.wandscape.content.task.types.GridPos;
import com.wsteam.wandscape.content.task.types.ResourceId;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Core-layer boundary for handling resource shortages with module-specific strategies.
 *
 * <p>Called by {@code GlobalTaskPool#markAwaitingResources} — i.e. at the moment a task
 * first reports it cannot get a resource — before falling back to the default
 * {@code gather:<resource>} task. An engine-layer implementation can check synthesize
 * recipes and enqueue a {@code production:synthesize} task instead.
 *
 * @return true if the shortage was handled (e.g. synthesize task enqueued),
 *         false to fall through to the default gather behavior
 */
@FunctionalInterface
public interface ResourceShortageHandler {

    /**
     * Who is asking for the resource. The strategy needs it to tell a construction
     * site's material call apart from every other shortage: 工地缺料不走通用自动补产，
     * 由建筑域按「放下时补一次 + 面板一键制作」自己管。
     */
    record Context(@Nullable String blueprintId, @Nullable UUID buildingId) {}

    boolean handle(Context context, ResourceId resource, int amount, GridPos location);
}
