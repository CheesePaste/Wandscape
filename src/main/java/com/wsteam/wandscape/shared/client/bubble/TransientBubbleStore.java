package com.wsteam.wandscape.shared.client.bubble;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

/**
 * Client-side store of transient event bubbles (purchase / service feedback)
 * keyed by entity UUID. Fed by server→client packets, consumed by
 * {@link SpeechBubbleRenderer}. Entries auto-expire after {@link #LIFETIME_TICKS}.
 */
public final class TransientBubbleStore {

    /** No icon — just the satisfaction bar. */
    public static final int ICON_NONE = 0;
    /** Registry item icon (iconId = registry id). */
    public static final int ICON_ITEM = 1;
    /** Element icon (iconId = element id). */
    public static final int ICON_ELEMENT = 2;

    /** How long an event bubble stays visible, in ticks. */
    public static final int LIFETIME_TICKS = 80;
    /** Fade-in / fade-out duration (ticks) and satisfaction fill animation length. */
    public static final int FADE_IN_TICKS = 10;
    public static final int FADE_OUT_TICKS = 15;
    public static final int SAT_ANIM_TICKS = 40;

    private static final Map<UUID, Event> EVENTS = new ConcurrentHashMap<>();

    /** One transient bubble event. {@code startTick} is the entity tickCount when received. */
    public record Event(int iconKind, @Nullable String iconId, int count,
                        int satBefore, int satAfter, int startTick) {}

    private TransientBubbleStore() {}

    /** Record an event for the given entity (restarts its bubble immediately). */
    public static void trigger(UUID entityUuid, int iconKind, @Nullable String iconId,
                               int count, int satBefore, int satAfter, int currentTick) {
        EVENTS.put(entityUuid, new Event(iconKind, iconId, count, satBefore, satAfter, currentTick));
    }

    /** Active event for the entity, or null if none / expired. */
    @Nullable
    public static Event get(UUID entityUuid, int currentTick) {
        Event e = EVENTS.get(entityUuid);
        if (e == null) return null;
        if (currentTick - e.startTick() >= LIFETIME_TICKS) {
            EVENTS.remove(entityUuid);
            return null;
        }
        return e;
    }

    /** Fade in/out alpha for an event (shared by bubble and satisfaction bar). */
    public static float alpha(int elapsed) {
        if (elapsed < FADE_IN_TICKS) return elapsed / (float) FADE_IN_TICKS;
        if (elapsed > LIFETIME_TICKS - FADE_OUT_TICKS) {
            return (LIFETIME_TICKS - elapsed) / (float) FADE_OUT_TICKS;
        }
        return 1F;
    }

    /** Satisfaction fill fraction (0..1), smoothly animated from the before-value up/down to the after-value. */
    public static float satFill(int elapsed, int satBefore, int satAfter) {
        float p = (elapsed - FADE_IN_TICKS) / (float) SAT_ANIM_TICKS;
        p = Math.clamp(p, 0F, 1F);
        float eased = p * p * (3F - 2F * p); // smoothstep — gentle, clearly gradual
        int current = Math.round(satBefore + (satAfter - satBefore) * eased);
        return Math.clamp(current / 100F, 0F, 1F);
    }
}
