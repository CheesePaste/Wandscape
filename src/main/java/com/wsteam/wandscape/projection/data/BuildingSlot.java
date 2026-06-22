package com.wsteam.wandscape.projection.data;

/**
 * Lightweight DTO for a single building slot in the projection mode scroll list.
 * Sent from server to client in {@code ProjectionEnterResponsePacket}.
 *
 * @param id          building type identifier (matches {@code BuildingConfig.id()})
 * @param displayName localized display name
 * @param category    building category (e.g. "basic", "storage", "node", "production")
 */
public record BuildingSlot(
        String id,
        String displayName,
        String category
) {}
