package org.magiccolony.core.event;

import org.magiccolony.core.types.GridPos;

/** Emitted when hostile mobs are detected near the colony. */
public record MobNearby(GridPos pos, int count) {
    @Override public String toString() { return "MobNearby[" + pos + " count=" + count + "]"; }
}
