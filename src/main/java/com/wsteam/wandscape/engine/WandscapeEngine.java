package com.wsteam.wandscape.engine;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.engine.boundary.AsyncTransformExecutor;

/**
 * Singleton holder for the engine {@link World} instance.
 * Bootstrap happens once in {@link com.wsteam.wandscape.engine.bootstrap.EngineBootstrap}.
 *
 * <p>All MC-side modules access the engine world through this class.
 * None of them call {@code CoreBootstrap.bootstrap()} directly.
 */
public final class WandscapeEngine {
    private static World world;
    @Nullable
    static AsyncTransformExecutor asyncExec;

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

    public static void setAsyncExecutor(AsyncTransformExecutor exec) { asyncExec = exec; }

    @Nullable
    public static AsyncTransformExecutor getAsyncExecutor() { return asyncExec; }
}
