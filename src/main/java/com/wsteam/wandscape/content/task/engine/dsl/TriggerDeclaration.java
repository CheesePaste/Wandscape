package com.wsteam.wandscape.content.task.engine.dsl;

import com.wsteam.wandscape.core.event.CustomEvent;

import java.util.Collections;
import java.util.Map;
/**
 * Producer-side trigger declaration.
 * When event X with matching params occurs, source blueprint Y as a new task.
 *
 * @param eventName         matches {@link CustomEvent#name}
 * @param paramFilter       subset match: event params must contain all these entries (null = match all)
 * @param sourceBlueprintId blueprint to compile, supports {@code {{event.<key>}}} template
 * @param priority          new task priority
 * @param paramMapping      key rename from event params to TaskRequest params (null = passthrough)
 * @param dedupKey          cross-tick dedup key (null = no dedup)
 */
public record TriggerDeclaration(
        String eventName,
        Map<String, String> paramFilter,
        String sourceBlueprintId,
        int priority,
        Map<String, String> paramMapping,
        String dedupKey
) {
    public TriggerDeclaration {
        if (paramFilter == null) paramFilter = Collections.emptyMap();
        if (paramMapping == null) paramMapping = Collections.emptyMap();
    }
}
