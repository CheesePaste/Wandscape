package com.wsteam.wandscape.road.core;

import java.util.UUID;
/**
 * Minimal snapshot of a building used as input to road planning.
 * Engine layer extracts this from {@code BuildingSavedData}.
 * Core layer uses it without any MC dependency.
 */
public record RoadBuildingData(UUID id, int x, int y, int z) {
}
