package com.wsteam.wandscape.task.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.task.BlueprintDefinition;
import com.wsteam.wandscape.core.task.BlueprintRegistry;
import com.wsteam.wandscape.core.task.GlobalTaskPool;
import com.wsteam.wandscape.core.system.PlayerManualSource;
import com.wsteam.wandscape.core.task.TaskRequest;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.source.blueprint.BlueprintConfigLoader;
import com.wsteam.wandscape.shared.api.TaskApi;
import com.wsteam.wandscape.shared.data.BlueprintInfo;
import com.wsteam.wandscape.shared.data.ParamTypeInfo;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

/**
 * Server-side implementation of {@link TaskApi}.
 *
 * <p>Wires {@link PlayerManualSource} and {@link BlueprintRegistry}
 * into the shared API layer. Registered into {@link WandscapeApis}
 * at server startup.
 *
 * @deprecated Use {@link GlobalTaskPool} and {@link PlayerManualSource} directly.
 */
@Deprecated
public class TaskApiImpl implements TaskApi {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final PlayerManualSource playerSource;
    private final BlueprintRegistry blueprintRegistry;

    public TaskApiImpl(PlayerManualSource playerSource, BlueprintRegistry blueprintRegistry) {
        this.playerSource = playerSource;
        this.blueprintRegistry = blueprintRegistry;
        WandscapeApis.setTaskApi(this);
    }

    private GlobalTaskPool getPool() {
        var world = WandscapeEngine.getWorld();
        return world != null ? world.taskPool : null;
    }

    @Override
    public List<BlueprintInfo> getAvailableBlueprints() {
        BlueprintConfigLoader loader = WandscapeEngine.getBlueprintConfigLoader();
        if (loader == null) {
            LOGGER.warn("[TaskApi] BlueprintConfigLoader not available");
            return List.of();
        }

        List<BlueprintInfo> result = new ArrayList<>();
        for (var entry : loader.getAll().entrySet()) {
            BlueprintDefinition def = entry.getValue();
            Map<String, ParamTypeInfo> paramInfos = new java.util.LinkedHashMap<>();
            if (def.params() != null) {
                for (var paramEntry : def.params().entrySet()) {
                    paramInfos.put(paramEntry.getKey(), ParamTypeInfo.fromCore(paramEntry.getValue()));
                }
            }
            String displayName = def.displayName() != null && !def.displayName().isEmpty()
                    ? def.displayName() : def.id();
            String description = def.description() != null ? def.description() : "";
            result.add(new BlueprintInfo(def.id(), displayName, description, paramInfos));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public UUID publishTask(String blueprintId, Map<String, JsonElement> params, int priority) {
        GlobalTaskPool pool = getPool();
        if (pool == null) {
            throw new IllegalStateException("Engine not bootstrapped — task pool unavailable");
        }

        if (blueprintId == null || blueprintId.isEmpty()) {
            throw new IllegalArgumentException("blueprintId must not be empty");
        }

        Map<String, JsonElement> safeParams = params != null ? params : Collections.emptyMap();
        long taskId = playerSource.publish(new TaskRequest(blueprintId, safeParams, priority));

        LOGGER.info("[TaskApi] published '{}' → task #{} (priority={}, params={})",
                blueprintId, taskId, priority, safeParams.size());

        return new UUID(taskId, 0);
    }
}
