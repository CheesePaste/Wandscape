package com.wsteam.wandscape.core.event;

import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.core.boundary.EventBus;

import java.util.*;
import java.util.function.Consumer;

/**
 * Simple in-memory event bus. Events are queued during tick and
 * dispatched in batch at tick end, preventing intra-tick side-effects.
 *
 * <p>Unsubscribe is deferred: handlers are only removed at the end of
 * {@link #dispatch()}, so a handler that unsubscribes itself during
 * dispatch still receives events emitted in the same tick.
 */
public class SimpleEventBus implements EventBus {

    private final Map<Class<?>, List<Consumer<Object>>> subscribers = new HashMap<>();
    private final List<Object> queue = new ArrayList<>();
    private final List<Subscription> deferredRemovals = new ArrayList<>();

    private static final String TAG = "EventBus";

    @Override
    @SuppressWarnings("unchecked")
    public <T> void emit(T event) {
        queue.add(event);
        Log.debug(TAG, "emit %s", event);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Subscription subscribe(Class<T> type, Consumer<T> handler) {
        Consumer<Object> erased = (Consumer<Object>) handler;
        subscribers.computeIfAbsent(type, k -> new ArrayList<>()).add(erased);
        Log.debug(TAG, "subscribe(%s) - %d handlers total",
                type.getSimpleName(),
                subscribers.get(type).size());
        return new Subscription(type, erased);
    }

    @Override
    public void unsubscribe(Subscription sub) {
        if (sub != null) {
            deferredRemovals.add(sub);
            Log.debug(TAG, "unsubscribe deferred (%s)", sub.eventType().getSimpleName());
        }
    }

    /** Dispatch all queued events to subscribers. Called at end of World.tick(). */
    @SuppressWarnings("unchecked")
    public void dispatch() {
        // 1. Deliver queued events
        if (!queue.isEmpty()) {
            Log.debug(TAG, "dispatch begin - %d queued events", queue.size());
            List<Object> toDispatch = new ArrayList<>(queue);
            queue.clear();

            for (Object event : toDispatch) {
                List<Consumer<Object>> handlers = subscribers.get(event.getClass());
                if (handlers != null) {
                    Log.debug(TAG, "dispatch %s → %d handlers", event, handlers.size());
                    for (Consumer<Object> handler : handlers) {
                        handler.accept(event);
                    }
                } else {
                    Log.debug(TAG, "dispatch %s → no handlers", event);
                }
            }
        }

        // 2. Execute deferred removals (take effect after dispatch)
        if (!deferredRemovals.isEmpty()) {
            Log.debug(TAG, "processing %d deferred unsubscribes", deferredRemovals.size());
            for (Subscription sub : deferredRemovals) {
                List<Consumer<Object>> handlers = subscribers.get(sub.eventType());
                if (handlers != null) {
                    handlers.remove(sub.handler());
                    if (handlers.isEmpty()) {
                        subscribers.remove(sub.eventType());
                    }
                }
            }
            deferredRemovals.clear();
        }
    }

    /** Number of queued events. */
    public int queueSize() {
        return queue.size();
    }
}
