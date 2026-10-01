package com.wsteam.wandscape.foundation.registry;

import java.util.Map;
public interface WandscapeDataRegistry<T> {
    T get(String id);

    /**
     * 全表只读视图。
     *
     * <p>返回的是**不可变快照**，同一版数据在重载前返回同一个实例（实现里缓存，重载时置脏），
     * 所以调用方可以拿它做身份比较来判断「表有没有变」，不必每次自己复制。不要试图修改它。
     */
    Map<String, T> getAll();

    boolean contains(String id);
}
