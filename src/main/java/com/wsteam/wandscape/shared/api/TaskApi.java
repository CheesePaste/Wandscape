package com.wsteam.wandscape.shared.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.wsteam.wandscape.shared.data.BlueprintInfo;

/**
 * @deprecated Use {@link com.wsteam.wandscape.core.task.GlobalTaskPool} and
 *             {@link com.wsteam.wandscape.core.system.PlayerManualSource} directly.
 *             This interface will be removed in a future cleanup.
 */
@Deprecated
public interface TaskApi {

    /** Get all available blueprints for the task editor GUI. */
    List<BlueprintInfo> getAvailableBlueprints();

    /**
     * Publish a task from a blueprint id + raw params.
     * Used by the task editor GUI (client → server via network packet).
     *
     * @return the created task id
     */
    UUID publishTask(String blueprintId, Map<String, JsonElement> params, int priority);
}
