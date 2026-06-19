package org.magiccolony.core.event;

import org.magiccolony.core.types.ResourceId;

/** Emitted when a colony resource drops below its threshold. */
public record ResourceLow(ResourceId resource, int current, int threshold) {
    @Override public String toString() { return "ResourceLow[" + resource + " " + current + "/" + threshold + "]"; }
}
