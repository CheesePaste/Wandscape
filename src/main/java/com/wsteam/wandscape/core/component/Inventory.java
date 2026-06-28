package com.wsteam.wandscape.core.component;

import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.types.ResourceStack;

import java.util.*;
/**
 * NPC inventory managed by the core layer.
 * Simple list-based storage with capacity limit.
 */
public class Inventory {

    private final List<ResourceStack> items;
    private final int capacity;

    public Inventory(int capacity) {
        this.items = new ArrayList<>();
        this.capacity = capacity;
    }

    public List<ResourceStack> items() {
        return Collections.unmodifiableList(items);
    }

    public int capacity() { return capacity; }

    public int usedSlots() { return items.size(); }

    public boolean isFull() { return items.size() >= capacity; }

    /** Add a resource stack. Merges with existing stacks of the same type when possible. */
    public boolean add(ResourceStack stack) {
        if (stack.isEmpty()) return true;

        // Try to merge into an existing stack
        for (int i = 0; i < items.size(); i++) {
            ResourceStack existing = items.get(i);
            if (existing.resource().equals(stack.resource())) {
                items.set(i, existing.add(stack.amount()));
                return true;
            }
        }

        // Need a new slot
        if (items.size() >= capacity) return false;
        items.add(stack);
        return true;
    }

    /** Remove up to the given amount. Returns the actual amount removed. */
    public int remove(ResourceId resource, int amount) {
        int remaining = amount;
        Iterator<ResourceStack> iter = items.iterator();
        while (iter.hasNext() && remaining > 0) {
            ResourceStack stack = iter.next();
            if (stack.resource().equals(resource)) {
                if (stack.amount() <= remaining) {
                    remaining -= stack.amount();
                    iter.remove();
                } else {
                    items.set(items.indexOf(stack), stack.add(-remaining));
                    remaining = 0;
                }
            }
        }
        return amount - remaining;
    }

    /** Count total amount of a resource. */
    public int count(ResourceId resource) {
        int total = 0;
        for (ResourceStack stack : items) {
            if (stack.resource().equals(resource)) {
                total += stack.amount();
            }
        }
        return total;
    }

    /** Check if we have at least the given amount. */
    public boolean hasEnough(ResourceId resource, int amount) {
        return count(resource) >= amount;
    }

    @Override
    public String toString() {
        return "Inventory[" + items.size() + "/" + capacity + "]";
    }
}
