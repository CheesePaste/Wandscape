package com.wsteam.wandscape.road.engine;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * Block tag constants for Wandscape.
 *
 * <p>Tags are defined as JSON under {@code data/wandscape/tags/block/}
 * and can be extended by datapacks.
 */
public final class WandscapeTags {

    private WandscapeTags() {}

    public static final class Blocks {
        private Blocks() {}

        /** Blocks that count as player-built custom roads for lazy blob discovery. */
        public static final TagKey<Block> CUSTOM_ROADS = TagKey.create(
                Registries.BLOCK,
                ResourceLocation.fromNamespaceAndPath("wandscape", "custom_roads"));
    }
}
