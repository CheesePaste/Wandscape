package com.wsteam.wandscape.shared.ui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Client-side localization helper. Builds translatable components whose
 * fallback text is shown only when the lang key is missing on the client.
 */
public final class I18n {

    private I18n() {}

    /** Translatable name with fallback text. */
    public static MutableComponent name(String key, String fallback) {
        return Component.translatableWithFallback(key, fallback);
    }

    /** Translatable name with fallback text and placeholders ({@code %s}, {@code %1$s}, ...). */
    public static MutableComponent name(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(key, fallback, args);
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
