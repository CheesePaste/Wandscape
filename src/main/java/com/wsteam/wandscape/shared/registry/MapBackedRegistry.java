package com.wsteam.wandscape.shared.registry;

import java.util.HashMap;
import java.util.Map;

public class MapBackedRegistry<T> implements WandscapeDataRegistry<T> {
    private final Map<String, T> entries = new HashMap<>();

    @Override
    public T get(String id) {
        return entries.get(id);
    }

    @Override
    public Map<String, T> getAll() {
        return Map.copyOf(entries);
    }

    @Override
    public boolean contains(String id) {
        return entries.containsKey(id);
    }

    public void put(String id, T entry) {
        entries.put(id, entry);
    }

    public void clear() {
        entries.clear();
    }
}
