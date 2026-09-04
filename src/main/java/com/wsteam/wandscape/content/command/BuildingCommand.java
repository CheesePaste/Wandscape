package com.wsteam.wandscape.content.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.api.BuildingApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;
import java.util.UUID;

/**
 * 殖民地建筑指令：查看 / 取消在建 / 拆除。
 *
 * <p>权限：
 * <ul>
 *   <li>{@code list} —— 只读，玩家无需权限；</li>
 *   <li>{@code cancel}/{@code demolish} —— 破坏性操作，需管理员（op-2）。</li>
 * </ul>
 *
 * <p>{@code buildingId} 支持短前缀/连续子串匹配小镇内任意建筑（取自 {@code list} 显示的 id），
 * 不要求手敲完整 UUID。
 */
public final class BuildingCommand {

    private BuildingCommand() {}

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_CATEGORIES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(
                    java.util.List.of("government", "storage", "production", "node",
                            "altar", "mage_hut", "tavern", "shop", "hotel", "relax"),
                    builder);

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("building")
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx, null))
                        .then(Commands.argument("category", StringArgumentType.word())
                                .suggests(SUGGEST_CATEGORIES)
                                .executes(ctx -> list(ctx, StringArgumentType.getString(ctx, "category")))))
                .then(Commands.literal("cancel").requires(src -> src.hasPermission(2))
                        .then(Commands.argument("buildingId", StringArgumentType.string())
                                .executes(ctx -> cancel(ctx, StringArgumentType.getString(ctx, "buildingId")))))
                .then(Commands.literal("demolish").requires(src -> src.hasPermission(2))
                        .then(Commands.argument("buildingId", StringArgumentType.string())
                                .executes(ctx -> demolish(ctx, StringArgumentType.getString(ctx, "buildingId")))))
                .build();
    }

    private static int list(CommandContext<CommandSourceStack> ctx, String category) {
        CommandSourceStack src = ctx.getSource();
        UUID colonyId = CommandUtil.resolveColony(src);
        if (colonyId == null) return notInColony(src);

        BuildingApi api = WandscapeApis.getBuildingApiSilently();
        if (api == null) {
            src.sendFailure(Component.literal("[魔法小镇] 建筑系统未就绪"));
            return 0;
        }

        List<BuildingState> builds = (category != null && !category.isBlank())
                ? api.getBuildingsByCategory(colonyId, category).stream()
                        .map(api::getBuilding)
                        .filter(java.util.Objects::nonNull)
                        .toList()
                : api.getColonyBuildings(colonyId);

        String cid = CommandUtil.shortId(colonyId);
        src.sendSuccess(() -> Component.literal("[魔法小镇] 小镇 " + cid + " 建筑 ("
                + (category == null ? "全部" : category) + "，共 " + builds.size() + " 座)："), false);
        if (builds.isEmpty()) {
            src.sendSuccess(() -> Component.literal("  （无建筑）"), false);
            return Command.SINGLE_SUCCESS;
        }
        builds.forEach(b -> {
            String state = b.hasEverCompleted()
                    ? (b.isDemolishing() ? "拆除中" : "已建成")
                    : (b.isConstructionStarted() ? "建造中" : "待施工");
            BoundingBox bb = b.getBounds();
            String size = bb != null ? (bb.getXSpan() + "x" + bb.getYSpan() + "x" + bb.getZSpan()) : "?";
            src.sendSuccess(() -> Component.literal(
                    String.format("  %s [%s] %s %s @ %s  id=%s",
                            b.getDisplayName(), b.getCategory(), state, size,
                            b.getAnchor().toShortString(), shortUuid(b.getBuildingId()))),
                    false);
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx, String idFragment) {
        CommandSourceStack src = ctx.getSource();
        UUID buildingId = resolveBuilding(src, idFragment);
        if (buildingId == null) return 0;
        BuildingApi api = WandscapeApis.getBuildingApiSilently();
        if (api == null) {
            src.sendFailure(Component.literal("[魔法小镇] 建筑系统未就绪"));
            return 0;
        }
        boolean ok = api.cancelBuilding(buildingId);
        src.sendSuccess(() -> Component.literal(
                "[魔法小镇] " + (ok ? "已取消在建建筑（材料已退还）" : "取消失败：建筑不存在或已建成")),
                true);
        return Command.SINGLE_SUCCESS;
    }

    private static int demolish(CommandContext<CommandSourceStack> ctx, String idFragment) {
        CommandSourceStack src = ctx.getSource();
        UUID buildingId = resolveBuilding(src, idFragment);
        if (buildingId == null) return 0;
        BuildingApi api = WandscapeApis.getBuildingApiSilently();
        if (api == null) {
            src.sendFailure(Component.literal("[魔法小镇] 建筑系统未就绪"));
            return 0;
        }
        Component block = api.demolishBlockReason(buildingId);
        if (block != null) {
            src.sendFailure(Component.literal("[魔法小镇] 无法拆除：" + block.getString()));
            return 0;
        }
        api.demolishBuilding(buildingId);
        src.sendSuccess(() -> Component.literal("[魔法小镇] 已开始拆除建筑（掉落归仓库）"), true);
        return Command.SINGLE_SUCCESS;
    }

    // ── helpers ──

    /** 在小镇建筑里按短前缀/子串解析 buildingId；找不到或系统未就绪返回 null（已输出失败提示）。 */
    @javax.annotation.Nullable
    private static UUID resolveBuilding(CommandSourceStack src, String fragment) {
        UUID colonyId = CommandUtil.resolveColony(src);
        if (colonyId == null) {
            notInColony(src);
            return null;
        }
        BuildingApi api = WandscapeApis.getBuildingApiSilently();
        if (api == null) {
            src.sendFailure(Component.literal("[魔法小镇] 建筑系统未就绪"));
            return null;
        }
        String f = fragment.trim();
        List<BuildingState> builds = api.getColonyBuildings(colonyId);
        for (BuildingState b : builds) {
            String full = b.getBuildingId().toString();
            if (full.equalsIgnoreCase(f) || full.startsWith(f) || full.contains(f)) {
                return b.getBuildingId();
            }
        }
        src.sendFailure(Component.literal("[魔法小镇] 找不到 id 为 '" + fragment + "' 的建筑"));
        return null;
    }

    private static String shortUuid(UUID id) {
        return id == null ? "?" : id.toString().substring(0, 8);
    }

    private static int notInColony(CommandSourceStack src) {
        src.sendFailure(Component.literal(
                "[魔法小镇] 未检测到小镇：请在小镇范围内使用，或先创建小镇"));
        return 0;
    }
}
