package com.wsteam.wandscape.core.types;

/**
 * A specific resource type with an amount.
 */
public record ResourceStack(ResourceId resource, int amount) {

    public ResourceStack {
        if (amount < 0) {
            throw new IllegalArgumentException("Amount cannot be negative: " + amount);
        }
    }

    public ResourceStack withAmount(int newAmount) {
        return new ResourceStack(resource, newAmount);
    }

    public ResourceStack add(int delta) {
        return new ResourceStack(resource, amount + delta);
    }

    public boolean isEmpty() {
        return amount == 0;
    }

    @Override
    public String toString() {
        return amount + "x " + resource.id();
    }
}
