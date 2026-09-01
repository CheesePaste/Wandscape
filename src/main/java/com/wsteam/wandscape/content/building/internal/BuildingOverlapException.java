package com.wsteam.wandscape.content.building.internal;

/**
 * Thrown when registering a building whose AABB overlaps with an existing building.
 */
public class BuildingOverlapException extends RuntimeException {
    public BuildingOverlapException(String message) {
        super(message);
    }
}
