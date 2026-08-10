package com.wsteam.wandscape.core.ecs;

import com.wsteam.wandscape.core.boundary.*;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.core.event.SimpleEventBus;
import com.wsteam.wandscape.op.executor.OpExecutorRegistry;
import com.wsteam.wandscape.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.task.engine.pool.BuildingTaskPool;
import com.wsteam.wandscape.task.engine.pool.GlobalTaskPool;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * The central ECS world. Owns component stores, systems, and boundary service references.
 */
public class World {

    // ---- Component stores ----
    final Map<Class<?>, ComponentStore<?>> stores = new LinkedHashMap<>();

    // ---- Systems ----
    final List<System> systems = new ArrayList<>();

    // ---- Entity ID generation ----
    long nextEntityId = 1;

    /** Expose the next entity ID (useful for diagnostics). */
    public long getNextEntityId() {
        return nextEntityId;
    }

    /** Number of registered systems. */
    public int systemCount() {
        return systems.size();
    }

    /** Access the component store map (for diagnostics). */
    public Map<Class<?>, ComponentStore<?>> stores() {
        return Collections.unmodifiableMap(stores);
    }

    // ---- Boundary services (injected on bootstrap) ----
    public BlockOps blockOps;
    public EntityOps entityOps;
    public RitualOps ritualOps;
    public MovementOps movementOps;
    public ColonyResourceAccess colonyResources;
    public EventBus eventBus;
    public BlueprintRegistry blueprintRegistry;
    public GlobalTaskPool taskPool;
    public BuildingTaskPool buildingTaskPool;
    public OpExecutorRegistry opExecutors;

    // ---- Async op tracking (event-driven tick gating, V2.5) ----
    /**
     * CompletableFutures for all in-flight async operations.
     * Engine tick is gated until all futures complete.
     * <p>
     * Pattern: MC boundary calls {@link #startAsyncOp(String)} to get a
     * future, then completes it when the MC-level operation finishes
     * (pathfinding done, ritual channeled, etc.). The {@code whenComplete}
     * callback auto-removes the future from this list.
     */
    private final List<CompletableFuture<Void>> pendingFutures = new ArrayList<>();

    /**
     * Start an async operation and get back a CompletableFuture.
     * The engine tick is blocked until the MC boundary {@link CompletableFuture#complete
     * completes} this future (or it completes exceptionally / times out).
     *
     * @param label short description for debug logging (e.g. "move_to_10_64_5")
     * @return a future the MC boundary must complete when the operation finishes
     */
    public CompletableFuture<Void> startAsyncOp(String label) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        pendingFutures.add(future);
        // Auto-remove from pending list on completion (success, failure, or timeout)
        future.whenComplete((v, ex) -> {
            pendingFutures.remove(future);
            if (ex != null) {
                Log.warn(TAG, "asyncOp '%s' failed: %s", label, ex.getMessage());
            } else {
            }
        });
        return future;
    }

    /** True if any async op is still in-flight (engine tick is blocked). */
    public boolean hasPendingAsyncOps() {
        return !pendingFutures.isEmpty();
    }

    // ---- Entity management ----

    private static final String TAG = "World";

    public long createEntity() {
        long id = nextEntityId++;
        return id;
    }

    /** Register a component store for the given type (called once during setup). */
    public <T> void registerComponent(Class<T> type, ComponentStore<T> store) {
        stores.put(type, store);
    }

    /** Add a component to an entity. Fails if the store was never registered. */
    @SuppressWarnings("unchecked")
    public <T> void addComponent(long entity, T component) {
        ComponentStore<T> store = (ComponentStore<T>) stores.get(component.getClass());
        if (store == null) {
            throw new IllegalStateException("No ComponentStore registered for " + component.getClass().getSimpleName());
        }
        store.add(entity, component);
    }

    /** Get a component by type. Returns null if absent. */
    @SuppressWarnings("unchecked")
    public <T> T get(long entity, Class<T> type) {
        ComponentStore<T> store = (ComponentStore<T>) stores.get(type);
        return store != null ? store.get(entity) : null;
    }

    /** Check whether an entity has a component type. */
    public <T> boolean has(long entity, Class<T> type) {
        ComponentStore<T> store = getStore(type);
        return store != null && store.has(entity);
    }

    /** Remove a component from an entity. No-op if the store or entity doesn't exist. */
    public <T> void removeComponent(long entity, Class<T> type) {
        ComponentStore<T> store = getStore(type);
        if (store != null) {
            store.remove(entity);
        }
    }

    /**
     * Query entities that have ALL of the given component types.
     * Uses sorted-list intersection for performance.
     */
    @SafeVarargs
    public final List<Long> query(Class<?>... componentTypes) {
        if (componentTypes.length == 0) {
            return Collections.emptyList();
        }

        ComponentStore<?> base = getStore(componentTypes[0]);
        if (base == null) {
            return Collections.emptyList();
        }

        List<Long> result = new ArrayList<>(base.entities());

        for (int i = 1; i < componentTypes.length && !result.isEmpty(); i++) {
            ComponentStore<?> store = getStore(componentTypes[i]);
            if (store == null) {
                return Collections.emptyList();
            }
            List<Long> other = store.entities();
            result = intersectSorted(result, other);
        }

        return result;
    }

    // ---- System management ----

    public void addSystem(System sys) {
        systems.add(sys);
    }

    /** Full task recovery: clear global pool, building queues, and reset all NPC executors. */
    public void clearAllTasks() {
        if (taskPool != null) {
            taskPool.clearAll();
        }
        if (buildingTaskPool != null) {
            buildingTaskPool.clear();
        }
        for (long entity : query(TaskExecutor.class)) {
            TaskExecutor exec = get(entity, TaskExecutor.class);
            if (exec != null) {
                if (movementOps != null) {
                    movementOps.cancelNavigation(entity);
                }
                exec.reset();
            }
        }
    }

    /** Execute all systems in registration order. */
    public void tick(float delta) {

        for (System sys : systems) {
            sys.update(this, delta);
        }
        // Dispatch queued events at end of tick
        if (eventBus instanceof SimpleEventBus eb) {
            eb.dispatch();
        }
    }

    // ---- Internal helpers ----

    @SuppressWarnings("unchecked")
    private <T> ComponentStore<T> getStore(Class<T> type) {
        return (ComponentStore<T>) stores.get(type);
    }

    /** Intersect two sorted lists, returning a new list. */
    private static List<Long> intersectSorted(List<Long> a, List<Long> b) {
        List<Long> result = new ArrayList<>(Math.min(a.size(), b.size()));
        int ai = 0, bi = 0;
        while (ai < a.size() && bi < b.size()) {
            long av = a.get(ai);
            long bv = b.get(bi);
            if (av < bv) {
                ai++;
            } else if (bv < av) {
                bi++;
            } else {
                result.add(av);
                ai++;
                bi++;
            }
        }
        return result;
    }
}
