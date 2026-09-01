package com.wsteam.wandscape.content.task.ecs;

import java.util.*;
/**
 * Map-backed implementation of ComponentStore.
 * Cache-friendly: entities() result is cached and invalidated on writes.
 */
public class HashMapComponentStore<T> implements ComponentStore<T> {

    private final Map<Long, T> data = new HashMap<>();
    private List<Long> cachedEntities = Collections.emptyList();
    private boolean cacheValid = true;

    @Override
    public void add(long entity, T component) {
        data.put(entity, component);
        cacheValid = false;
    }

    @Override
    public void remove(long entity) {
        data.remove(entity);
        cacheValid = false;
    }

    @Override
    public T get(long entity) {
        return data.get(entity);
    }

    @Override
    public boolean has(long entity) {
        return data.containsKey(entity);
    }

    @Override
    public List<Long> entities() {
        if (!cacheValid) {
            List<Long> list = new ArrayList<>(data.keySet());
            Collections.sort(list);
            cachedEntities = Collections.unmodifiableList(list);
            cacheValid = true;
        }
        return cachedEntities;
    }

    public int size() {
        return data.size();
    }

    @Override
    public String toString() {
        return "HashMapComponentStore[size=" + data.size() + "]";
    }
}
