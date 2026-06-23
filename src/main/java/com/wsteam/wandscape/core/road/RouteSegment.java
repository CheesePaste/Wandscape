package com.wsteam.wandscape.core.road;

/**
 * A segment of a transport route — a straight line from
 * ({@link #fromX}, {@link #fromY}, {@link #fromZ}) to
 * ({@link #toX}, {@link #toY}, {@link #toZ}).
 *
 * <p>When {@link #onRoad()} is {@code true}, the item moves at 2× speed
 * (10 ticks/block). Otherwise at 1× speed (20 ticks/block).
 *
 * <p>Core-friendly — no MC dependencies. The engine layer
 * converts these coordinates to {@code Vec3} / {@code BlockPos}.
 */
public record RouteSegment(
        double fromX, double fromY, double fromZ,
        double toX, double toY, double toZ,
        boolean onRoad
) {}
