package com.wsteam.wandscape.core.ecs;

/**
 * A stateless system that processes the world each tick.
 * Systems are executed in registration order by World.tick().
 */
@FunctionalInterface
public interface System {
    void update(World world, float delta);
}
