package com.wsteam.wandscape.content.building.render;

import com.mojang.brigadier.context.CommandContext;
import com.wsteam.wandscape.content.building.projection.client.ProjectionClientState;
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
 * <b>临时的 A/B 测量指令</b>：切换虚影「段按由近到远排序」
 * （{@link BuildingGhostVboCache#drawVisibleSections}），并在每次切换时报告上一段窗口的平均
 * 帧时间——排序只省填充（每个像素的混色层数约 3 → 1），不省顶点，所以值不值得只能靠实测帧时间。
 * 测完（决定排序去留）连同 {@code BuildingGhostVboCache} 里那个开关一起删掉。
 *
 * <p>注册在客户端命令派发器上（{@link RegisterClientCommandsEvent}）：NeoForge 的
 * {@code ClientCommandHandler#runCommand} 会在把指令发给服务端之前先本地执行，所以这条指令
 * 从不下发到服务端。采样只在「投影虚影确实在画」的 tick 上累加，两种状态的样本才可比。
 *
 * <pre>
 * /ghostsort         切一次（开 ↔ 关）并报告上一段的平均帧时间
 * /ghostsort on|off  直接设定，同样报告上一段
 * </pre>
 */
public final class GhostSortCommand {

    /** 低于这个样本数只提示、不给数字——窗口太短的均值没有意义。 */
    private static final int MIN_SAMPLES = 40;

    private static boolean registered;
    private static double msSum;
    private static int samples;

    private GhostSortCommand() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, GhostSortCommand::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, GhostSortCommand::onClientTick);
        Log.debug(LogCategory.BUILDING, "render", "[GhostSort] /ghostsort registered (temporary A/B command)");
    }

    private static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ghostsort")
                .executes(ctx -> apply(ctx, !BuildingGhostVboCache.isFrontToBackSorting()))
                .then(Commands.literal("on").executes(ctx -> apply(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> apply(ctx, false))));
    }

    private static int apply(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        String window = report();
        BuildingGhostVboCache.setFrontToBackSorting(enabled);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[Wandscape] 虚影段排序 = " + (enabled ? "开" : "关") + " | " + window), false);
        return 1;
    }

    /** 汇报上一段窗口并重置它。 */
    private static String report() {
        int n = samples;
        double sum = msSum;
        samples = 0;
        msSum = 0.0;
        if (n < MIN_SAMPLES) {
            return "上一段样本太少(" + n + " tick)：每段请保持投影状态跑够十几秒再切";
        }
        double avgMs = sum / n;
        return String.format("上一段 %d tick / 平均 %.2f ms 每帧(约 %.1f fps)", n, avgMs, 1000.0 / avgMs);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        // 只在虚影真的会被画出来时采样，否则两种状态量到的是同一件事
        if (!ProjectionClientState.isProjecting() || ProjectionClientState.getGhostPos() == null) return;
        int fps = mc.getFps();
        if (fps <= 0) return;
        msSum += 1000.0 / fps;
        samples++;
    }
}
