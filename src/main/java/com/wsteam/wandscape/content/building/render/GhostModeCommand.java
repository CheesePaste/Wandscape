package com.wsteam.wandscape.content.building.render;

import com.mojang.brigadier.context.CommandContext;
import com.wsteam.wandscape.content.building.projection.client.ProjectionClientState;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * <b>临时的 A/B 测量指令</b>：切换虚影观感并报告上一段窗口的平均帧时间。
 *
 * <ul>
 *   <li><b>透视</b>（默认）：关掉深度写入，任何面都不挡别的面，从任何角度看都能看进楼里。
 *       填充最贵的一档 —— 视线穿过的每一层都参与混色。</li>
 *   <li><b>实心壳</b>：保留深度写入，段按由近到远排序，墙后的面被深度测试拒掉。</li>
 * </ul>
 *
 * 见 {@link BuildingGhostVboCache#drawVisibleSections}。测完（用户选定一种观感）连同被淘汰
 * 那一档的代码、这个开关和这条指令一起删掉。
 *
 * <p>注册在客户端命令派发器上（{@link RegisterClientCommandsEvent}）：NeoForge 的
 * {@code ClientCommandHandler#runCommand} 会在把指令发给服务端之前先本地执行，所以这条指令
 * 从不下发到服务端。采样条件是「虚影这一两 tick 真的被画过」（{@link BuildingGhostVboCache#lastDrawTick()}），
 * 与处于哪个放置模式无关。
 *
 * <pre>
 * /ghostmode              切一次并报告上一段的平均帧时间
 * /ghostmode xray|solid   直接设定，同样报告上一段
 * </pre>
 */
public final class GhostModeCommand {

    /** 低于这个样本数只提示、不给数字——窗口太短的均值没有意义。 */
    private static final int MIN_SAMPLES = 40;

    private static boolean registered;
    private static double msSum;
    private static int samples;

    private GhostModeCommand() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, GhostModeCommand::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, GhostModeCommand::onClientTick);
        Log.debug(LogCategory.BUILDING, "render", "[GhostMode] /ghostmode registered (temporary A/B command)");
    }

    private static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ghostmode")
                .executes(ctx -> apply(ctx, !BuildingGhostVboCache.isXray()))
                .then(Commands.literal("xray").executes(ctx -> apply(ctx, true)))
                .then(Commands.literal("solid").executes(ctx -> apply(ctx, false))));
    }

    private static int apply(CommandContext<CommandSourceStack> ctx, boolean xray) {
        String window = report();
        BuildingGhostVboCache.setXray(xray);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[Wandscape] 虚影模式 = " + (xray ? "透视" : "实心壳") + " | " + window), false);
        return 1;
    }

    /** 汇报上一段窗口并重置它。 */
    private static String report() {
        int n = samples;
        double sum = msSum;
        samples = 0;
        msSum = 0.0;
        String diag = diagnostics();
        if (n < MIN_SAMPLES) {
            return "上一段样本太少(" + n + " tick)：保持虚影显示不动跑够十几秒再切 | " + diag;
        }
        double avgMs = sum / n;
        return String.format("上一段 %d tick / 平均 %.2f ms 每帧(约 %.1f fps)", n, avgMs, 1000.0 / avgMs)
                + " | " + diag;
    }

    /** 采样条件到底满没满足，一把报出来，免得只能猜。 */
    private static String diagnostics() {
        Minecraft mc = Minecraft.getInstance();
        long now = mc.level != null ? mc.level.getGameTime() : -1L;
        long last = BuildingGhostVboCache.lastDrawTick();
        String since = last == Long.MIN_VALUE ? "从未" : (now - last) + " tick 前";
        return "诊断[投影=" + ProjectionClientState.isProjecting()
                + " 虚影位置=" + ProjectionClientState.getGhostPos()
                + " 面板=" + WandscapePanelState.isPanelOpen()
                + " 上次绘制=" + since
                + " fps=" + mc.getFps() + "]";
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        // 取样条件 = 「虚影这一两 tick 真的被画过」这个事实，而不是猜当前处于哪个模式：
        // 放置虚影与工地虚影分属两个渲染器、门控条件不同。
        long last = BuildingGhostVboCache.lastDrawTick();
        if (last == Long.MIN_VALUE || mc.level.getGameTime() - last > 2) return;
        int fps = mc.getFps();
        if (fps <= 0) return;
        msSum += 1000.0 / fps;
        samples++;
    }
}
