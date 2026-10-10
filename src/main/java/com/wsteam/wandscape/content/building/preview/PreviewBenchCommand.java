package com.wsteam.wandscape.content.building.preview;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * 客户端命令 {@code /wsbench}：量一次建筑预览的**烘焙速度**与**磁盘读取速度**。
 *
 * <p>预览缓存只存在于客户端（客户端 config + GL + 客户端目录），所以这条命令注册在
 * {@code RegisterClientCommandsEvent} 上，单机 / 联机 / 不需要 OP 都能用。
 *
 * <p>刻意用独立根名而不是挂到服务端的 {@code /wandscape} 下：客户端命令树与服务端命令树在收到
 * 服务端命令表时会被合并，**同名根会互相顶掉**（历史上这是客户端命令「注册了却用不了」的常见原因），
 * 独立根名从根上避开这件事。
 *
 * <ul>
 *   <li>{@code /wsbench bake} —— 强制重烤（跳过磁盘读取，并清空逐配置缓存）：冷启动的真实烘焙成本；</li>
 *   <li>{@code /wsbench load} —— 只走磁盘读取：缓存已经在盘上时的启动成本（miss 表示该建筑没有缓存）；</li>
 *   <li>{@code /wsbench} 或 {@code /wsbench both} —— 先烤一遍再读一遍，一次拿两个数字。</li>
 * </ul>
 *
 * <p>结果分阶段给出（meta / draw / readback / post / encode / read / upload），同时进日志与聊天栏。
 */
public final class PreviewBenchCommand {

    private PreviewBenchCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("wsbench")
                .executes(ctx -> run(ctx.getSource(), BuildingPreviewCache.BenchMode.BOTH))
                .then(Commands.literal("both")
                        .executes(ctx -> run(ctx.getSource(), BuildingPreviewCache.BenchMode.BOTH)))
                .then(Commands.literal("bake")
                        .executes(ctx -> run(ctx.getSource(), BuildingPreviewCache.BenchMode.BAKE)))
                .then(Commands.literal("load")
                        .executes(ctx -> run(ctx.getSource(), BuildingPreviewCache.BenchMode.LOAD)));
        dispatcher.register(root);
    }

    private static int run(CommandSourceStack source, BuildingPreviewCache.BenchMode mode) {
        // 客户端命令由输入处理驱动，本来就是渲染线程；烘焙/回读都必须在渲染线程上跑。
        if (Minecraft.getInstance() == null) {
            return 0;
        }
        if (!BuildingPreviewCache.startBenchmark(mode)) {
            source.sendFailure(Component.literal("预览基准测试已经在跑了，等它跑完再开"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("预览基准测试开始（" + mode.name().toLowerCase()
                + "）：每帧一栋，跑完自动把结果贴出来"), false);
        return 1;
    }
}
