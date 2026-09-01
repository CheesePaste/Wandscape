package com.wsteam.wandscape.content.task.engine.pool;
import com.wsteam.wandscape.content.npc.component.NpcTaskQueue;
import com.wsteam.wandscape.content.task.boundary.MovementOps;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.impl.TemplateResolver;
import com.wsteam.wandscape.content.task.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.content.task.boundary.EventBus;
import com.wsteam.wandscape.content.task.boundary.ResourceShortageHandler;
import com.wsteam.wandscape.content.task.component.TaskExecutor;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.event.CustomEvent;
import com.wsteam.wandscape.content.task.event.TaskCompleted;
import com.wsteam.wandscape.content.task.types.GridPos;
import com.wsteam.wandscape.content.task.types.ResourceId;
import com.wsteam.wandscape.content.task.types.ResourceStack;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.content.task.engine.dsl.CompiledBlueprint;
import com.wsteam.wandscape.content.task.engine.dsl.TaskCompiler;
import com.wsteam.wandscape.content.task.engine.dsl.TriggerDeclaration;
import com.wsteam.wandscape.content.task.runtime.ApprovalInfo;
import com.wsteam.wandscape.content.task.runtime.TaskSequence;
import com.wsteam.wandscape.content.task.runtime.TaskState;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Central container for all global tasks. Manages task lifecycle and assignment.
 *
 * <p>Ordering: assignable tasks are kept in a TreeSet ordered by
 * {@code priority desc → createdAt asc → id asc}, ensuring older tasks
 * win ties and deterministic ordering.
 *
 * <p>State-to-queue mapping:
 * <ul><li>{@link TaskState#PENDING_ASSIGN} → in assignableSet</li>
 *     <li>{@link TaskState#AWAITING_RESOURCES} → dormancy (not in set)</li>
 *     <li>All other states → not in set</li></ul>
 *
 * <p>Trigger lifecycle: when a task is assigned, its {@link TriggerDeclaration}s are
 * subscribed to the event bus. When the task completes, they are unsubscribed.
 */
public class GlobalTaskPool {

    private static final String TAG = "TaskPool";

    /** Assignable-task ordering: priority desc → createdAt asc → id asc. */
    private static final Comparator<GlobalTask> ASSIGNABLE_ORDER =
            Comparator.<GlobalTask>comparingInt(t -> t.priority).reversed()
                    .thenComparingLong(t -> t.createdAt)
                    .thenComparingLong(t -> t.id);

    private final Map<Long, GlobalTask> tasksById = new HashMap<>();
    private final TreeSet<GlobalTask> assignableSet = new TreeSet<>(ASSIGNABLE_ORDER);
    private final EventBus eventBus;
    private final TaskCompiler compiler;
    private final ColonyResourceAccess colonyResources;
    private final boolean autoApprove;
    private long nextTaskId = 1;

    /** Optional handler for resource shortages (synthesize / gather). */
    @javax.annotation.Nullable
    private ResourceShortageHandler resourceShortageHandler;

    public void setResourceShortageHandler(@javax.annotation.Nullable ResourceShortageHandler handler) {
        this.resourceShortageHandler = handler;
    }

    /** Called whenever the task pool is mutated (add/assign/complete/release). */
    @javax.annotation.Nullable
    public Runnable onChanged;

    public GlobalTaskPool(EventBus eventBus, TaskCompiler compiler, ColonyResourceAccess colonyResources, boolean autoApprove) {
        this.eventBus = eventBus;
        this.compiler = compiler;
        this.colonyResources = colonyResources;
        this.autoApprove = autoApprove;

    }

    // ── Queue maintenance ──

    private void addToAssignable(GlobalTask task) {
        if (task.state == TaskState.PENDING_ASSIGN) {
            assignableSet.add(task);
        }
    }

    private void removeFromAssignable(GlobalTask task) {
        assignableSet.remove(task);
    }

    private void transitionToPendingAssign(GlobalTask task) {
        task.state = TaskState.PENDING_ASSIGN;
        task.awaitingResource = null;
        assignableSet.add(task);
    }

    private void notifyChanged() {
        if (onChanged != null) onChanged.run();
    }

    // ── Task creation ──

    /** Add a task from a request. Automatically determines if approval is needed. */
    public long addTask(TaskRequest request) {
        return addTaskWithId(request, nextTaskId++);
    }

    /**
     * Add a task with a fixed id (used by persistence load). The task's
     * {@code id} field is set to the given id so it always matches its pool key —
     * otherwise {@code taskPool.get(task.id)} / {@code assignLight(task.id)} resolve
     * to the wrong entry (or null) after a reload, which can leave a ghost task in
     * the assignable set that re-assigns the same underlying task to a new NPC
     * every heartbeat.
     */
    public long addTaskWithId(TaskRequest request, long id) {
        CompiledBlueprint compiled = compiler.compile(request, null);
        TaskSequence seq = compiled.sequence();
        if (id >= nextTaskId) nextTaskId = id + 1;

        TaskState initialState;
        ApprovalInfo approval = null;
        if (!autoApprove && request.priority() >= 50) {
            initialState = TaskState.PENDING_APPROVAL;
            GridPos suggestedPos = parseGridPos(request.params());
            approval = new ApprovalInfo(suggestedPos, Long.MAX_VALUE, false);
        } else {
            initialState = TaskState.PENDING_ASSIGN;
        }

        long createdAt = System.currentTimeMillis();

        // ── 殖民地归属归一化（params["colony_id"] 的唯一写入点）──
        // 来源在 TaskRequest.colonyId 显式给出 → 统一写成规范化 UUID 字符串；
        // 来源传 null（无主任务）→ 保留 params 里已有的 colony_id（事件透传/存档恢复）。
        Map<String, JsonElement> taskParams = new HashMap<>(request.params());
        if (request.colonyId() != null) {
            taskParams.put("colony_id", new JsonPrimitive(request.colonyId().toString()));
        } else if (isColonyBoundBlueprint(request.blueprintId())
                && !taskParams.containsKey("colony_id")) {
            Log.warn(TAG, "addTask #%d '%s' — colony-bound blueprint has no colony_id; task is unbound "
                    + "(assigned to any real-colony NPC, never placeholder)", id, request.blueprintId());
        }

        GlobalTask task = new GlobalTask(id, seq,
                request.priority(), createdAt,
                compiled.triggers(), new ArrayList<>(),
                taskParams,
                initialState, 0, null, null,
                new ArrayDeque<>(), approval);
        task.blueprintId = request.blueprintId();
        tasksById.put(id, task);
        addToAssignable(task);
        notifyChanged();
        Log.info(TAG, "addTask #%d '%s' blueprint=%s state=%s priority=%d steps=%d triggers=%d",
                id, seq.label(), request.blueprintId(), initialState, request.priority(),
                seq.size(), compiled.triggers().size());
        return id;
    }

    /** Add a pre-built task directly (used by systems). */
    public long addTask(GlobalTask task) {
        long id = nextTaskId++;
        GlobalTask t = new GlobalTask(id, task.sequence,
                task.priority, task.createdAt,
                new ArrayList<>(task.triggers), new ArrayList<>(),
                new HashMap<>(task.taskParams),
                task.state, task.stepIndex, null,
                task.awaitingResource,
                task.interruptHistory != null ? new ArrayDeque<>(task.interruptHistory) : new ArrayDeque<>(),
                task.approval);
        tasksById.put(id, t);
        t.channelRemainingTicks = task.channelRemainingTicks; // preserve any mid-channel checkpoint
        addToAssignable(t);
        notifyChanged();
        Log.info(TAG, "addTask #%d '%s' (pre-built) state=%s priority=%d triggers=%d",
                id, t.sequence.label(), t.state, t.priority, t.triggers.size());
        return id;
    }

    /**
     * Add a task that is the head of a building's queue.
     * Marks the task with building ownership so completion can trigger promotion.
     */
    public long addTaskFromBuilding(TaskRequest request, UUID buildingId) {
        long taskId = addTask(request);
        GlobalTask task = get(taskId);
        if (task != null) {
            task.buildingId = buildingId;
            task.isBuildingHead = true;
            if (task.state == TaskState.PENDING_APPROVAL) {
                transitionToPendingAssign(task);
            }
        }
        return taskId;
    }

    // ── Approval ──

    public void approve(long taskId) {
        GlobalTask task = tasksById.get(taskId);
        if (task != null && task.state == TaskState.PENDING_APPROVAL) {
            transitionToPendingAssign(task);
            notifyChanged();
            Log.info(TAG, "approve #%d '%s' → PENDING_ASSIGN", taskId, task.sequence.label());
        }
    }

    public void reject(long taskId) {
        GlobalTask task = tasksById.get(taskId);
        if (task != null && task.state == TaskState.PENDING_APPROVAL) {
            task.state = TaskState.COMPLETED;
            notifyChanged();
            Log.info(TAG, "reject #%d '%s' → COMPLETED", taskId, task.sequence.label());
        }
    }

    // ── Assignment ──

    /**
     * Mark a task as assigned and subscribe triggers. Does NOT touch
     * TaskExecutor fields — the caller (SchedulerSystem) creates the
     * NpcTaskPackage and enqueues it into the NPC's NpcTaskQueue directly.
     */
    public void assignLight(long taskId, long npcId, World world) {
        GlobalTask task = tasksById.get(taskId);
        if (task == null) return;

        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        if (exec == null) return;

        removeFromAssignable(task);
        task.state = TaskState.IN_PROGRESS;
        task.assignedNpcId = npcId;
        exec.taskParams = new HashMap<>(task.taskParams);

        if (task.subscriptions.isEmpty()) {
            for (TriggerDeclaration trigger : task.triggers) {
                var sub = eventBus.subscribe(CustomEvent.class,
                        event -> onTriggerEvent(trigger, event));
                task.subscriptions.add(sub);
            }
        }

        Log.info(TAG, "assignLight #%d '%s' → NPC %d (triggers=%d)",
                taskId, task.sequence.label(), npcId, task.triggers.size());
    }

    /** Release an NPC from its current task. */
    public void releaseNpc(long taskId, long npcId, World world) {
        GlobalTask task = tasksById.get(taskId);
        if (task == null) return;

        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        if (exec != null && exec.globalTaskId != null && exec.globalTaskId == taskId) {
            exec.releaseGlobalTask();
        }
        task.assignedNpcId = null;
    }

    // ── State transitions ──

    /** Called when a step completes and the task is done. */
    public void completeTask(long taskId, long npcId) {
        GlobalTask task = tasksById.get(taskId);
        if (task == null) return;
        removeFromAssignable(task);
        task.state = TaskState.COMPLETED;
        task.assignedNpcId = null;

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
                                       List<ResourceStack> needed,
                                       World world) {
        GlobalTask task = tasksById.get(taskId);
        if (task == null) return;

        removeFromAssignable(task);
        task.state = TaskState.AWAITING_RESOURCES;
        task.awaitingResource = List.copyOf(needed);
        task.stepIndex = world.get(npcId, TaskExecutor.class).stepIndex;

        releaseNpc(taskId, npcId, world);

        // Auto-recovery: try to create production tasks for the needed resources
        if (resourceShortageHandler != null && !needed.isEmpty()) {
            ResourceStack primary = needed.get(0);
            resourceShortageHandler.handle(primary.resource(), primary.amount(), GridPos.ORIGIN);
        }

        notifyChanged();
        Log.info(TAG, "awaitingResources #%d need %s (step=%d)", taskId, needed, task.stepIndex);
    }

    /**
     * Called when an NPC dies or can't continue. Preserves stepIndex for resume.
     */
    public void releaseTaskForReassign(long taskId, long npcId, World world) {
        GlobalTask task = tasksById.get(taskId);
        if (task == null) return;

        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        if (exec != null) {
            task.stepIndex = exec.stepIndex;
        }

        releaseNpc(taskId, npcId, world);
        transitionToPendingAssign(task);

        notifyChanged();
        Log.info(TAG, "reassign #%d '%s' — NPC %d released, step=%d re-queued",
                taskId, task.sequence.label(), npcId, task.stepIndex);
    }

    /**
     * Terminally cancel a task (e.g. a building that was undone). The task leaves
     * the assignable set, its triggers are unsubscribed, and its assigned NPC (if
     * any) is released from it — the NPC's queued global package is dropped and
     * any pending navigation is cancelled so it stops executing immediately.
     * Unlike {@link #completeTask}, no {@link TaskCompleted} is emitted (the work
     * was not done), so downstream trigger chains are not fired.
     *
     * @return the ECS id of the NPC the task was assigned to, or -1 if none
     */
    public long cancelTask(long taskId, World world) {
        GlobalTask task = tasksById.get(taskId);
        if (task == null) return -1;

        removeFromAssignable(task);
        task.state = TaskState.COMPLETED;

        long npcId = task.assignedNpcId != null ? task.assignedNpcId : -1;
        if (npcId >= 0) {
            releaseNpc(taskId, npcId, world);
            TaskExecutor exec = world.get(npcId, TaskExecutor.class);
            if (exec != null) {
                exec.npcQueue.dropGlobalPackages();
            }
            if (world.movementOps != null) {
                world.movementOps.cancelNavigation(npcId);
            }
        }
        task.assignedNpcId = null;

        for (var sub : task.subscriptions) {
            eventBus.unsubscribe(sub);
        }
        task.subscriptions.clear();

        notifyChanged();
        Log.info(TAG, "cancel #%d '%s' — task removed (npc=%d)", taskId, task.sequence.label(), npcId);
        return npcId;
    }

    /** Update priority of an existing task. Re-sorts assignableSet if pending. */
    public boolean updatePriority(long taskId, int newPriority) {
        GlobalTask task = tasksById.get(taskId);
        if (task == null) return false;
        if (task.state == TaskState.PENDING_ASSIGN) {
            removeFromAssignable(task);
            task.priority = newPriority;
            addToAssignable(task);
        } else {
            task.priority = newPriority;
        }
        notifyChanged();
        Log.info(TAG, "updatePriority #%d '%s' → %d", taskId, task.sequence.label(), newPriority);
        return true;
    }

    /** Advance stepIndex on the global task. */
    public void advanceStep(long taskId, int newStepIndex) {
        GlobalTask task = tasksById.get(taskId);
        if (task != null) {
            task.stepIndex = newStepIndex;
        }
    }

    // ── Trigger event handler ──

    private void onTriggerEvent(TriggerDeclaration trigger, CustomEvent event) {
        if (!trigger.eventName().equals(event.name())) return;

        if (!matchesFilter(trigger.paramFilter(), event.params())) {
            return;
        }

        Map<String, String> templateVars = new HashMap<>();
        for (var entry : event.params().entrySet()) {
            templateVars.put("event." + entry.getKey(), entry.getValue());
        }
        String resolvedBpId = TemplateResolver.resolve(trigger.sourceBlueprintId(), templateVars);

        Map<String, String> rawParams = applyMapping(trigger.paramMapping(), event.params());
        Map<String, JsonElement> taskParams = new HashMap<>();
        for (var entry : rawParams.entrySet()) {
            taskParams.put(entry.getKey(), new JsonPrimitive(entry.getValue()));
        }

        if (trigger.dedupKey() != null) {
            String dedupValue = event.params().get(trigger.dedupKey());
            if (dedupValue != null && isDuplicate(resolvedBpId, trigger.dedupKey(), dedupValue)) {
                return;
            }
        }

        long newTaskId = addTask(new TaskRequest(resolvedBpId, taskParams, trigger.priority(),
                colonyIdFrom(event.params())));
        Log.info(TAG, "trigger %s → task #%d blueprint=%s priority=%d",
                trigger.eventName(), newTaskId, resolvedBpId, trigger.priority());
    }

    /** 事件参数里的 colony_id（字符串）→ UUID；缺失或格式非法返回 null（无主任务）。 */
    @Nullable
    private static UUID colonyIdFrom(Map<String, String> eventParams) {
        String cid = eventParams.get("colony_id");
        if (cid == null) return null;
        try {
            return UUID.fromString(cid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 资源消耗型/建筑型蓝图前缀——必须归属殖民地，否则无法从仓库供料。 */
    private static boolean isColonyBoundBlueprint(@Nullable String blueprintId) {
        if (blueprintId == null) return false;
        return blueprintId.startsWith("build:")
                || blueprintId.startsWith("production:")
                || blueprintId.startsWith("road:")
                || blueprintId.startsWith("terrain:");
    }

    private static boolean matchesFilter(Map<String, String> filter, Map<String, String> eventParams) {
        if (filter.isEmpty()) return true;
        for (var entry : filter.entrySet()) {
            if (!entry.getValue().equals(eventParams.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> applyMapping(Map<String, String> mapping, Map<String, String> eventParams) {
        if (mapping.isEmpty()) {
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

    private boolean isDuplicate(String blueprintId, String dedupKey, String dedupValue) {
        String labelPrefix = resolvedToLabelPrefix(blueprintId);
        for (GlobalTask t : all()) {
            if (t.state == TaskState.COMPLETED) continue;
            String label = t.sequence.label();
            boolean labelMatch = label.startsWith(labelPrefix) || label.equals(blueprintId);
            if (labelMatch) {
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

    // ── Resource fulfillment wake-up ──

    /** Called when a resource is added to the warehouse. Wakes any AWAITING_RESOURCES tasks. */
    public void onResourceAdded(ResourceId resource, int amount) {
        int totalWaiting = 0;
        int relevant = 0;
        int awakened = 0;
        for (GlobalTask task : tasksById.values()) {
            if (task.state != TaskState.AWAITING_RESOURCES
                    || task.awaitingResource == null || task.awaitingResource.isEmpty()) {
                continue;
            }
            totalWaiting++;

            boolean matches = task.awaitingResource.stream()
                    .anyMatch(r -> r.resource().equals(resource));
            if (!matches) continue;
            relevant++;

            boolean allAvailable = true;
            for (ResourceStack need : task.awaitingResource) {
                if (colonyResources.available(need.resource()) < need.amount()) {
                    allAvailable = false;
                    break;
                }
            }
            if (allAvailable) {
                transitionToPendingAssign(task);
                awakened++;
            }
        }
        Log.info(TAG, "onResourceAdded(%s +%d) waiting=%d relevant=%d awakened=%d",
                resource, amount, totalWaiting, relevant, awakened);
        if (awakened > 0) {
            notifyChanged();
        }
    }

    /** Wake a single AWAITING_RESOURCES task back to PENDING_ASSIGN. */
    public void wakeupTask(long taskId) {
        GlobalTask task = tasksById.get(taskId);
        if (task == null || task.state != TaskState.AWAITING_RESOURCES) return;
        transitionToPendingAssign(task);
        notifyChanged();
        Log.info(TAG, "wakeup #%d '%s' → PENDING_ASSIGN", taskId, task.sequence.label());
    }

    // ── Persistence ──

    /** All non-COMPLETED tasks with a blueprintId. */
    public List<GlobalTask> getPersistableTasks() {
        List<GlobalTask> result = new ArrayList<>();
        for (GlobalTask t : tasksById.values()) {
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
        // Idempotency: evict any prior copy under this id so a reload can't leave
        // a stale duplicate in tasksById / assignableSet.
        GlobalTask existing = tasksById.remove(originalId);
        if (existing != null && existing != task) {
            assignableSet.remove(existing);
        }
        // Evict a stale key the same object may be parked under (e.g. a temp id).
        tasksById.values().removeIf(v -> v == task && v.id != originalId);
        tasksById.put(originalId, task);
        if (originalId >= nextTaskId) {
            nextTaskId = originalId + 1;
        }
        // Only PENDING_ASSIGN tasks belong in the assignable set.
        if (task.state == TaskState.PENDING_ASSIGN) {
            addToAssignable(task);
        } else {
            assignableSet.remove(task);
        }
        Log.info(TAG, "loadTask #%d '%s' state=%s step=%d/%d",
                originalId, task.sequence.label(), task.state,
                task.stepIndex, task.sequence.size());
    }

    // ── Queries ──

    /** Get all tasks in a given state. */
    public List<GlobalTask> getByState(TaskState state) {
        List<GlobalTask> result = new ArrayList<>();
        for (GlobalTask t : tasksById.values()) {
            if (t.state == state) result.add(t);
        }
        return result;
    }

    /** All PENDING_ASSIGN tasks, sorted by priority desc → createdAt asc → id asc. */
    public List<GlobalTask> getAssignableTasks() {
        List<GlobalTask> result = new ArrayList<>(assignableSet);
        result.sort(ASSIGNABLE_ORDER);
        return result;
    }

    /** Number of assignable tasks currently in the queue. */
    public int assignableCount() {
        return assignableSet.size();
    }

    public GlobalTask get(long taskId) {
        return tasksById.get(taskId);
    }

    public int size() {
        int count = 0;
        for (GlobalTask t : tasksById.values()) {
            if (t.state != TaskState.COMPLETED) count++;
        }
        return count;
    }

    public boolean isActive(long taskId) {
        GlobalTask t = tasksById.get(taskId);
        return t != null && t.state != TaskState.COMPLETED;
    }

    /**
     * True if any non-COMPLETED task has {@code blueprintId} and its params contain
     * all of {@code requiredParams} (exact JsonElement equality). {@code blueprintId}
     * may be null to match any blueprint. Used to lock/dedupe pending work on the same
     * subject (e.g. one altar cast per altar+magic while the previous task is queued
     * or being channeled).
     */
    public boolean hasActiveTask(@Nullable String blueprintId,
                                 Map<String, JsonElement> requiredParams) {
        for (GlobalTask t : tasksById.values()) {
            if (t.state == TaskState.COMPLETED) continue;
            if (blueprintId != null && !blueprintId.equals(t.blueprintId)) continue;
            if (paramsMatch(t.taskParams, requiredParams)) return true;
        }
        return false;
    }

    private static boolean paramsMatch(Map<String, JsonElement> taskParams,
                                       Map<String, JsonElement> required) {
        for (Map.Entry<String, JsonElement> e : required.entrySet()) {
            if (!e.getValue().equals(taskParams.get(e.getKey()))) return false;
        }
        return true;
    }

    public Collection<GlobalTask> all() {
        return Collections.unmodifiableCollection(tasksById.values());
    }

    /** Clear all tasks, unsubscribe triggers, and reset ID counter. */
    public void clearAll() {
        int count = tasksById.size();
        for (GlobalTask task : tasksById.values()) {
            for (var sub : task.subscriptions) {
                eventBus.unsubscribe(sub);
            }
            task.subscriptions.clear();
        }
        tasksById.clear();
        assignableSet.clear();
        nextTaskId = 1;
        notifyChanged();
        Log.info(TAG, "clearAll — %d tasks removed", count);
    }

    // ── Helpers ──

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
