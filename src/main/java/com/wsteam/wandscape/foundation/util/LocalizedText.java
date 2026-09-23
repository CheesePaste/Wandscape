package com.wsteam.wandscape.foundation.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.foundation.log.Log;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据包提供的名字的多语言写法（建筑名、建筑包名与描述、道路预设名共用一套规则）。
 *
 * <h3>JSON 形态</h3>
 * <pre>
 *   "display_name": "Tea House"                      // 不分语言，所有玩家看同一份
 *   "display_name": { "zh_cn": "茶馆", "en_us": "Tea House" }   // 按语言
 * </pre>
 *
 * <p>字符串形态是合法的"没有语言维度"，不是旧格式残留；对象形态是作者显式声明了多语言，
 * 因此在名字解析里压过模组的 lang 键（见 {@code I18n#buildingName}）。
 *
 * <h3>回落链</h3>
 * 玩家 locale → {@value #FALLBACK_LOCALE} → 字面量 → 调用方给的最后兜底（通常是 id）。
 * 中间那一跳复刻原版语义：{@code ClientLanguage.loadFrom} 永远把 {@code en_us} 排在语言列表最前，
 * 缺失的键本来就落它，所以这里不做 {@code zh_tw} → {@code zh_cn} 这类子标签回落。
 *
 * <p>纯逻辑，零 MC 依赖。
 */
public final class LocalizedText {

    private static final String TAG = "LocalizedText";

    /** 原版 {@code ClientLanguage.loadFrom} 的最终语言，缺失键都落它。 */
    public static final String FALLBACK_LOCALE = "en_us";

    private LocalizedText() {}

    /**
     * 解析 JSON 字段里的语言映射。字符串形态返回空 map；对象形态返回其中的字符串键值对。
     *
     * <p>值的迭代顺序被保留（{@link LinkedHashMap}），{@link #literal} 的"取第一个键"依赖它。
     * 非字符串的值记一条警告后跳过，不让整份文件因为一个笔误被丢弃。
     *
     * @param context 报错时用于定位的上下文（通常是建筑/预设 id）
     */
    public static Map<String, String> parseLocaleMap(@Nullable JsonElement el, String context) {
        if (el == null || !el.isJsonObject()) return Map.of();

        JsonObject obj = el.getAsJsonObject();
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                map.put(entry.getKey(), value.getAsString());
            } else {
                Log.warn(TAG, "{}: language '{}' is not a string, skipped", context, entry.getKey());
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * 取"无语言兜底值"：对象形态取 {@value #FALLBACK_LOCALE}，没有则取第一个键；
     * 字符串形态返回其本身。两者都没有时返回 {@code def}。
     *
     * <p>服务端拿不到可靠的玩家语言，凡是服务端自己要用的名字（日志、任务参数、事件参数）
     * 都用这个值。
     */
    public static String literal(@Nullable JsonElement el, String def) {
        if (el == null || el.isJsonNull()) return def;

        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            return el.getAsString();
        }

        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            JsonElement fallback = obj.get(FALLBACK_LOCALE);
            if (fallback != null && fallback.isJsonPrimitive() && fallback.getAsJsonPrimitive().isString()) {
                return fallback.getAsString();
            }
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                JsonElement value = entry.getValue();
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    return value.getAsString();
                }
            }
            return def;
        }

        Log.warn(TAG, "Unsupported localized text shape '{}', falling back to '{}'", el, def);
        return def;
    }

    /**
     * 按回落链选一个名字：玩家 locale → {@value #FALLBACK_LOCALE} → 字面量 → {@code finalFallback}。
     *
     * @param locale        玩家语言码（如 {@code zh_cn}），服务端传 null 即跳过前两跳
     * @param byLocale      {@link #parseLocaleMap} 的结果
     * @param literal       {@link #literal} 的结果
     * @param finalFallback 最后兜底，通常是 id
     */
    public static String resolve(@Nullable String locale,
                                 @Nullable Map<String, String> byLocale,
                                 @Nullable String literal,
                                 String finalFallback) {
        if (byLocale != null && !byLocale.isEmpty()) {
            if (locale != null && !locale.isEmpty()) {
                String exact = byLocale.get(locale);
                if (exact != null && !exact.isEmpty()) return exact;
            }
            String fallback = byLocale.get(FALLBACK_LOCALE);
            if (fallback != null && !fallback.isEmpty()) return fallback;
        }

        if (literal != null && !literal.isEmpty()) return literal;
        return finalFallback;
    }
}
