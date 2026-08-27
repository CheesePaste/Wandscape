package com.wsteam.wandscape.engine.boundary;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.wsteam.wandscape.core.CoreBootstrap;
import com.wsteam.wandscape.core.CoreBootstrapConfig;
import com.wsteam.wandscape.core.boundary.MockBoundary;
import com.wsteam.wandscape.core.boundary.MovementOps;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.InteractAction;
import com.wsteam.wandscape.core.types.NpcAttributes;
import com.wsteam.wandscape.op.api.AtomicOp;
import com.wsteam.wandscape.task.engine.dsl.BlueprintRegistry;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.task.runtime.TaskSequence;
import com.wsteam.wandscape.task.runtime.TaskState;
import com.wsteam.wandscape.task.scheduler.SystemBlueprintRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Channel checkpoint semantics for {@link WandscapeBlockInteractExecutor} (production
 * synthesize/decompose/craft_wand/brew_potion + node gather).
 *
 * <p>Regression for: 合成到一半退出/被中断 → 进度丢失从头开始合成。The channel's remaining
 * ticks are checkpointed onto the owning global task each tick so a released/reloaded
 * task resumes mid-channel; an orphaned channel from a released task is cancelled
 * instead of completing and producing output a second time.
 */
class WandscapeBlockInteractChannelTest {

    private static final String BP = "production:synthesize";

    private World world;
    private WandscapeBlockInteractExecutor executor;
    private long npcId;
    private GridPos target;

    @BeforeEach
    void setUp() {
        MockBoundary mock = new MockBoundary();
        BlueprintRegistry blueprints = new BlueprintRegistry();
        blueprints.register(BP, params -> new TaskSequence(List.of(), "synthesize"));
        MovementOps noopMov = new MovementOps() {
            @Override
            public CompletableFuture<Void> navigateTo(long npcId, int x, int y, int z) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void cancelNavigation(long npcId) {
            }
        };
        CoreBootstrapConfig config = new CoreBootstrapConfig(
                mock, mock, mock, noopMov, mock, List.of(), blueprints,
                new SystemBlueprintRegistry(), false);
        world = CoreBootstrap.bootstrap(config);
        executor = new WandscapeBlockInteractExecutor(null);
        npcId = CoreBootstrap.createNpc(world, 0, 64, 0, UUID.randomUUID(), NpcAttributes.defaults());
        target = new GridPos(0, 64, 0);
    }

    private AtomicOp.BlockInteractOp channelOp(int channelTicks) {
        return new AtomicOp.BlockInteractOp(target, new InteractAction("synthesize"),
                Map.of("recipe_id", "bread", "count", "1"), channelTicks);
    }

    /** Create a task in the pool and bind it to the NPC as IN_PROGRESS. */
    private GlobalTask bindTask() {
        long taskId = world.taskPool.addTask(new TaskRequest(BP, Map.of(), 10, null));
        GlobalTask task = world.taskPool.get(taskId);
        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        exec.globalTaskId = taskId;
        task.state = TaskState.IN_PROGRESS;
        task.assignedNpcId = npcId;
        return task;
    }

    @Test
    void tick_writesChannelCheckpointOntoTask() {
        GlobalTask task = bindTask();
        assertEquals(-1, task.channelRemainingTicks);

        executor.execute(channelOp(10), world, npcId);
        executor.tickAll(); // 10 → 9
        assertEquals(9, task.channelRemainingTicks);

        executor.tickAll(); // 9 → 8
        assertEquals(8, task.channelRemainingTicks);
    }

    @Test
    void release_cancelsOrphanedChannel_withoutCompletingIt() {
        GlobalTask task = bindTask();

        executor.execute(channelOp(10), world, npcId);
        executor.tickAll();
        assertEquals(9, task.channelRemainingTicks);

        // Simulate the task being released (follow mode / reassignment): the NPC's
        // binding is cleared and the task returns to PENDING_ASSIGN.
        world.taskPool.releaseTaskForReassign(task.id, npcId, world);
        assertTrue(world.get(npcId, TaskExecutor.class).globalTaskId == null);

        executor.tickAll();
        // Orphaned channel cancelled: checkpoint untouched (a normal completion would
        // reset it to -1), no pending ops left, and the async future was resolved.
        assertEquals(9, task.channelRemainingTicks);
        assertFalse(executor.hasPendingOps());
        assertFalse(world.hasPendingAsyncOps());
    }

    @Test
    void reexecute_resumesFromCheckpoint_notFullChannel() {
        GlobalTask task = bindTask();

        executor.execute(channelOp(10), world, npcId);
        executor.tickAll();
        assertEquals(9, task.channelRemainingTicks);

        // Release then re-assign to the same NPC (like a reassignment).
        world.taskPool.releaseTaskForReassign(task.id, npcId, world);
        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        exec.globalTaskId = task.id;
        task.state = TaskState.IN_PROGRESS;
        task.assignedNpcId = npcId;

        executor.execute(channelOp(10), world, npcId);
        int[] prog = executor.getChannelProgress(target);
        assertEquals(9, prog[0]); // resumed from checkpoint, not a full 10-tick channel
        assertEquals(10, prog[1]);

        executor.tickAll();
        assertEquals(8, task.channelRemainingTicks);
    }
}
