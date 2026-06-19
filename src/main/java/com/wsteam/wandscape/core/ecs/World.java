package com.wsteam.wandscape.core.ecs;

import com.wsteam.wandscape.core.Log;
import com.wsteam.wandscape.core.boundary.*;
import com.wsteam.wandscape.core.component.*;
import com.wsteam.wandscape.core.event.SimpleEventBus;
import com.wsteam.wandscape.core.op.OpExecutorRegistry;
import com.wsteam.wandscape.core.task.BlueprintRegistry;
import com.wsteam.wandscape.core.task.GlobalTaskPool;

import java.util.*;

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
    public ColonyResourceAccess colonyResources;
    public EventBus eventBus;
    public BlueprintRegistry blueprintRegistry;
    public GlobalTaskPool taskPool;
    public OpExecutorRegistry opExecutors;

    // ---- Entity management ----

    private static final String TAG = "World";

    public long createEntity() {
        long id = nextEntityId++;
        Log.debug(TAG, "createEntity() -> %d", id);
        return id;
    }

    /** Register a component store for the given type (called once during setup). */
    public <T> void registerComponent(Class<T> type, ComponentStore<T> store) {
        stores.put(type, store);
        Log.debug(TAG, "registerComponent(%s)", type.getSimpleName());
    }

    /** Add a component to an entity. Fails if the store was never registered. */
    @SuppressWarnings("unchecked")
    public <T> void addComponent(long entity, T component) {
        ComponentStore<T> store = (ComponentStore<T>) stores.get(component.getClass());
        if (store == null) {
            throw new IllegalStateException("No ComponentStore registered for " + component.getClass().getSimpleName());
        }
        store.add(entity, component);
        Log.debug(TAG, "entity %d + %s", entity, component);
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

    /** Execute all systems in registration order. */
    public void tick(float delta) {
        Log.debug(TAG, "tick begin (delta=%.1f) - entities=%d tasks=%d events=%d",
                delta, nextEntityId - 1,
                taskPool != null ? taskPool.size() : 0,
                eventBus instanceof SimpleEventBus eb ? eb.queueSize() : 0);

        for (System sys : systems) {
            sys.update(this, delta);
        }
        // Dispatch queued events at end of tick
        if (eventBus instanceof SimpleEventBus eb) {
            eb.dispatch();
        }
        Log.debug(TAG, "tick end");
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
