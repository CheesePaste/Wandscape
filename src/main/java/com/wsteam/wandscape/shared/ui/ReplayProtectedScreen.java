package com.wsteam.wandscape.shared.ui;

/**
 * Marker for Wandscape screens that must never open during ReplayMod/ReforgedPlay
 * playback (checked by {@link ReplayScreenGuard}).
 *
 * <p>{@code MedievalScreen} subclasses and the container-based screens
 * (e.g. the warehouse) implement this so the guard stays effective even though
 * they no longer share a common base class.
 */
public interface ReplayProtectedScreen {
}
