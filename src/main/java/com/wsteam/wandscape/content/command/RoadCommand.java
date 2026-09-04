package com.wsteam.wandscape.content.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.api.RoadApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.road.core.RoadEdge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * 道路网指令：查看路网状态 / 撤回未建成路段。
 *
 * <p>权限：
 * <ul>
 *   <li>{@code status} —— 只读，玩家无需权限；</li>
 *   <li>{@code cancel} —— 撤回在建路段（退还材料），需管理员（op-2）。</li>
 * </ul>
 *
 * <p>{@code edgeId} 支持短前缀/子串匹配（取自 {@code status} 显示的 id）。
 */
public final class RoadCommand {

    private RoadCommand() {}

    private static final String ROAD_API_MSG = "[Wandscape] 道路系统未就绪";

    /** RoadApi 无 silently 变体；在未装配早期启动窗口安全取用。 */
    @javax.annotation.Nullable
    private static RoadApi roadApi() {
        try {
            return WandscapeApis.getRoadApi();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("road")
                .then(Commands.literal("status")
                        .executes(RoadCommand::status))
                .then(Commands.literal("cancel").requires(src -> src.hasPermission(2))
                        .then(Commands.argument("edgeId", StringArgumentType.string())
                                .executes(ctx -> cancel(ctx, StringArgumentType.getString(ctx, "edgeId")))))
                .build();
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID colonyId = CommandUtil.resolveColony(src);
        if (colonyId == null) return notInColony(src);

        RoadApi api = roadApi();
        if (api == null) {
            src.sendFailure(Component.literal(ROAD_API_MSG));
            return 0;
        }
        List<RoadEdge> edges = api.getEdges(colonyId);
        long planned = edges.stream().filter(e -> e.getStatus() == RoadEdge.EdgeStatus.PLANNED).count();
        long building = edges.stream().filter(e -> e.getStatus() == RoadEdge.EdgeStatus.BUILDING).count();
        long complete = edges.stream().filter(e -> e.getStatus() == RoadEdge.EdgeStatus.COMPLETE).count();
        long totalLen = edges.stream().mapToLong(e -> e.getPath().size()).sum();

        String cid = CommandUtil.shortId(colonyId);
        src.sendSuccess(() -> Component.literal(
                "[Wandscape] 小镇 " + cid + " 路网：" + edges.size() + " 段"
                        + "（已建成 " + complete + "，" + "在建 " + building
                        + "，" + "待施工 " + planned + "）"
                        + "，铺装总长 " + totalLen + " 格"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx, String fragment) {
        CommandSourceStack src = ctx.getSource();
        UUID colonyId = CommandUtil.resolveColony(src);
        if (colonyId == null) return notInColony(src);
        RoadApi api = roadApi();
        if (api == null) {
            src.sendFailure(Component.literal(ROAD_API_MSG));
            return 0;
        }

        String f = fragment.trim();
        RoadEdge target = null;
        for (RoadEdge e : api.getEdges(colonyId)) {
            String full = e.getEdgeId().toString();
            if (full.equalsIgnoreCase(f) || full.startsWith(f) || full.contains(f)) {
                target = e;
                break;
            }
        }
        if (target == null) {
            src.sendFailure(Component.literal("[Wandscape] 找不到 id 为 '" + fragment + "' 的路段"));
            return 0;
        }

        boolean ok = api.cancelEdge(colonyId, target.getEdgeId());
        final RoadEdge ft = target;
        src.sendSuccess(() -> Component.literal(
                "[Wandscape] " + (ok
                        ? ("已撤回路段 " + ft.getEdgeId().toString().substring(0, 8) + "（材料已退还）")
                        : "撤回失败：路段不存在或已建成")),
                true);
        return Command.SINGLE_SUCCESS;
    }

    private static int notInColony(CommandSourceStack src) {
        src.sendFailure(Component.literal(
                "[Wandscape] 未检测到小镇：请在小镇范围内使用，或先创建小镇"));
        return 0;
    }
}
