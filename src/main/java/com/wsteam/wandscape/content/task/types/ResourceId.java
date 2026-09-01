package com.wsteam.wandscape.content.task.types;

/**
 * Identifier for a resource type (e.g., "stone_bricks", "glass", "iron_ingot").
 * Data-driven: the core layer only defines well-known constants; adapters can register more.
 */
public record ResourceId(String id) {

    // Well-known resource IDs
    public static final ResourceId STONE_BRICKS = new ResourceId("stone_bricks");
    public static final ResourceId GLASS = new ResourceId("glass");
    public static final ResourceId IRON_INGOT = new ResourceId("iron_ingot");
    public static final ResourceId WOOD = new ResourceId("wood");
    public static final ResourceId STONE = new ResourceId("stone");
    public static final ResourceId DIRT = new ResourceId("dirt");
    public static final ResourceId WHEAT = new ResourceId("wheat");
    public static final ResourceId MAGIC_CRYSTAL = new ResourceId("magic_crystal");

    @Override
    public String toString() {
        return id;
    }

    public ResourceId stripBlockStateSuffix() {
        return new ResourceId(id.replaceAll("\\[.*?\\]", "").trim());
    }
}
