package com.wsteam.wandscape.content.npc.nav;
import com.wsteam.wandscape.content.task.ecs.World;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * Amphibious node evaluator for Wandscape walking entities (NPCs and tourists).
 *
 * <p>{@link AmphibiousNodeEvaluator} extends {@link net.minecraft.world.level.pathfinder.WalkNodeEvaluator}
 * with vertical water neighbours, so a mob that fell into water can path back up to the surface /
 * onto a bank. A plain {@code WalkNodeEvaluator} only explores horizontal neighbours and has no
 * way out of deep water — pathfinding fails and the mob falls into the teleport fallback
 * (NPC self_teleport ritual / tourist rescue teleport).
 *
 * <p>Vanilla {@link AmphibiousNodeEvaluator#prepare} inflates the WALKABLE cost to 6.0 so the
 * creature prefers swimming (turtle/axolotl behaviour). Wandscape NPCs are land walkers that
 * should only treat water as passable terrain, so we restore WALKABLE / WATER_BORDER to neutral
 * (0) — walking stays the default, and swimming is only chosen when a path actually crosses water.
 */
public class WandscapeNodeEvaluator extends AmphibiousNodeEvaluator {

    public WandscapeNodeEvaluator() {
        super(false); // don't prefer shallow swimming
    }

    @Override
    public void prepare(PathNavigationRegion level, Mob mob) {
        super.prepare(level, mob);
        // Neutral costs: walking costs the same as swimming. Vanilla's 6.0 would make every
        // path detour through water; 0.0 keeps land preferred and water passable.
        // (done() in AmphibiousNodeEvaluator restores the pre-prepare values, so this is clean.)
        mob.setPathfindingMalus(PathType.WALKABLE, 0.0F);
        mob.setPathfindingMalus(PathType.WATER_BORDER, 0.0F);
    }
}
