package com.wsteam.wandscape.engine;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.engine.boundary.AsyncTransformExecutor;
import com.wsteam.wandscape.engine.boundary.WandscapeBlockInteractExecutor;
import com.wsteam.wandscape.engine.boundary.WandscapeMovementOps;
import com.wsteam.wandscape.engine.boundary.WandscapeRitualOps;
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
    static WandscapeRitualOps ritualOps;
    @Nullable
    static WandscapeBlockInteractExecutor blockInteractExec;
    static boolean manaDebug;
    @Nullable
    static net.minecraft.server.level.ServerPlayer manaDebugTarget;

    public static boolean isManaDebug() { return manaDebug; }
    public static void setManaDebug(boolean v) { manaDebug = v; }

    @Nullable
    public static net.minecraft.server.level.ServerPlayer getManaDebugTarget() { return manaDebugTarget; }
    public static void setManaDebugTarget(@Nullable net.minecraft.server.level.ServerPlayer p) { manaDebugTarget = p; }

    @Nullable
    private static WandscapeMovementOps movementOps;
    @Nullable
    private static BlueprintConfigLoader blueprintConfigLoader;

    @Nullable
    private static TaskPoolSavedData taskPoolSavedData;

    private WandscapeEngine() {}

    public static void setWorld(World w) {
        if (world != null) {
            throw new IllegalStateException("World already bootstrapped");
        }
        world = w;
    }

    public static void reset() {
        world = null;
        asyncExec = null;
        movementOps = null;
        // blueprintConfigLoader: intentionally NOT nulled — it's a permanent singleton
        // whose internal definitions map is managed by WandscapeDataLoader resource reload.
        // Nulling it would break DSL blueprint registration on world re-entry.
    }

    @Nullable
    public static World getWorld() {
        return world;
    }

    public static void setAsyncExecutor(AsyncTransformExecutor exec) { asyncExec = exec; }

    @Nullable
    public static AsyncTransformExecutor getAsyncExecutor() { return asyncExec; }

    public static void setRitualOps(WandscapeRitualOps ops) { ritualOps = ops; }

    @Nullable
    public static WandscapeRitualOps getRitualOps() { return ritualOps; }

    public static void setBlockInteractExec(WandscapeBlockInteractExecutor exec) { blockInteractExec = exec; }

    @Nullable
    public static WandscapeBlockInteractExecutor getBlockInteractExec() { return blockInteractExec; }

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

    @Nullable
    public static TaskPoolSavedData getTaskPoolSavedData() { return taskPoolSavedData; }
    public static void setTaskPoolSavedData(@Nullable TaskPoolSavedData v) { taskPoolSavedData = v; }
}
