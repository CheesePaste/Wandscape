package org.magiccolony.core.ecs;

import org.magiccolony.core.ecs.World;

/**
 * A stateless system that processes the world each tick.
 * Systems are executed in registration order by World.tick().
 */
@FunctionalInterface
public interface System {
    void update(World world, float delta);
}
