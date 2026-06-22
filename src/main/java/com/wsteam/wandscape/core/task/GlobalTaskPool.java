package com.wsteam.wandscape.core.task;

import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.core.Log;
import com.wsteam.wandscape.core.TemplateResolver;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.boundary.EventBus;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.event.CustomEvent;
import com.wsteam.wandscape.core.event.ResourceFulfilled;
import com.wsteam.wandscape.core.event.TaskAwaitingResources;
import com.wsteam.wandscape.core.event.TaskCompleted;
import com.wsteam.wandscape.core.types.GridPos;

import java.util.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/**
 * Central container for all global tasks. Manages task lifecycle and assignment.
 * Subscribes to ResourceFulfilledEvent to wake AWAITING_RESOURCES tasks.
 *
 * <p>Trigger lifecycle: when a task is assigned, its {@link TriggerDeclaration}s are
 * subscribed to the event bus. When the task completes, they are unsubscribed.
 */
public class GlobalTaskPool {

    private static final String TAG = "TaskPool";

    private final Map<Long, GlobalTask> tasks = new LinkedHashMap<>();
    private final EventBus eventBus;
    private final TaskCompiler compiler;
    private final ColonyResourceAccess colonyResources;
    private final boolean autoApprove;
    private long nextTaskId = 1;

    /** Called whenever the task pool is mutated (add/assign/complete/release). */
    @javax.annotation.Nullable
    public Runnable onChanged;

    public GlobalTaskPool(EventBus eventBus, TaskCompiler compiler, ColonyResourceAccess colonyResources, boolean autoApprove) {
        this.eventBus = eventBus;
        this.compiler = compiler;
        this.colonyResources = colonyResources;
        this.autoApprove = autoApprove;

        // Subscribe to resource fulfilled events to wake waiting tasks
        eventBus.subscribe(ResourceFulfilled.class, this::onResourceFulfilled);
    }

    private void notifyChanged() {
        if (onChanged != null) onChanged.run();
    }

    // ---- Task creation ----

    /** Add a task from a request. Automatically determines if approval is needed. */
    public long addTask(TaskRequest request) {
        CompiledBlueprint compiled = compiler.compile(request, null);
        TaskSequence seq = compiled.sequence();
        long id = nextTaskId++;

        // High-priority tasks (priority >= 50) normally need player approval.
        // When autoApprove is enabled, skip the gate and go straight to PENDING_ASSIGN.
        TaskState initialState;
        ApprovalInfo approval = null;
        if (!autoApprove && request.priority() >= 50) {
            initialState = TaskState.PENDING_APPROVAL;
            GridPos suggestedPos = parseGridPos(request.params());
            approval = new ApprovalInfo(suggestedPos, Long.MAX_VALUE, false);
        } else {
            initialState = TaskState.PENDING_ASSIGN;
        }

        GlobalTask task = new GlobalTask(id, seq, Collections.emptyMap(),
                request.priority(), compiled.triggers(), new ArrayList<>(),
                new HashMap<>(request.params()),
                initialState, 0, null, null,
                new ArrayDeque<>(), approval);
        task.blueprintId = request.blueprintId();
        tasks.put(id, task);
        notifyChanged();
        Log.info(TAG, "addTask #%d '%s' blueprint=%s state=%s priority=%d steps=%d triggers=%d",
                id, seq.label(), request.blueprintId(), initialState, request.priority(),
                seq.size(), compiled.triggers().size());
        return id;
    }

    /** Add a pre-built task directly (used by systems). Triggers are deep-copied. */
    public long addTask(GlobalTask task) {
        long id = nextTaskId++;
        // Don't copy subscriptions — the new task starts fresh
        GlobalTask t = new GlobalTask(id, task.sequence, task.requirements,
                task.priority, new ArrayList<>(task.triggers), new ArrayList<>(),
                new HashMap<>(task.taskParams),
                task.state, task.stepIndex, null,
                task.awaitingResource,
                task.interruptHistory != null ? new ArrayDeque<>(task.interruptHistory) : new ArrayDeque<>(),
                task.approval);
        tasks.put(id, t);
        notifyChanged();
        Log.info(TAG, "addTask #%d '%s' (pre-built) state=%s priority=%d triggers=%d",
                id, t.sequence.label(), t.state, t.priority, t.triggers.size());
        return id;
    }

