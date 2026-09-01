package com.wsteam.wandscape.foundation.nav;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Shared navigation for all Wandscape entities (NPCs and tourists).
 *
 * <p>Based on vanilla {@link GroundPathNavigation} with automatic door opening.
 * Vanilla {@link GroundPathNavigation#createPathFinder(int)} creates a fresh
 * {@link WalkNodeEvaluator} per path computation, which resets
 * {@code canOpenDoors} to {@code false}; this override keeps it enabled so
 * entities can open wooden doors during indoor micro-navigation.
 *
 * <p>Uses {@link WandscapeNodeEvaluator} (an {@link AmphibiousNodeEvaluator} with neutral
 * land/water costs) so land-walking entities can also swim: a mob that falls into water can
 * path back to the surface and onto a bank, instead of the plain {@link WalkNodeEvaluator}
 * failing (it has no vertical water neighbours) and forcing the teleport fallback.
 *
 * <p>The A* node budget is derived by vanilla as
 * {@code FOLLOW_RANGE × 16}, so raising each entity's
 * {@code Attributes.FOLLOW_RANGE} scales its long-distance search budget.
 */
public class WandscapeNavigation extends GroundPathNavigation {

    public WandscapeNavigation(Mob mob, Level level) {
        super(mob, level);
        // Explicit swim capability. {@link net.minecraft.world.entity.ai.goal.FloatGoal} also
        // sets this via setCanFloat(true), but being explicit here makes water behaviour robust
        // regardless of goal-registration order.
        setCanFloat(true);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new WandscapeNodeEvaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        this.nodeEvaluator.setCanOpenDoors(true);
        this.nodeEvaluator.setCanFloat(true);
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }
}
