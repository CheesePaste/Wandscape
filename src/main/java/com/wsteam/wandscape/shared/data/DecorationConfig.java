package com.wsteam.wandscape.shared.data;

/** Decoration building config. Provides radius-based stat radiation to nearby functional buildings. */
public record DecorationConfig(int radius) {
    public DecorationConfig {
        if (radius <= 0) radius = 8;
    }
}
