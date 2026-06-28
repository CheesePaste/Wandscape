package com.wsteam.wandscape.core.boundary;

import java.util.function.Consumer;
/**
 * Event bus for domain events.
 * Events are queued during tick and dispatched at tick end
 * so Systems cannot see each other's events within the same tick.
 */
public interface EventBus {

    /** Queue an event for dispatch at end of tick. */
    <T> void emit(T event);

    /** Subscribe to a specific event type. Returns a handle for later unsubscribe. */
    <T> Subscription subscribe(Class<T> type, Consumer<T> handler);

    /**
     * Queue a handler for removal. The removal takes effect at the end of
     * the current {@code dispatch()} call — handlers still receive events
     * emitted in the same tick before the unsubscribe call.
     */
    void unsubscribe(Subscription sub);

    /** Handle returned by {@link #subscribe}. */
    record Subscription(Class<?> eventType, Consumer<?> handler) {}
}
