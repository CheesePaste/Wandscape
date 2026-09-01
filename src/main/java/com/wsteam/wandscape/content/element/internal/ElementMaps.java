package com.wsteam.wandscape.content.element.internal;

import com.google.gson.JsonObject;
import com.wsteam.wandscape.shared.data.ElementType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Element-value map JSON parsing, shared by element mapping configs,
 * the element audit generator, and the production recipe loaders.
 */
public final class ElementMaps {
    private ElementMaps() {}

    /**
     * Parse a JSON field into an element-value map. Uses {@link LinkedHashMap} to
     * preserve JSON insertion order (the element audit iterates it for deterministic
     * output); returns an empty map if the field is absent.
     */
    public static Map<ElementType, Long> parse(JsonObject obj, String key) {
        Map<ElementType, Long> map = new LinkedHashMap<>();
        if (!obj.has(key)) return map;
        JsonObject costObj = obj.getAsJsonObject(key);
        for (var entry : costObj.entrySet()) {
            ElementType type = ElementType.valueOf(entry.getKey().toUpperCase());
            map.put(type, entry.getValue().getAsLong());
        }
        return map;
    }
}
