package com.wsteam.wandscape.content.task.types;

/**
 * Data-driven block type identifier (e.g., "minecraft:stone", "magiccolony:glowstone").
 * Core layer only defines well-known constants; rest come from the adapter layer.
 */
public record BlockType(String id) {

    public static final BlockType AIR = new BlockType("minecraft:air");
    public static final BlockType STONE = new BlockType("minecraft:stone");
    public static final BlockType DIRT = new BlockType("minecraft:dirt");
    public static final BlockType GLASS = new BlockType("minecraft:glass");
    public static final BlockType STONE_BRICKS = new BlockType("minecraft:stone_bricks");
    public static final BlockType OAK_PLANKS = new BlockType("minecraft:oak_planks");
    public static final BlockType BOOKSHELF = new BlockType("minecraft:bookshelf");
    public static final BlockType IRON_ORE = new BlockType("minecraft:iron_ore");

    @Override
    public String toString() {
        return id;
    }
}