    // ---- Approval ----

    public void approve(long taskId) {
        GlobalTask task = tasks.get(taskId);
        if (task != null && task.state == TaskState.PENDING_APPROVAL) {
            task.state = TaskState.PENDING_ASSIGN;
            notifyChanged();
            Log.info(TAG, "approve #%d '%s' → PENDING_ASSIGN", taskId, task.sequence.label());
        }
    }

    public void reject(long taskId) {
        GlobalTask task = tasks.get(taskId);
        if (task != null && task.state == TaskState.PENDING_APPROVAL) {
            task.state = TaskState.COMPLETED;
            notifyChanged();
            Log.info(TAG, "reject #%d '%s' → COMPLETED", taskId, task.sequence.label());
        }
    }

    // ---- Assignment ----

    /**
     * Assign a task to an NPC. Subscribes triggers, sets taskParams on executor.
     */
    public void assign(long taskId, long npcId, World world) {
        GlobalTask task = tasks.get(taskId);
        if (task == null) return;

        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        if (exec == null) return;

        task.state = TaskState.IN_PROGRESS;
        task.assignedNpcId = npcId;
        exec.globalTaskId = taskId;
        exec.currentSequence = task.sequence;
        exec.stepIndex = task.stepIndex;
        exec.state = ExecutorState.ACTIVE;

        // Copy original task params to executor for EmitEventOp template resolution
        exec.taskParams = new HashMap<>(task.taskParams);

        // Subscribe triggers (only on first assign — they persist across release/reassign)
        if (task.subscriptions.isEmpty()) {
            for (TriggerDeclaration trigger : task.triggers) {
                var sub = eventBus.subscribe(CustomEvent.class,
                        event -> onTriggerEvent(trigger, event));
                task.subscriptions.add(sub);
            }
        }

        Log.info(TAG, "assign #%d '%s' → NPC %d (step=%d/%d triggers=%d)",
                taskId, task.sequence.label(), npcId, exec.stepIndex,
                task.sequence.size(), task.triggers.size());
    }

    /** Release an NPC from its current task (e.g., resource waiting). */
    public void releaseNpc(long taskId, long npcId, World world) {
        GlobalTask task = tasks.get(taskId);
        if (task == null) return;

        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        if (exec != null && exec.globalTaskId != null && exec.globalTaskId == taskId) {
            exec.releaseGlobalTask();
        }
        task.assignedNpcId = null;
        Log.debug(TAG, "releaseNpc #%d ← NPC %d", taskId, npcId);
    }

    // ---- State transitions (called by TaskExecutionSystem) ----

    /** Called when a step completes and the task is done. */
    public void completeTask(long taskId, long npcId) {
        GlobalTask task = tasks.get(taskId);
        if (task == null) return;
        task.state = TaskState.COMPLETED;
        task.assignedNpcId = null;

        // Unsubscribe all triggers (deferred — handlers still fire for
        // events emitted in this tick before dispatch)
        for (var sub : task.subscriptions) {
            eventBus.unsubscribe(sub);
        }
        task.subscriptions.clear();

        eventBus.emit(new TaskCompleted(taskId, npcId));
        notifyChanged();
        Log.info(TAG, "complete #%d '%s' by NPC %d", taskId, task.sequence.label(), npcId);
    }

    /** Called when an op returns WAITING due to resource shortage. */
    public void markAwaitingResources(long taskId, long npcId,
                                       ResourceStack needed,
                                       World world) {
        GlobalTask task = tasks.get(taskId);
        if (task == null) return;

        task.state = TaskState.AWAITING_RESOURCES;
        task.awaitingResource = needed;
        task.stepIndex = world.get(npcId, TaskExecutor.class).stepIndex; // preserve progress

        // Release NPC
        releaseNpc(taskId, npcId, world);

        // Emit event so TaskSources can create supply tasks
        eventBus.emit(new TaskAwaitingResources(taskId, needed));
        notifyChanged();
        Log.info(TAG, "awaitingResources #%d need %s (step=%d)", taskId, needed, task.stepIndex);
    }

