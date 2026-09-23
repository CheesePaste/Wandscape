package com.wsteam.wandscape.foundation.ui;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.foundation.util.LocalizedText;

import javax.annotation.Nullable;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Client-side localization helper. Builds translatable components whose
 * fallback text is shown only when the lang key is missing on the client.
 */
public final class I18n {

    private I18n() {}

    /**
     * 客户端当前语言码（如 {@code zh_cn}），服务端与语言管理器缺席时返回 null。
     *
     * <p>服务端没有可靠的玩家语言——要按玩家语言解析的地方都走客户端（数据包内容已全量同步到
     * 客户端），只有上屏回执这类必须在服务端拼串才用 {@code ServerPlayer#getLanguage()}。
     */
    @Nullable
    public static String clientLocale() {
        if (!net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) return null;
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null) return null;
            var languageManager = mc.getLanguageManager();
            return languageManager != null ? languageManager.getSelected() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Resolves a localized building name.
     * Supports canonical full ID (e.g. "default:warehouse1", "oriental:bamboo_house")
     * as well as short/raw ID ("warehouse1").
     *
     * <p>Checks:
     * 1. 建筑 JSON 自己声明了多语言（{@code display_name} 写成对象）——直接用它，压过下面的键
     * 2. Package-scoped key using dot separator if namespace present (e.g. "building.wandscape.oriental.bamboo_house")
     * 3. Standard mod key: "building.wandscape.<rawId>" (e.g. "building.wandscape.warehouse1")
     * 4. Fallback string (e.g. config display_name)
     *
     * <p>第 1 跳是给数据包作者的出口：第三方包的建筑 id 撞上模组内置 id 时（都叫 bakery），
     * 键会把作者的名字吃掉；显式写了对象形态的包不再受影响。
     */
    public static MutableComponent buildingName(@Nullable String buildingTypeId, @Nullable String fallback) {
        if (buildingTypeId == null || buildingTypeId.isEmpty()) {
            return Component.literal(fallback != null ? fallback : "");
        }
        String rawId = buildingTypeId.contains(":")
                ? buildingTypeId.substring(buildingTypeId.indexOf(':') + 1)
                : buildingTypeId;
        String fb = (fallback != null && !fallback.isEmpty()) ? fallback : rawId;

        // 数据包自带的多语言名字优先于模组 lang 键。
        var config = com.wsteam.wandscape.content.building.internal.BuildingConfigLoader
                .getInstance().get(buildingTypeId);
        if (config != null && !config.displayNames().isEmpty()) {
            return Component.literal(config.displayNameFor(clientLocale()));
        }

        try {
            var lang = net.minecraft.locale.Language.getInstance();
            if (lang != null) {
                if (buildingTypeId.contains(":")) {
                    String scopedKey = "building.wandscape." + buildingTypeId.replace(':', '.');
                    if (lang.has(scopedKey)) {
                        return Component.translatableWithFallback(scopedKey, fb);
                    }
                }
                String standardKey = "building.wandscape." + rawId;
                if (lang.has(standardKey)) {
                    return Component.translatableWithFallback(standardKey, fb);
                }
            }
        } catch (Throwable ignored) {}

        return Component.translatableWithFallback("building.wandscape." + rawId, fb);
    }

    /**
     * 数据包提供的名字（建筑包名与描述、道路预设名）。与 {@link #buildingName} 同一套规则：
     * 作者写了对象形态就用它，否则走 {@code langKey} → 字面量。
     *
     * @param langKey  模组 lang 键，可为 null（那时只用字面量）
     * @param literal  无语言兜底值
     * @param byLocale 语言映射，字符串形态时为空
     */
    public static MutableComponent datapackName(@Nullable String langKey, @Nullable String literal,
                                                @Nullable Map<String, String> byLocale) {
        String fb = literal != null ? literal : "";
        if (byLocale != null && !byLocale.isEmpty()) {
            return Component.literal(LocalizedText.resolve(clientLocale(), byLocale, literal, fb));
        }
        String key = (langKey != null && !langKey.isEmpty()) ? langKey : fb;
        return Component.translatableWithFallback(key, fb);
    }

    /** Translatable name with fallback text. */
    public static MutableComponent name(String key, String fallback) {
        return Component.translatableWithFallback(key, fallback);
    }

    /** Translatable name with fallback text and placeholders ({@code %s}, {@code %1$s}, ...). */
    public static MutableComponent name(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(key, fallback, sanitize(args));
    }

    /**
     * MC's network codec only accepts Number / Boolean / String / Component arg values for a
     * translatable (see {@code TranslatableContents#filterAllowedArguments}); anything else
     * (Path, BlockPos, ...) fails the server&rarr;client chat encode and kicks the player. Coerce
     * such args to literal components so every {@code sendSystemMessage} / {@code ScreenFeedbackPacket}
     * value survives the wire.
     */
    static Object[] sanitize(Object[] args) {
        Object[] filtered = null;
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (!isNetworkSafe(arg)) {
                if (filtered == null) filtered = args.clone();
                filtered[i] = Component.literal(String.valueOf(arg));
            }
        }
        return filtered == null ? args : filtered;
    }

    private static boolean isNetworkSafe(Object arg) {
        return arg instanceof Number || arg instanceof Boolean || arg instanceof String || arg instanceof Component;
    }

    /** Safe translatable string extraction with test-safe fallback. */
    public static String string(String key, String fallback, Object... args) {
        try {
            if (net.minecraft.locale.Language.getInstance() != null) {
                return name(key, fallback, args).getString();
            }
        } catch (Throwable ignored) {}
        if (args != null && args.length > 0) {
            try {
                return String.format(fallback, args);
            } catch (Exception ignored) {}
        }
        return fallback;
    }
}
