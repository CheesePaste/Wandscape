package com.wsteam.wandscape.content.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.api.WarehouseApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.util.ItemKey;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 殖民地仓库指令（只针对<b>物品</b>；元素走 {@code /wandscape element}）。
 *
 * <p>权限：
 * <ul>
 *   <li>{@code view} —— 只读，玩家无需权限；</li>
 *   <li>{@code add}/{@code remove}/{@code clear} —— 改变殖民地物品，需管理员（op-2）。</li>
 * </ul>
 *
 * <p>实现走 {@link WarehouseApi}（经 {@link com.wsteam.wandscape.content.warehouse.WarehouseManager}），
 * 与整合包作者共享同一条增删清路径。
 */
public final class WarehouseCommand {

    private WarehouseCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("warehouse")
                .then(Commands.literal("view").executes(WarehouseCommand::view))
                .then(Commands.literal("add").requires(src -> src.hasPermission(2))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                        .executes(ctx -> mutateItem(ctx, true)))))
                .then(Commands.literal("remove").requires(src -> src.hasPermission(2))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                        .executes(ctx -> mutateItem(ctx, false)))))
                .then(Commands.literal("clear").requires(src -> src.hasPermission(2))
                        .executes(WarehouseCommand::clear))
                .build();
    }

    private static int view(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID colonyId = CommandUtil.resolveColony(src);
        if (colonyId == null) return notInColony(src);
        WarehouseApi api = api(src, "仓库");
        if (api == null) return 0;

        var items = api.getItemSnapshot(colonyId);
        long total = items.values().stream().mapToLong(Long::longValue).sum();
        String cid = CommandUtil.shortId(colonyId);
        src.sendSuccess(() -> Component.literal(
                "[魔法小镇] 小镇 " + cid + " 仓库：物品 " + items.size() + " 类 / " + total + " 件"), false);

        long used = api.getUsedItemCapacity(colonyId);
        long cap = api.getItemCapacity(colonyId);
        if (cap > 0) {
            src.sendSuccess(() -> Component.literal("  容量: " + used + " / " + cap), false);
        } else {
            src.sendSuccess(() -> Component.literal("  容量: 不限 (" + used + " 件在用)"), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int mutateItem(CommandContext<CommandSourceStack> ctx, boolean add) {
        CommandSourceStack src = ctx.getSource();
        UUID colonyId = CommandUtil.resolveColony(src);
        if (colonyId == null) return notInColony(src);
        String id = StringArgumentType.getString(ctx, "id");
        long amount = LongArgumentType.getLong(ctx, "amount");
        WarehouseApi api = api(src, "仓库");
        if (api == null) return 0;

        ItemKey key = ItemKey.of(id, null);
        boolean ok = add ? api.addItem(colonyId, key, amount) : api.removeItem(colonyId, key, amount);
        if (!ok) {
            src.sendFailure(Component.literal("[魔法小镇] "
                    + (add ? "增加物品失败（账本未就绪）"
                           : ("物品不足：" + id + " 现有 " + api.getItemCount(colonyId, key)))));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("[魔法小镇] " + (add ? "增加" : "减少") + "物品 "
                + id + " x" + amount + "（小镇 " + CommandUtil.shortId(colonyId) + "）"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID colonyId = CommandUtil.resolveColony(src);
        if (colonyId == null) return notInColony(src);
        WarehouseApi api = api(src, "仓库");
        if (api == null) return 0;

        var items = api.getItemSnapshot(colonyId);
        long total = items.values().stream().mapToLong(Long::longValue).sum();
        api.clearItems(colonyId);
        src.sendSuccess(() -> Component.literal(
                "[魔法小镇] 已清空小镇 " + CommandUtil.shortId(colonyId) + " 仓库物品（" + total + " 件）"),
                true);
        return Command.SINGLE_SUCCESS;
    }

    @javax.annotation.Nullable
    private static WarehouseApi api(CommandSourceStack src, String label) {
        WarehouseApi api = WandscapeApis.getWarehouseApiSilently();
        if (api == null) {
            src.sendFailure(Component.literal("[魔法小镇] " + label + "系统未就绪"));
        }
        return api;
    }

    private static int notInColony(CommandSourceStack src) {
        src.sendFailure(Component.literal(
                "[魔法小镇] 未检测到小镇：请在小镇范围内使用，或先创建小镇"));
        return 0;
    }
}
