package com.wsteam.wandscape.content.task.runtime;

import com.wsteam.wandscape.content.building.executor.AltarCastExecutor;
import com.wsteam.wandscape.content.npc.guard.executor.GuardAttackExecutor;
import com.wsteam.wandscape.content.npc.guard.executor.SelfDefenseExecutor;
import com.wsteam.wandscape.content.task.boundary.AsyncTransformExecutor;
import com.wsteam.wandscape.content.task.boundary.ResourceRequestExecutor;
import com.wsteam.wandscape.content.task.boundary.WandscapeBlockInteractExecutor;
import com.wsteam.wandscape.content.task.boundary.WandscapeMovementOps;
import com.wsteam.wandscape.content.task.boundary.WandscapeRitualOps;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.warehouse.transport.ItemTransportManager;
import com.wsteam.wandscape.foundation.util.TickProfiler;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * Encapsulates the runtime state and execution loop of the Wandscape task & ECS engine.
 *
 * <p>Created once on server start during {@link com.wsteam.wandscape.impl.EngineBootstrap#bootstrap()},
 * driven on server tick via {@link #tick(ServerLevel)}, and reset on server stopping.
 */
public final class TaskRuntime {

    @Nullable
    private static volatile TaskRuntime activeInstance;

    private final World world;
    @Nullable
    private final AsyncTransformExecutor asyncExec;
    @Nullable
    private final WandscapeBlockInteractExecutor blockInteractExec;
    @Nullable
    private final WandscapeRitualOps ritualOps;
    @Nullable
    private final ItemTransportManager transporter;
    @Nullable
    private final ResourceRequestExecutor resourceReqExec;
    @Nullable
    private final GuardAttackExecutor guardExec;
    @Nullable
    private final SelfDefenseExecutor selfDefenseExec;
    @Nullable
    private final AltarCastExecutor altarCastExec;
    @Nullable
    private final WandscapeMovementOps movementOps;

    public TaskRuntime(World world,
                       @Nullable AsyncTransformExecutor asyncExec,
                       @Nullable WandscapeBlockInteractExecutor blockInteractExec,
                       @Nullable WandscapeRitualOps ritualOps,
                       @Nullable ItemTransportManager transporter,
                       @Nullable ResourceRequestExecutor resourceReqExec,
                       @Nullable GuardAttackExecutor guardExec,
                       @Nullable SelfDefenseExecutor selfDefenseExec,
                       @Nullable AltarCastExecutor altarCastExec,
                       @Nullable WandscapeMovementOps movementOps) {
        this.world = world;
        this.asyncExec = asyncExec;
        this.blockInteractExec = blockInteractExec;
        this.ritualOps = ritualOps;
        this.transporter = transporter;
        this.resourceReqExec = resourceReqExec;
        this.guardExec = guardExec;
        this.selfDefenseExec = selfDefenseExec;
        this.altarCastExec = altarCastExec;
        this.movementOps = movementOps;
    }

    public static void setActive(@Nullable TaskRuntime runtime) {
        activeInstance = runtime;
    }

    @Nullable
    public static TaskRuntime getActive() {
        return activeInstance;
    }

    @Nullable
    public static World getActiveWorld() {
        TaskRuntime rt = activeInstance;
        return rt != null ? rt.getWorld() : null;
    }

    public static void reset() {
        activeInstance = null;
    }

    /**
     * Drive all task execution countdowns, async handlers, and the ECS world.
     */
    public void tick(ServerLevel level) {
        // 1. Tick async executors
        if (asyncExec != null) {
            try (var s = TickProfiler.INSTANCE.start("tick.async_exec")) {
                asyncExec.tickAll();
            }
        }
        if (blockInteractExec != null) {
            try (var s = TickProfiler.INSTANCE.start("tick.block_interact")) {
                blockInteractExec.tickAll();
            }
        }
        if (ritualOps != null) {
            try (var s = TickProfiler.INSTANCE.start("tick.ritual_ops")) {
                ritualOps.tickAll();
            }
        }
        if (transporter != null) {
            try (var s = TickProfiler.INSTANCE.start("tick.transporter")) {
                transporter.tickAll();
            }
        }
        if (resourceReqExec != null) {
            try (var s = TickProfiler.INSTANCE.start("tick.resource_req")) {
                resourceReqExec.tickAll();
            }
        }
        if (guardExec != null) {
            try (var s = TickProfiler.INSTANCE.start("tick.guard_exec")) {
                guardExec.tickAll();
            }
        }
        if (selfDefenseExec != null) {
            try (var s = TickProfiler.INSTANCE.start("tick.self_defense")) {
                selfDefenseExec.tick(world);
            }
        }
        if (altarCastExec != null) {
            try (var s = TickProfiler.INSTANCE.start("tick.altar_exec")) {
                altarCastExec.tickAll();
            }
        }

        // 2. Tick ECS World (includes NavigationSystem, ResourceSupplySystem, SchedulerSystem, etc.)
        try (var s = TickProfiler.INSTANCE.start("tick.world")) {
            world.tick(1.0f);
        }
    }

    public World getWorld() {
        return world;
    }

    @Nullable
    public WandscapeMovementOps getMovementOps() {
        return movementOps;
    }

    @Nullable
    public ItemTransportManager getTransporter() {
        return transporter;
    }

    @Nullable
    public AsyncTransformExecutor getAsyncExec() {
        return asyncExec;
    }

    @Nullable
    public WandscapeBlockInteractExecutor getBlockInteractExec() {
        return blockInteractExec;
    }

    @Nullable
    public WandscapeRitualOps getRitualOps() {
        return ritualOps;
    }

    @Nullable
    public ResourceRequestExecutor getResourceReqExec() {
        return resourceReqExec;
    }

    @Nullable
    public GuardAttackExecutor getGuardExec() {
        return guardExec;
    }

    @Nullable
    public SelfDefenseExecutor getSelfDefenseExec() {
        return selfDefenseExec;
    }

    @Nullable
    public AltarCastExecutor getAltarCastExec() {
        return altarCastExec;
    }
}
