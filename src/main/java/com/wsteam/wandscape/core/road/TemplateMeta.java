package com.wsteam.wandscape.core.road;

import java.util.List;

/**
 * Metadata for a road template NBT file.
 * Describes the template's entry/exit points, dimensions, and selection weight.
 *
 * @param id          unique identifier (e.g. "wandscape:road/straight")
 * @param templateRef resource location of the NBT file (e.g. "village/plains/streets/straight_01")
 * @param width       road width in blocks
 * @param budgetCost  how much budget this template consumes when placed
 * @param weight      relative weight for random selection (higher = more frequent)
 * @param entries     entry points (can enter from any of these)
 * @param exits       exit points (continue extension from any of these after placement)
 */
public record TemplateMeta(
        String id,
        String templateRef,
        int width,
        int budgetCost,
        int weight,
        List<EntryExit> entries,
        List<EntryExit> exits) {
}
