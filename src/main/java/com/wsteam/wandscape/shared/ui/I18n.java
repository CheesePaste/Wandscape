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
}
