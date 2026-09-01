package com.wsteam.wandscape.engine;

import com.wsteam.wandscape.content.building.executor.AltarCastExecutor;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.engine.bootstrap.EngineBootstrap;
import com.wsteam.wandscape.engine.boundary.*;
import com.wsteam.wandscape.engine.colony.ColonyLevelManager;
import com.wsteam.wandscape.engine.source.blueprint.BlueprintConfigLoader;
import com.wsteam.wandscape.engine.transport.ItemTransportManager;
import com.wsteam.wandscape.content.npc.guard.executor.GuardAttackExecutor;
import com.wsteam.wandscape.content.npc.guard.executor.SelfDefenseExecutor;
import com.wsteam.wandscape.content.road.engine.RoadSavedData;
import com.wsteam.wandscape.content.task.source.PlayerManualSource;

import javax.annotation.Nullable;
/**
 * Singleton holder for the engine {@link World} instance.
 * Bootstrap happens once in {@link EngineBootstrap}.
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

    @Nullable
    private static WandscapeMovementOps movementOps;
    @Nullable
    private static BlueprintConfigLoader blueprintConfigLoader;

    @Nullable
    private static TaskPoolSavedData taskPoolSavedData;
    @Nullable
    private static RoadSavedData roadSavedData;
    @Nullable
    private static ItemTransportManager transporter;
    @Nullable
    private static ResourceRequestExecutor resourceRequestExec;
    @Nullable
    private static PlayerManualSource playerManualSource;
    @Nullable
    private static GuardAttackExecutor guardExec;
    @Nullable
    private static SelfDefenseExecutor selfDefenseExec;
    @Nullable
    private static AltarCastExecutor altarCastExec;

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
        roadSavedData = null;
        playerManualSource = null;
        colonyLevelManager = null;
        altarCastExec = null;
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

    @Nullable
    public static RoadSavedData getRoadSavedData() { return roadSavedData; }
    public static void setRoadSavedData(@Nullable RoadSavedData v) { roadSavedData = v; }

    @Nullable
    public static ItemTransportManager getTransporter() { return transporter; }
    public static void setTransporter(ItemTransportManager t) { transporter = t; }

    @Nullable
    public static ResourceRequestExecutor getResourceRequestExec() { return resourceRequestExec; }
    public static void setResourceRequestExec(@Nullable ResourceRequestExecutor e) { resourceRequestExec = e; }

    @Nullable
    public static PlayerManualSource getPlayerManualSource() { return playerManualSource; }
    public static void setPlayerManualSource(@Nullable PlayerManualSource s) { playerManualSource = s; }

    @Nullable
    public static GuardAttackExecutor getGuardExecutor() { return guardExec; }
    public static void setGuardExecutor(@Nullable GuardAttackExecutor exec) { guardExec = exec; }

    @Nullable
    public static SelfDefenseExecutor getSelfDefenseExecutor() { return selfDefenseExec; }
    public static void setSelfDefenseExecutor(@Nullable SelfDefenseExecutor exec) { selfDefenseExec = exec; }

    @Nullable
    public static AltarCastExecutor getAltarCastExecutor() { return altarCastExec; }
    public static void setAltarCastExecutor(@Nullable AltarCastExecutor exec) { altarCastExec = exec; }

    @Nullable
    private static ColonyLevelManager colonyLevelManager;

    @Nullable
    public static ColonyLevelManager getColonyLevelManager() { return colonyLevelManager; }
    public static void setColonyLevelManager(@Nullable ColonyLevelManager mgr) { colonyLevelManager = mgr; }
}