    /**
     * Called when an NPC dies while holding a global task.
     * Preserves stepIndex so another NPC can resume where the dead one left off.
     * Private queue is discarded (per user decision).
     */
    public void releaseTaskForReassign(long taskId, long npcId, World world) {
        GlobalTask task = tasks.get(taskId);
        if (task == null) return;

        // Preserve progress
        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        if (exec != null) {
            task.stepIndex = exec.stepIndex;
        }

        // Detach NPC
        releaseNpc(taskId, npcId, world);

        // Re-queue
        task.state = TaskState.PENDING_ASSIGN;
        task.assignedNpcId = null;

        notifyChanged();
        Log.info(TAG, "reassign #%d '%s' — NPC %d died, step=%d re-queued",
                taskId, task.sequence.label(), npcId, task.stepIndex);
    }

    /** Advance stepIndex on the global task (called after DONE). */
    public void advanceStep(long taskId, int newStepIndex) {
        GlobalTask task = tasks.get(taskId);
        if (task != null) {
            task.stepIndex = newStepIndex;
            Log.debug(TAG, "advanceStep #%d → %d/%d", taskId, newStepIndex, task.sequence.size());
        }
    }

    // ---- Trigger event handler ----

    private void onTriggerEvent(TriggerDeclaration trigger, CustomEvent event) {
        if (!trigger.eventName().equals(event.name())) return;

        // Filter check: event params must contain all filter entries
        if (!matchesFilter(trigger.paramFilter(), event.params())) {
            Log.debug(TAG, "trigger %s → filter mismatch for event %s", trigger.eventName(), event);
            return;
        }

        // Resolve sourceBlueprintId template with event params
        Map<String, String> templateVars = new HashMap<>();
        for (var entry : event.params().entrySet()) {
            templateVars.put("event." + entry.getKey(), entry.getValue());
        }
        String resolvedBpId = TemplateResolver.resolve(trigger.sourceBlueprintId(), templateVars);

        // Apply paramMapping (key rename) and wrap values as JsonPrimitive
        Map<String, String> rawParams = applyMapping(trigger.paramMapping(), event.params());
        Map<String, JsonElement> taskParams = new HashMap<>();
        for (var entry : rawParams.entrySet()) {
            taskParams.put(entry.getKey(), new JsonPrimitive(entry.getValue()));
        }

        // Dedup check
        if (trigger.dedupKey() != null) {
            String dedupValue = event.params().get(trigger.dedupKey());
            if (dedupValue != null && isDuplicate(resolvedBpId, trigger.dedupKey(), dedupValue)) {
                Log.debug(TAG, "trigger %s → dedup skip: %s=%s already in flight",
                        trigger.eventName(), trigger.dedupKey(), dedupValue);
                return;
            }
        }

        long newTaskId = addTask(new TaskRequest(resolvedBpId, taskParams, trigger.priority()));
        Log.info(TAG, "trigger %s → task #%d blueprint=%s priority=%d",
                trigger.eventName(), newTaskId, resolvedBpId, trigger.priority());
    }

