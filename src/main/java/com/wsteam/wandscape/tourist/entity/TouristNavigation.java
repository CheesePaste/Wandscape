package com.wsteam.wandscape.tourist.entity;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Custom navigation for {@link TouristEntity} that enables automatic door opening.
 *
 * <p>Vanilla {@link GroundPathNavigation#createPathFinder(int)} creates a fresh
 * {@link WalkNodeEvaluator} each path computation, which resets {@code canOpenDoors}
 * to {@code false}. This override sets it to {@code true} on every newly created
 * evaluator so that {@link net.minecraft.world.entity.ai.goal.OpenDoorGoal}
 * can detect and open wooden doors during indoor micro-navigation.
 */
public class TouristNavigation extends GroundPathNavigation {

    public TouristNavigation(Mob mob, Level level) {
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
