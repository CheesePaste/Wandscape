package com.wsteam.wandscape.content.building.preview;

import com.wsteam.wandscape.content.building.data.BuildingConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 以 {@code config.id()} 取键、值里记 source 实例的缓存（{@code docs/domain-notes.md} §16 的统一姿势）。
 *
 * <p><b>为什么不拿 {@link BuildingConfig} 直接当键</b>：它是 record，{@code equals} 逐组件比较、
 * **包含整条 pattern**（超大建筑 58 万条）；而配置实例会被整批换掉（datapack 重载、入服同步
 * 走的是「没有清缓存」的那条路），换实例之后每次查表都是一次 O(pattern) 的相等比较。
 *
 * <p>这里只在「同 id 的新实例」出现时比**一次**内容：相同就认下新实例（此后走身份短路，
 * 继续复用旧值），真变了才丢掉重算。
 */
final class ConfigKeyedCache<V> {

    private record Sourced<V>(BuildingConfig source, V value) {}

    private final Map<String, Sourced<V>> map = new ConcurrentHashMap<>();

    /** 取缓存；缺失（或同 id 内容变了）时用 {@code loader} 现算并存入。 */
    V get(BuildingConfig config, Function<BuildingConfig, V> loader) {
        String key = config.id();
        Sourced<V> hit = map.get(key);
        if (hit != null) {
            if (hit.source() == config) {
                return hit.value();
            }
            if (hit.source().equals(config)) {
                map.put(key, new Sourced<>(config, hit.value()));
                return hit.value();
            }
        }
        V value = loader.apply(config);
        map.put(key, new Sourced<>(config, value));
        return value;
    }

    void clear() {
        map.clear();
    }
}
