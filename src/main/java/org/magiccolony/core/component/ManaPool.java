package org.magiccolony.core.component;

/**
 * Mana resource for an NPC or mana-using block.
 * Mutable: current ticks up/down each frame.
 */
public class ManaPool {

    private int current;
    private final int max;
    private final int regenPerTick;

    public ManaPool(int current, int max, int regenPerTick) {
        this.current = Math.min(current, max);
        this.max = max;
        this.regenPerTick = regenPerTick;
    }

    public int current() { return current; }
    public int max() { return max; }
    public int regenPerTick() { return regenPerTick; }

    /** Regenerate mana, capping at max. */
    public void regen() {
        current = Math.min(current + regenPerTick, max);
    }

    /** Try to consume mana. Returns false if insufficient. */
    public boolean consume(int amount) {
        if (current < amount) return false;
        current -= amount;
        return true;
    }

    /** Add mana (e.g., from external source), capping at max. */
    public void add(int amount) {
        current = Math.min(current + amount, max);
    }

    public boolean isFull() { return current >= max; }
    public boolean isEmpty() { return current <= 0; }

    @Override
    public String toString() {
        return "ManaPool[" + current + "/" + max + " (+" + regenPerTick + "/tick)]";
    }
}
