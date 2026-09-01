package com.wsteam.wandscape.content.task.ecs;

/**
 * A stateless system that processes the world each tick.
 * Systems are executed in registration order by World.tick().
 */
@FunctionalInterface
public interface EcsSystem {
    void update(World world, float delta);
}
