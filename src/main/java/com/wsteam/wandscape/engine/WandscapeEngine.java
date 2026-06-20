package com.wsteam.wandscape.engine;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.engine.boundary.AsyncTransformExecutor;
import com.wsteam.wandscape.engine.boundary.WandscapeMovementOps;
import com.wsteam.wandscape.engine.source.blueprint.BlueprintConfigLoader;

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
    @Nullable
    private static WandscapeMovementOps movementOps;
    @Nullable
    private static BlueprintConfigLoader blueprintConfigLoader;

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

    public static void setMovementOps(WandscapeMovementOps ops) { movementOps = ops; }

    @Nullable
    public static WandscapeMovementOps getMovementOps() { return movementOps; }

    /** Set the blueprint config loader singleton (called from Wandscape constructor). */
    public static void setBlueprintConfigLoader(BlueprintConfigLoader loader) {
        blueprintConfigLoader = loader;
    }

    @Nullable
    public static BlueprintConfigLoader getBlueprintConfigLoader() {
        return blueprintConfigLoader;
    }
}
