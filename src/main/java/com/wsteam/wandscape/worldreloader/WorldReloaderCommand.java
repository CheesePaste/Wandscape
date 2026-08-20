package com.wsteam.wandscape.worldreloader;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Command integration for WorldReloader:
 * /wandscape transform <x> <y> <z> biome|structure|random ...
 * /wandscape transform here biome|structure|random ...
 * /wandscape transform reload
 * /wandscape transform stop
 * /wandscape transform setPermission player|op|disabled
 */
public final class WorldReloaderCommand {

    private WorldReloaderCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("transform")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("reload")
                        .executes(WorldReloaderCommand::reloadConfig))
                .then(Commands.literal("refresh")
                        .executes(WorldReloaderCommand::reloadConfig))
                .then(Commands.literal("stop")
                        .executes(WorldReloaderCommand::stopTasks))
                .then(Commands.literal("setPermission")
                        .then(Commands.argument("permission", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(new String[]{"player", "op", "disabled"}, builder))
                                .executes(WorldReloaderCommand::setPermission)))
                .then(Commands.literal("here")
                        .then(Commands.literal("biome")
                                .then(Commands.argument("biomeName", StringArgumentType.greedyString())
                                        .executes(ctx -> transformHere(ctx, "biome", StringArgumentType.getString(ctx, "biomeName")))))
                        .then(Commands.literal("structure")
                                .then(Commands.argument("structureName", StringArgumentType.greedyString())
                                        .executes(ctx -> transformHere(ctx, "structure", StringArgumentType.getString(ctx, "structureName")))))
                        .then(Commands.literal("random")
                                .executes(ctx -> transformHere(ctx, "random", null))))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .then(Commands.literal("biome")
                                                .then(Commands.argument("biomeName", StringArgumentType.greedyString())
                                                        .executes(ctx -> transformAt(ctx, "biome", StringArgumentType.getString(ctx, "biomeName")))))
                                        .then(Commands.literal("structure")
                                                .then(Commands.argument("structureName", StringArgumentType.greedyString())
                                                        .executes(ctx -> transformAt(ctx, "structure", StringArgumentType.getString(ctx, "structureName")))))
                                        .then(Commands.literal("random")
                                                .executes(ctx -> transformAt(ctx, "random", null))))))
                .build();
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        WorldReloaderManager.get().reloadConfig();
        ctx.getSource().sendSuccess(() -> Component.literal("§a[WorldReloader] 配置已重新加载"), true);
        return 1;
    }

    private static int stopTasks(CommandContext<CommandSourceStack> ctx) {
        WorldReloaderManager.get().stopAll();
        ctx.getSource().sendSuccess(() -> Component.literal("§6[WorldReloader] 已停止所有运行中的地形改造任务"), true);
        return 1;
    }

    private static int setPermission(CommandContext<CommandSourceStack> ctx) {
        String perm = StringArgumentType.getString(ctx, "permission").toLowerCase();
        if (!perm.equals("player") && !perm.equals("op") && !perm.equals("disabled")) {
            ctx.getSource().sendFailure(Component.literal("§c[WorldReloader] 无效的权限等级！可用选项: player, op, disabled"));
            return 0;
        }

        WorldReloaderManager.get().getConfig().minPermission = perm;
        WorldReloaderManager.get().getConfig().save();
        ctx.getSource().sendSuccess(() -> Component.literal("§a[WorldReloader] 地形改造权限已设置为: " + perm), true);
        return 1;
    }

    private static int transformHere(CommandContext<CommandSourceStack> ctx, String mode, String target) {
        ServerPlayer player;
        try {
            player = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§c[WorldReloader] 仅玩家可使用 here 命令"));
            return 0;
        }

        ServerLevel level = ctx.getSource().getLevel();
        BlockPos pos = player.blockPosition();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("§6[WorldReloader] 开始在玩家位置 (%d, %d, %d) 执行地形改造...", pos.getX(), pos.getY(), pos.getZ())), false);

        level.getServer().execute(() -> {
            WorldReloaderManager.get().startTransformationAt(level, pos, player, mode, target);
        });

        return 1;
    }

    private static int transformAt(CommandContext<CommandSourceStack> ctx, String mode, String target) {
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        BlockPos pos = new BlockPos(x, y, z);
        ServerLevel level = ctx.getSource().getLevel();
        ServerPlayer player = ctx.getSource().getPlayer();

        ctx.getSource().sendSuccess(() -> Component.literal(String.format("§6[WorldReloader] 开始在指定地点 (%d, %d, %d) 执行地形改造...", x, y, z)), false);

        level.getServer().execute(() -> {
            WorldReloaderManager.get().startTransformationAt(level, pos, player, mode, target);
        });

        return 1;
    }
}
