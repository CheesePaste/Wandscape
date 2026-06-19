package org.magiccolony.core.ecs;

import java.util.List;

/**
 * Storage contract for one component type T.
 * Each component type gets its own store in World.
 * <p>
 * {@link #entities()} returns a sorted list (cached until next write)
 * to enable fast intersection-based queries.
 */
public interface ComponentStore<T> {

    /** Add or overwrite the component for the given entity. */
    void add(long entity, T component);

    /** Remove the component from the given entity (no-op if absent). */
    void remove(long entity);

    /** Get the component, or null if the entity doesn't have it. */
    T get(long entity);

    /** Check whether the entity has this component. */
    boolean has(long entity);

    /**
     * All entity IDs that currently hold this component.
     * Returned list is sorted and may be cached - do not mutate.
     */
    List<Long> entities();
}
