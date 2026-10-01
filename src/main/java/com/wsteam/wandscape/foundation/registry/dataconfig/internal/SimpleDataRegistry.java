package com.wsteam.wandscape.foundation.registry.dataconfig.internal;

import com.google.gson.JsonElement;
import com.wsteam.wandscape.foundation.registry.WandscapeDataRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
class SimpleDataRegistry<T> implements WandscapeDataRegistry<T> {
    private final Map<String, T> entries = new HashMap<>();
    private final BiFunction<String, JsonElement, T> parser;
    private final Runnable onClear;

    /**
     * {@link #getAll()} 的不可变快照。{@link #loadEntry} / {@link #clear} 后置空，下次
     * getAll 只重建一次。
     *
     * <p>{@code getAll()} 曾被写成「每次调用 {@code Map.copyOf(entries)}」，而它被放在逐方块的
     * 循环里（元素映射查表每次调用都拉一次全表）：spark 实测 {@code Map.copyOf} 一条链在一栋
     * 超大建筑上占服务端线程 23.65% ≈ 23.8 秒。缓存后单次 getAll 是零拷贝。
     *
     * <p>顺带给了下游一个**稳定的身份**：快照实例不变 ⇒ 表没重载，下游可以据此缓存派生索引
     * （见 {@code ElementMappingLoader} 的 id 索引）。
     *
     * <p>volatile 而非加锁：写只发生在数据重载（服务端 reload / 客户端收到同步包）那一刻，
     * 读可能来自 JEI 等客户端线程；最坏是读到上一版快照，与「每次读 entries 的当时状态」
     * 相比不新增风险。
     */
    private volatile Map<String, T> snapshot;

    SimpleDataRegistry(BiFunction<String, JsonElement, T> parser) {
        this(parser, null);
    }

    SimpleDataRegistry(BiFunction<String, JsonElement, T> parser, Runnable onClear) {
        this.parser = parser;
        this.onClear = onClear;
    }

    @Override
    public T get(String id) {
        return entries.get(id);
    }

    @Override
    public Map<String, T> getAll() {
        Map<String, T> cached = snapshot;
        if (cached == null) {
            cached = Map.copyOf(entries);
            snapshot = cached;
        }
        return cached;
    }

    @Override
    public boolean contains(String id) {
        return entries.containsKey(id);
    }

    void loadEntry(String id, JsonElement json) {
        T result = parser.apply(id, json);
        if (result != null) {
            entries.put(id, result);
            snapshot = null;
        }
    }

    void clear() {
        entries.clear();
        snapshot = null;
        if (onClear != null) {
            onClear.run();
        }
    }
}
