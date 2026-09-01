package com.wsteam.wandscape.foundation.registry;

import java.util.Map;
public interface WandscapeDataRegistry<T> {
    T get(String id);
    Map<String, T> getAll();
    boolean contains(String id);
}
