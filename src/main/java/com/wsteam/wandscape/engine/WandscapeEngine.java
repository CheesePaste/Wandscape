package com.wsteam.wandscape.engine;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.ecs.World;

/**
 * Singleton holder for the engine {@link World} instance.
 * Bootstrap happens once in {@link com.wsteam.wandscape.engine.bootstrap.EngineBootstrap}.
 *
 * <p>All MC-side modules access the engine world through this class.
 * None of them call {@code Engine.bootstrap()} directly.
 */
public final class WandscapeEngine {
    private static World world;

    private WandscapeEngine() {}

    public static void setWorld(World w) {
        if (world != null) {
            throw new IllegalStateException("World already bootstrapped");
        }
        world = w;
    }

    @Nullable
    public static World getWorld() {
        return world;
    }
}