    /** Check if event params contain all filter entries (subset match). */
    private static boolean matchesFilter(Map<String, String> filter, Map<String, String> eventParams) {
        if (filter.isEmpty()) return true;
        for (var entry : filter.entrySet()) {
            if (!entry.getValue().equals(eventParams.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** Apply key-rename mapping from event params to task params. */
    private static Map<String, String> applyMapping(Map<String, String> mapping, Map<String, String> eventParams) {
        if (mapping.isEmpty()) {
            // Passthrough: copy all event params
            return new HashMap<>(eventParams);
        }
        Map<String, String> result = new HashMap<>();
        for (var entry : mapping.entrySet()) {
            String value = eventParams.get(entry.getKey());
            if (value != null) {
                result.put(entry.getValue(), value);
            }
        }
        return result;
    }

    /** Check whether a task with the given blueprint+dedupKey is already in-flight. */
    private boolean isDuplicate(String blueprintId, String dedupKey, String dedupValue) {
        String labelPrefix = resolvedToLabelPrefix(blueprintId);
        for (GlobalTask t : all()) {
            if (t.state == TaskState.COMPLETED) continue;
            String label = t.sequence.label();
            // Heuristic 1: label starts with conventional prefix (e.g. "Gather stone_bricks")
            // Heuristic 2: label equals the blueprintId directly
            boolean labelMatch = label.startsWith(labelPrefix) || label.equals(blueprintId);
            if (labelMatch) {
                // dedupValue appears in label, taskParams, or label==blueprintId
                if (label.equals(blueprintId) || label.contains(dedupValue)) {
                    return true;
                }
                for (var v : t.taskParams.values()) {
                    if (v.isJsonPrimitive() && v.getAsString().equals(dedupValue)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Convert a blueprint ID like "gather:stone_bricks" to a label prefix "Gather stone_bricks". */
    private static String resolvedToLabelPrefix(String blueprintId) {
        String[] parts = blueprintId.split(":", 2);
        if (parts.length == 2) {
            return capitalize(parts[0]) + " " + parts[1];
        }
        return capitalize(blueprintId);
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- Resource fulfillment wake-up ----

    private void onResourceFulfilled(ResourceFulfilled event) {
        int awakened = 0;
        for (GlobalTask task : tasks.values()) {
            if (task.state == TaskState.AWAITING_RESOURCES
                    && task.awaitingResource != null
                    && task.awaitingResource.resource().equals(event.resource())) {
                int available = colonyResources.available(event.resource());
                if (available >= task.awaitingResource.amount()) {
                    task.state = TaskState.PENDING_ASSIGN;
                    task.awaitingResource = null;
                    awakened++;
                }
            }
        }
        if (awakened > 0) {
            Log.info(TAG, "ResourceFulfilled(%s) awakened %d tasks", event.resource(), awakened);
        }
    }

    // ---- Persistence ----

    /** All non-COMPLETED tasks with a blueprintId (persistable across sessions). */
    public List<GlobalTask> getPersistableTasks() {
        List<GlobalTask> result = new ArrayList<>();
        for (GlobalTask t : tasks.values()) {
            if (t.state != TaskState.COMPLETED && t.blueprintId != null) {
                result.add(t);
            }
        }
        return result;
    }

    public long getNextTaskId() { return nextTaskId; }
    public void setNextTaskId(long id) { this.nextTaskId = id; }

    /** Add a task loaded from persistence, preserving its original ID. */
    public void addLoadedTask(GlobalTask task, long originalId) {
        tasks.put(originalId, task);
        if (originalId >= nextTaskId) {
            nextTaskId = originalId + 1;
        }
        Log.info(TAG, "loadTask #%d '%s' state=%s step=%d/%d",
                originalId, task.sequence.label(), task.state,
                task.stepIndex, task.sequence.size());
    }

    // ---- Queries ----

    /** Get all tasks in a given state. */
    public List<GlobalTask> getByState(TaskState state) {
        List<GlobalTask> result = new ArrayList<>();
        for (GlobalTask t : tasks.values()) {
            if (t.state == state) result.add(t);
        }
        return result;
    }

    /** All PENDING_ASSIGN tasks, sorted by priority descending. */
    public List<GlobalTask> getAssignableTasks() {
        List<GlobalTask> pending = getByState(TaskState.PENDING_ASSIGN);
        pending.sort((a, b) -> Integer.compare(b.priority, a.priority));
        return pending;
    }

    public GlobalTask get(long taskId) {
        return tasks.get(taskId);
    }

    public int size() {
        // Only count active tasks — COMPLETED tasks are kept for history
        // but should not inflate the pool size metric.
        int count = 0;
        for (GlobalTask t : tasks.values()) {
            if (t.state != TaskState.COMPLETED) count++;
        }
        return count;
    }

    /** Check whether a task is still active (exists and not COMPLETED). */
    public boolean isActive(long taskId) {
        GlobalTask t = tasks.get(taskId);
        return t != null && t.state != TaskState.COMPLETED;
    }

    public Collection<GlobalTask> all() {
        return Collections.unmodifiableCollection(tasks.values());
    }

    // ---- Helpers ----

    private static GridPos parseGridPos(Map<String, JsonElement> params) {
        if (params == null) return null;
        try {
            var xEl = params.get("x");
            var yEl = params.get("y");
            var zEl = params.get("z");
            if (xEl != null && yEl != null && zEl != null) {
                return new GridPos(xEl.getAsInt(), yEl.getAsInt(), zEl.getAsInt());
            }
        } catch (NumberFormatException | IllegalStateException ignored) {
        }
        return null;
    }
}
