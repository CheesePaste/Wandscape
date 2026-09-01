package com.wsteam.wandscape.content.npc.guard;
import com.wsteam.wandscape.content.task.component.Position;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.content.task.runtime.TaskState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 守卫调试命令：{@code /wandscape guard status} — 打印守卫区域数、最近威胁、
 * 脱离区是否清空、活跃守卫任务数，便于实测核对。
 */
public final class GuardCommand {

    private static final String TAG = "GuardCommand";

    private GuardCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("guard")
                .then(Commands.literal("status")
                        .requires(src -> src.hasPermission(2))
                        .executes(GuardCommand::status))
                .build();
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getServer().overworld();
        if (level == null) {
            src.sendFailure(Component.literal("[Wandscape] No overworld available"));
            return 0;
        }

        int attackRange = com.wsteam.wandscape.foundation.util.BalanceValues.guardRange();
        int releaseRange = com.wsteam.wandscape.foundation.util.BalanceValues.guardReleaseRange();

        List<GuardZone> attackZones = GuardScanner.zones(level, attackRange);
        AABB queryBox = GuardScanner.unionAabb(attackZones);
        LivingEntity threat = queryBox != null
                ? GuardScanner.nearestInZones(level, attackZones, queryBox.getCenter())
                : null;
        boolean releaseClear = !GuardScanner.hasMonsterInZones(
                level, GuardScanner.zones(level, releaseRange));

        int activeGuards = 0;
        World world = com.wsteam.wandscape.content.task.ecs.World.getActive();
        if (world != null) {
            for (var t : world.taskPool.all()) {
                if ("guard:attack".equals(t.blueprintId) && t.state != TaskState.COMPLETED) {
                    activeGuards++;
                }
            }
        }

        String line = "[Wandscape Guard] zones=" + attackZones.size()
                + " attack=" + attackRange + " release=" + releaseRange
                + " threat=" + (threat != null
                        ? threat.getUUID().toString().substring(0, 8)
                                + " @ " + fmt(threat.position())
                        : "none")
                + " releaseClear=" + releaseClear
                + " activeGuards=" + activeGuards;
        Log.info(TAG, line);
        src.sendSuccess(() -> Component.literal(line), true);
        return Command.SINGLE_SUCCESS;
    }

    private static String fmt(Vec3 v) {
        return String.format("(%.1f,%.1f,%.1f)", v.x, v.y, v.z);
    }
}
