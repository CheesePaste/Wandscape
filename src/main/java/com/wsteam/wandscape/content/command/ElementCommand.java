package com.wsteam.wandscape.content.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.api.WarehouseApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 殖民地元素指令（小镇的货币：土/木/水/火/金/风/暗）。
 *
 * <p>权限：
 * <ul>
 *   <li>{@code view} —— 只读，玩家无需权限；</li>
 *   <li>{@code add}/{@code remove}/{@code clear} —— 直接改变元素存量，属平衡性变更，需管理员（op-2）。</li>
 * </ul>
 *
 * <p>实现走 {@link WarehouseApi}（经 {@link com.wsteam.wandscape.content.warehouse.WarehouseManager}），
 * 与整合包作者共享同一条增删清路径。
 */
public final class ElementCommand {

    private ElementCommand() {}

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_ELEMENTS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(
                    java.util.Arrays.stream(ElementType.values()).map(ElementType::getId).toList(),
                    builder);

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("element")
                .then(Commands.literal("view").executes(ElementCommand::view))
                .then(Commands.literal("add").requires(src -> src.hasPermission(2))
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests(SUGGEST_ELEMENTS)
                                .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                        .executes(ctx -> mutate(ctx, true)))))
                .then(Commands.literal("remove").requires(src -> src.hasPermission(2))
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests(SUGGEST_ELEMENTS)
                                .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                        .executes(ctx -> mutate(ctx, false)))))
                .then(Commands.literal("clear").requires(src -> src.hasPermission(2))
                        .executes(ElementCommand::clear))
                .build();
    }

    private static int view(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID colonyId = CommandUtil.resolveColony(src);
        if (colonyId == null) return notInColony(src);
        WarehouseApi api = api(src);
        if (api == null) return 0;

        var elems = api.getAllElements(colonyId);
        StringBuilder sb = new StringBuilder("[Wandscape] 小镇 " + CommandUtil.shortId(colonyId) + " 元素：");
        for (ElementType t : ElementType.values()) {
            sb.append("  ").append(t.getId()).append("=").append(elems.getOrDefault(t, 0L));
        }
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int mutate(CommandContext<CommandSourceStack> ctx, boolean add) {
        CommandSourceStack src = ctx.getSource();
        UUID colonyId = CommandUtil.resolveColony(src);
        if (colonyId == null) return notInColony(src);
        String id = StringArgumentType.getString(ctx, "type");
        long amount = LongArgumentType.getLong(ctx, "amount");
        WarehouseApi api = api(src);
        if (api == null) return 0;

        ElementType type = parseElement(src, id);
        if (type == null) return 0;

        if (add) {
            api.addElement(colonyId, type, Math.max(0, amount));
        } else {
            if (amount <= 0) {
                src.sendSuccess(() -> Component.literal("[Wandscape] 无变更"), false);
                return Command.SINGLE_SUCCESS;
            }
            if (!api.consumeElement(colonyId, type, amount)) {
                src.sendFailure(Component.literal("[Wandscape] 元素不足：" + id
                        + " 现有 " + api.getElement(colonyId, type)));
                return 0;
            }
        }

        src.sendSuccess(() -> Component.literal("[Wandscape] " + (add ? "增加" : "减少") + "元素 "
                + id + " x" + amount + "（小镇 " + CommandUtil.shortId(colonyId) + "）"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID colonyId = CommandUtil.resolveColony(src);
        if (colonyId == null) return notInColony(src);
        WarehouseApi api = api(src);
        if (api == null) return 0;

        var elems = api.getAllElements(colonyId);
        long total = elems.values().stream().mapToLong(Long::longValue).sum();
        api.clearElements(colonyId);
        src.sendSuccess(() -> Component.literal(
                "[Wandscape] 已清空小镇 " + CommandUtil.shortId(colonyId) + " 元素（" + total + " 点）"),
                true);
        return Command.SINGLE_SUCCESS;
    }

    @javax.annotation.Nullable
    private static ElementType parseElement(CommandSourceStack src, String id) {
        for (ElementType t : ElementType.values()) {
            if (t.getId().equalsIgnoreCase(id)) return t;
        }
        src.sendFailure(I18n.name("message.wandscape.command.element_unknown",
                "[Wandscape] 未知元素：%s（可选：earth/wood/water/fire/metal/wind/dark）", id));
        return null;
    }

    @javax.annotation.Nullable
    private static WarehouseApi api(CommandSourceStack src) {
        WarehouseApi api = WandscapeApis.getWarehouseApiSilently();
        if (api == null) {
            src.sendFailure(Component.literal("[Wandscape] 仓库系统未就绪"));
        }
        return api;
    }

    private static int notInColony(CommandSourceStack src) {
        src.sendFailure(Component.literal(
                "[Wandscape] 未检测到小镇：请在小镇范围内使用，或先创建小镇"));
        return 0;
    }
}
