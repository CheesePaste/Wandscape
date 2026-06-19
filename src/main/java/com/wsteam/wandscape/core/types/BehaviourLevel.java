package com.wsteam.wandscape.core.types;

/**
 * A behaviour level value from 1 to 5.
 */
public record BehaviourLevel(int value) {

    public static final int MIN = 1;
    public static final int MAX = 5;

    public BehaviourLevel {
        if (value < MIN || value > MAX) {
            throw new IllegalArgumentException("BehaviourLevel must be between " + MIN + " and " + MAX + ", got: " + value);
        }
    }

    public static BehaviourLevel of(int value) {
        return new BehaviourLevel(value);
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
