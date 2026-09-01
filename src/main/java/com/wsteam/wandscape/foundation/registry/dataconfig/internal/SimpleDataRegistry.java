package com.wsteam.wandscape.foundation.registry.dataconfig.internal;

import com.google.gson.JsonElement;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
class SimpleDataRegistry<T> implements WandscapeDataRegistry<T> {
    private final Map<String, T> entries = new HashMap<>();
    private final BiFunction<String, JsonElement, T> parser;

    SimpleDataRegistry(BiFunction<String, JsonElement, T> parser) {
        this.parser = parser;
    }

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

    void loadEntry(String id, JsonElement json) {
        T result = parser.apply(id, json);
        if (result != null) {
            entries.put(id, result);
        }
    }

    void clear() {
        entries.clear();
    }
}
