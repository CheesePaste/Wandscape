package com.wsteam.wandscape.engine.nav;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
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
 * <p>The A* node budget is derived by vanilla as
 * {@code FOLLOW_RANGE × 16}, so raising each entity's
 * {@code Attributes.FOLLOW_RANGE} scales its long-distance search budget.
 */
public class WandscapeNavigation extends GroundPathNavigation {

    public WandscapeNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new WalkNodeEvaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        this.nodeEvaluator.setCanOpenDoors(true);
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }
}
