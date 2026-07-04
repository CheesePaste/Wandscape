package com.wsteam.wandscape.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.task.runtime.TaskState;
import com.wsteam.wandscape.engine.WandscapeEngine;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
/**
 * Recovery commands: emergency task pool reset and status inspection.
 *
 * <p>Usage:
 * <pre>
 *   /wandscape recover clear  — clear all tasks, building queues, and reset NPCs
 *   /wandscape recover status — show task pool statistics
 * </pre>
 */
public final class RecoveryCommand {

    private RecoveryCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("recover")
                .then(Commands.literal("clear")
                        .executes(RecoveryCommand::clearAll))
                .then(Commands.literal("status")
                        .executes(RecoveryCommand::showStatus))
                .build();
    }

    private static int clearAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        World world = WandscapeEngine.getWorld();
        if (world == null || world.taskPool == null) {
            src.sendFailure(Component.literal("[Wandscape] Engine not bootstrapped"));
            return 0;
        }

        int taskCount = world.taskPool.size();
        int buildingCount = world.buildingTaskPool != null
                ? world.buildingTaskPool.totalBuildings() : 0;

        // Release NPCs from active tasks so they return to idle cleanly
        for (GlobalTask task : world.taskPool.all()) {
            if ((task.state == TaskState.IN_PROGRESS || task.state == TaskState.AWAITING_RESOURCES)
                    && task.assignedNpcId != null) {
                world.taskPool.releaseNpc(task.id, task.assignedNpcId, world);
            }
        }

        // Full clear: pool, building queues, NPC executors
        world.clearAllTasks();

        src.sendSuccess(() -> Component.literal(
                "[Wandscape] Recovery complete — cleared " + taskCount + " tasks, "
                        + buildingCount + " building queues, reset all NPCs"),
                true);

        return Command.SINGLE_SUCCESS;
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        World world = WandscapeEngine.getWorld();
        if (world == null || world.taskPool == null) {
            src.sendFailure(Component.literal("[Wandscape] Engine not bootstrapped"));
            return 0;
        }

        int total = world.taskPool.size();
        int assignable = world.taskPool.assignableCount();
        int pendingApproval = world.taskPool.getByState(TaskState.PENDING_APPROVAL).size();
        int inProgress = world.taskPool.getByState(TaskState.IN_PROGRESS).size();
        int awaiting = world.taskPool.getByState(TaskState.AWAITING_RESOURCES).size();
        int failed = world.taskPool.getByState(TaskState.FAILED).size();
        int completed = world.taskPool.getByState(TaskState.COMPLETED).size();
        int buildings = world.buildingTaskPool != null
                ? world.buildingTaskPool.totalBuildings() : 0;

        src.sendSuccess(() -> Component.literal(
                "[Wandscape] ── Task Pool Status ──\n" +
                        "  Total active:  " + total + "\n" +
                        "  Assignable:    " + assignable + "\n" +
                        "  Pending appr:  " + pendingApproval + "\n" +
                        "  In progress:   " + inProgress + "\n" +
                        "  Awaiting res:  " + awaiting + "\n" +
                        "  Failed:        " + failed + "\n" +
                        "  Completed:     " + completed + "\n" +
                        "  Buildings:     " + buildings),
                false);

        return Command.SINGLE_SUCCESS;
    }
}
