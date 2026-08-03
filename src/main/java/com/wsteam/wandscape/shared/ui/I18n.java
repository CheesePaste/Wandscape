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

    /** Translatable name with fallback text and placeholders ({@code {0}}, {@code {1}}, ...). */
    public static MutableComponent name(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(key, fallback, args);
    }
}
