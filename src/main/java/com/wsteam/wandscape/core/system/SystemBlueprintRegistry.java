package com.wsteam.wandscape.core.system;

import com.wsteam.wandscape.core.Log;
import com.wsteam.wandscape.core.TemplateResolver;
import com.wsteam.wandscape.core.boundary.EventBus;
import com.wsteam.wandscape.core.event.CustomEvent;
import com.wsteam.wandscape.core.task.*;

import java.util.*;

/**
 * Separate registry for system blueprints.
 * System blueprints:
 * <ul>
 *   <li>May have steps driven by {@link SystemBlueprintSystem} heartbeat</li>
 *   <li>Have <em>permanent</em> trigger subscriptions (never unsubscribed)</li>
 *   <li>Are not in the global task pool</li>
 * </ul>
 *
 * <p>Typical use: infrastructure events ({@code resource_low}, {@code task_awaiting_resource})
 * listened by {@code warehouse:monitor} which creates gather tasks.
 */
public class SystemBlueprintRegistry {

    private static final String TAG = "SysBlueprint";

    private final Map<String, Blueprint> blueprints = new LinkedHashMap<>();

    /** Register a system blueprint. */
    public void register(String id, Blueprint blueprint) {
        blueprints.put(id, blueprint);
    }

    /** Convenience: register a system blueprint with no triggers. */
    public void register(String id, BlueprintSteps steps) {
        blueprints.put(id, new Blueprint(id, steps));
    }

    public Blueprint get(String id) {
        return blueprints.get(id);
    }

    /** All registered system blueprints (insertion order). */
    public Collection<Blueprint> all() {
        return blueprints.values();
    }

    /**
     * Subscribe all permanent triggers to the event bus.
     * Called once at startup. These subscriptions are never removed.
     */
    public void subscribePermanentTriggers(EventBus eventBus, GlobalTaskPool taskPool) {
        for (Blueprint bp : blueprints.values()) {
            for (TriggerDeclaration trigger : bp.triggers()) {
                eventBus.subscribe(CustomEvent.class,
                        event -> onSystemTrigger(trigger, event, taskPool));
                Log.info(TAG, "permanent trigger: %s → %s (priority=%d)",
                        trigger.eventName(), trigger.sourceBlueprintId(), trigger.priority());
            }
        }
    }

    /** Handle a system trigger: filter → resolve → map → create task. */
    private void onSystemTrigger(TriggerDeclaration trigger, CustomEvent event,
                                  GlobalTaskPool taskPool) {
        if (!trigger.eventName().equals(event.name())) return;

        // Filter check
        if (!matchesFilter(trigger.paramFilter(), event.params())) return;

        // Resolve sourceBlueprintId template
        Map<String, String> templateVars = new HashMap<>();
        for (var entry : event.params().entrySet()) {
            templateVars.put("event." + entry.getKey(), entry.getValue());
        }
        String resolvedBpId = TemplateResolver.resolve(trigger.sourceBlueprintId(), templateVars);

        // Apply paramMapping
        Map<String, String> taskParams = applyMapping(trigger.paramMapping(), event.params());

        // Dedup check
        if (trigger.dedupKey() != null) {
            String dedupValue = event.params().get(trigger.dedupKey());
            if (dedupValue != null && isDuplicate(resolvedBpId, trigger.dedupKey(), dedupValue, taskPool)) {
                Log.debug(TAG, "trigger %s → dedup skip: %s=%s already in flight",
                        trigger.eventName(), trigger.dedupKey(), dedupValue);
                return;
            }
        }

        long newTaskId = taskPool.addTask(
                new TaskRequest(resolvedBpId, taskParams, trigger.priority()));
        Log.info(TAG, "system trigger %s → task #%d blueprint=%s",
                trigger.eventName(), newTaskId, resolvedBpId);
    }

    private static boolean matchesFilter(Map<String, String> filter, Map<String, String> eventParams) {
        if (filter.isEmpty()) return true;
        for (var entry : filter.entrySet()) {
            if (!entry.getValue().equals(eventParams.get(entry.getKey()))) return false;
        }
        return true;
    }

    private static Map<String, String> applyMapping(Map<String, String> mapping, Map<String, String> eventParams) {
        if (mapping.isEmpty()) return new HashMap<>(eventParams);
        Map<String, String> result = new HashMap<>();
        for (var entry : mapping.entrySet()) {
            String value = eventParams.get(entry.getKey());
            if (value != null) result.put(entry.getValue(), value);
        }
        return result;
    }

    private static boolean isDuplicate(String blueprintId, String dedupKey, String dedupValue,
                                        GlobalTaskPool taskPool) {
        String labelPrefix = resolvedToLabelPrefix(blueprintId);
        for (GlobalTask t : taskPool.all()) {
            if (t.state == TaskState.COMPLETED) continue;
            String label = t.sequence.label();
            boolean labelMatch = label.startsWith(labelPrefix) || label.equals(blueprintId);
            if (labelMatch) {
                if (label.equals(blueprintId) || label.contains(dedupValue)
                        || t.taskParams.containsValue(dedupValue)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String resolvedToLabelPrefix(String blueprintId) {
        String[] parts = blueprintId.split(":", 2);
        if (parts.length == 2) {
            return Character.toUpperCase(parts[0].charAt(0)) + parts[0].substring(1)
                    + " " + parts[1];
        }
        return blueprintId.substring(0, 1).toUpperCase() + blueprintId.substring(1);
    }
}
