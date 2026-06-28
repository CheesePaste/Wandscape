package com.wsteam.wandscape.shared.registry;

import java.util.Map;
public interface WandscapeDataRegistry<T> {
    T get(String id);
    Map<String, T> getAll();
    boolean contains(String id);
}
