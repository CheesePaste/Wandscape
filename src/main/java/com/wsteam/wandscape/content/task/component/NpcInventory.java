package com.wsteam.wandscape.content.task.component;

import com.wsteam.wandscape.content.task.types.ResourceId;
import com.wsteam.wandscape.content.task.types.ResourceStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
/**
 * NPC inventory managed by the core layer.
 * Simple list-based storage with capacity limit.
 */
public class NpcInventory {

    private final List<ResourceStack> items;
    private final int capacity;

    public NpcInventory(int capacity) {
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

        // Normalize: strip block state properties so that
        // oak_door[facing=north] and oak_door[facing=south] merge into one stack.
        ResourceStack toAdd = stack;
        ResourceId cleanId = stack.resource().stripBlockStateSuffix();
        if (!cleanId.equals(stack.resource())) {
            toAdd = new ResourceStack(cleanId, stack.amount());
        }

        // Try to merge into an existing stack
        for (int i = 0; i < items.size(); i++) {
            ResourceStack existing = items.get(i);
            if (existing.resource().equals(toAdd.resource())) {
                items.set(i, existing.add(toAdd.amount()));
                return true;
            }
        }

        // Need a new slot
        if (items.size() >= capacity) return false;
        items.add(toAdd);
        return true;
    }

    /** Remove up to the given amount. Returns the actual amount removed. */
    public int remove(ResourceId resource, int amount) {
        int remaining = amount;
        Iterator<ResourceStack> iter = items.iterator();
        while (iter.hasNext() && remaining > 0) {
            ResourceStack stack = iter.next();
            if (stack.resource().equals(resource) || stack.resource().equals(resource.stripBlockStateSuffix())) {
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
            if (stack.resource().equals(resource)||stack.resource().equals(resource.stripBlockStateSuffix())) {
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
