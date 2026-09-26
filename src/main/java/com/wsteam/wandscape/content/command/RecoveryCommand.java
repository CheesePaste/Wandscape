package com.wsteam.wandscape.content.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.api.BuildingApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.building.data.BuildingData;
import com.wsteam.wandscape.content.colony.ownership.ColonyOwnership;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTaskPool;
import com.wsteam.wandscape.content.task.runtime.TaskState;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 任务池恢复指令。两条面，作用域不同：
 *
 * <pre>
 *   /wandscape recover clear|status        — 玩家面，所有人可用，只作用于执行者自己的小镇
 *   /wandscape test recover clear|status   — 开发者面（父节点 op-2 门控），作用于全服
 * </pre>
 *
 * <p>玩家面用 {@link ColonyOwnership#ownColony} 按创始人判定归属，**不给 OP 的位置回退**
 * （{@code CommandUtil.resolveColony} 那种「站在谁的地盘就操作谁」的规则在这里是有害的）：
 * 「只 recover 自己的小镇」是给玩家的防误伤边界；管理员要看/清别人的小镇走 test 面。
 * 没有小镇的玩家直接拒绝——那正是「建镇引导态」。
 *
 * <p>玩家面与开发者面的输出刻意不同：玩家面走 i18n（面向所有玩家），开发者面保留原来的
 * 英文调试行（op 专用，与 {@code /wandscape test} 下其他调试指令一致）。
 */
public final class RecoveryCommand {

    private static final String TAG = "RecoveryCommand";

    private RecoveryCommand() {}

    /** 玩家面：所有人可用，只清理你自己的小镇。 */
    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("recover")
                .then(Commands.literal("clear")
                        .executes(RecoveryCommand::clearOwnColony))
                .then(Commands.literal("status")
                        .executes(RecoveryCommand::showOwnStatus))
                .build();
    }

    /** 开发者面：全服清理/全服状态，挂在 {@code /wandscape test} 下（父节点 op-2 门控）。 */
    public static CommandNode<CommandSourceStack> devNode() {
        return Commands.literal("recover")
                .then(Commands.literal("clear")
                        .executes(RecoveryCommand::clearAllColonies))
                .then(Commands.literal("status")
                        .executes(RecoveryCommand::showGlobalStatus))
                .build();
    }

    // ── 玩家面：只作用于自己的小镇 ──

    private static int clearOwnColony(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        World world = World.getActive();
        if (world == null || world.taskPool == null) {
            src.sendFailure(Component.literal("[Wandscape] Engine not bootstrapped"));
            return 0;
        }
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(I18n.name("message.wandscape.command.recover_players_only",
                    "[魔法小镇] 该指令只能由玩家执行——它只清理执行者自己的小镇"));
            return 0;
        }
        UUID colonyId = ColonyOwnership.ownColony(player);
        if (colonyId == null) {
            src.sendFailure(I18n.name("message.wandscape.command.recover_no_colony",
                    "[魔法小镇] 你还没有自己的小镇——recover 只清理你自己的小镇"));
            return 0;
        }

        World.ColonyRecovery r = world.clearColonyTasks(colonyId, colonyBuildingIds(colonyId));
        Log.info(TAG, "[Recover] {} cleared colony={} tasks={} queues={} npcs={}",
                player.getGameProfile().getName(), CommandUtil.shortId(colonyId),
                r.tasks(), r.buildingQueues(), r.npcs());

        src.sendSuccess(() -> I18n.name("message.wandscape.command.recover_cleared",
                "[魔法小镇] 已重置你小镇的任务：任务 %s 条、建筑队列 %s 个、法师 %s 名",
                r.tasks(), r.buildingQueues(), r.npcs()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int showOwnStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        World world = World.getActive();
        if (world == null || world.taskPool == null) {
            src.sendFailure(Component.literal("[Wandscape] Engine not bootstrapped"));
            return 0;
        }
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(I18n.name("message.wandscape.command.recover_players_only",
                    "[魔法小镇] 该指令只能由玩家执行——它只清理执行者自己的小镇"));
            return 0;
        }
        UUID colonyId = ColonyOwnership.ownColony(player);
        if (colonyId == null) {
            src.sendFailure(I18n.name("message.wandscape.command.recover_no_colony",
                    "[魔法小镇] 你还没有自己的小镇——recover 只清理你自己的小镇"));
            return 0;
        }

        ColonyTaskStatus st = statusOf(world, colonyId);
        src.sendSuccess(() -> I18n.name("message.wandscape.command.recover_status_header",
                "[魔法小镇] ── 你小镇的任务池 (%s) ──", CommandUtil.shortId(colonyId)), false);
        src.sendSuccess(() -> I18n.name("message.wandscape.command.recover_status_body",
                "  任务 %s（可派发 %s · 进行中 %s · 待料 %s · 已完成 %s）· 建筑 %s 座 · 队列 %s 个",
                st.total(), st.assignable(), st.inProgress(), st.awaiting(),
                st.completed(), st.buildings(), st.queues()), false);
        return Command.SINGLE_SUCCESS;
    }

    // ── 开发者面：全服 ──

    private static int clearAllColonies(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        World world = World.getActive();
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

    private static int showGlobalStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        World world = World.getActive();
        if (world == null || world.taskPool == null) {
            src.sendFailure(Component.literal("[Wandscape] Engine not bootstrapped"));
            return 0;
        }

        int total = world.taskPool.size();
        int assignable = world.taskPool.assignableCount();
        int inProgress = world.taskPool.getByState(TaskState.IN_PROGRESS).size();
        int awaiting = world.taskPool.getByState(TaskState.AWAITING_RESOURCES).size();
        int completed = world.taskPool.getByState(TaskState.COMPLETED).size();
        int buildings = world.buildingTaskPool != null
                ? world.buildingTaskPool.totalBuildings() : 0;

        src.sendSuccess(() -> Component.literal(
                "[Wandscape] ── Task Pool Status ──\n" +
                        "  Total active:  " + total + "\n" +
                        "  Assignable:    " + assignable + "\n" +
                        "  In progress:   " + inProgress + "\n" +
                        "  Awaiting res:  " + awaiting + "\n" +
                        "  Completed:     " + completed + "\n" +
                        "  Buildings:     " + buildings),
                false);

        return Command.SINGLE_SUCCESS;
    }

    // ── 共享 ──

    /** 一个殖民地的任务池快照（recover status 用）。 */
    private record ColonyTaskStatus(int total, int assignable, int inProgress, int awaiting,
                                    int completed, int buildings, int queues) {}

    /** 该殖民地拥有的建筑 id 快照；建筑系统缺失时返回空表（只清无建筑的合成/采集任务）。 */
    private static List<UUID> colonyBuildingIds(UUID colonyId) {
        BuildingApi api = WandscapeApis.getBuildingApiSilently();
        if (api == null) return List.of();
        List<UUID> ids = new ArrayList<>();
        for (BuildingData b : api.getColonyBuildings(colonyId)) {
            if (b != null && b.getBuildingId() != null) ids.add(b.getBuildingId());
        }
        return ids;
    }

    /**
     * 按 {@code colony_id} 聚合该小镇的任务状态。
     * 未完成条数与全服口径一致（{@code Total active} 含 COMPLETED 僵尸条目，这里照样计入 total，
     * 免得玩家看到「已完成」不计入总数而困惑）。
     */
    private static ColonyTaskStatus statusOf(World world, UUID colonyId) {
        List<UUID> buildingIds = colonyBuildingIds(colonyId);
        int total = 0, assignable = 0, inProgress = 0, awaiting = 0, completed = 0;
        for (GlobalTask task : world.taskPool.all()) {
            if (!colonyId.equals(GlobalTaskPool.colonyIdOf(task))) continue;
            total++;
            switch (task.state) {
                case PENDING_ASSIGN -> assignable++;
                case IN_PROGRESS -> inProgress++;
                case AWAITING_RESOURCES -> awaiting++;
                case COMPLETED -> completed++;
            }
        }
        int queues = 0;
        if (world.buildingTaskPool != null) {
            for (UUID buildingId : buildingIds) {
                if (world.buildingTaskPool.hasQueue(buildingId)) queues++;
            }
        }
        return new ColonyTaskStatus(total, assignable, inProgress, awaiting, completed,
                buildingIds.size(), queues);
    }
}
