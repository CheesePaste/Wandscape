package com.wsteam.wandscape.shared.data;

import java.util.Map;

public interface ElementStore {
    long getAmount(ElementType type);

    default boolean has(ElementType type, long amount) {
        return getAmount(type) >= amount;
    }

    Map<ElementType, Long> getAll();
}
