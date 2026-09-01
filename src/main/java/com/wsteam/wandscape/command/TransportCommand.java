package com.wsteam.wandscape.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.content.road.algorithm.RoadRouter;
import com.wsteam.wandscape.content.road.core.PathPoint;
import com.wsteam.wandscape.content.road.core.RoadEdge;
import com.wsteam.wandscape.content.road.core.SplineLeg;
import com.wsteam.wandscape.content.road.core.TransportRoute;
import com.wsteam.wandscape.content.road.engine.RoadSavedData;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.transport.ItemTransportManager;
import com.wsteam.wandscape.engine.transport.TransportItemEntity;
import com.wsteam.wandscape.shared.data.ItemKey;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.concurrent.atomic.AtomicInteger;
/**
 * Debug command: test item transport animation.
 *
 * <p>Usage:
 * <pre>
 *   /wandscape transport &lt;fromX&gt; &lt;fromY&gt; &lt;fromZ&gt; [item]          — item flies to player, then back
 *   /wandscape transport &lt;fromX&gt; &lt;fromY&gt; &lt;fromZ&gt; &lt;toX&gt; &lt;toY&gt; &lt;toZ&gt; [item]  — item flies one-way
 * </pre>
 */
public final class TransportCommand {

    private TransportCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        // Build from the innermost argument outward:
        // tx ty tz -> oneWay -> count -> batch

        // count (innermost, optional sibling of tz)
        var nodeCount = Commands.argument("count", IntegerArgumentType.integer(1, 100))
                .executes(ctx -> batch(ctx,
                        StringArgumentType.getString(ctx, "item"),
                        IntegerArgumentType.getInteger(ctx, "count")));

        // tz (one-way executor, optional count child)
        var nodeTz = Commands.argument("tz", IntegerArgumentType.integer());
        nodeTz.executes(ctx -> oneWay(ctx, StringArgumentType.getString(ctx, "item")));
        nodeTz.then(nodeCount);

        // ty → tz
        var nodeTy = Commands.argument("ty", IntegerArgumentType.integer());
        nodeTy.then(nodeTz);

        // tx → ty
        var nodeTx = Commands.argument("tx", IntegerArgumentType.integer());
        nodeTx.then(nodeTy);

        // item (round-trip executor, optional tx... chain)
        var nodeItem = Commands.argument("item", StringArgumentType.word());
        nodeItem.executes(ctx -> roundTrip(ctx, StringArgumentType.getString(ctx, "item")));
        nodeItem.then(nodeTx);

        // fz (default stone round-trip, optional item... chain)
        var nodeFz = Commands.argument("fz", IntegerArgumentType.integer());
        nodeFz.executes(ctx -> roundTrip(ctx, "minecraft:stone"));
        nodeFz.then(nodeItem);

        // fy → fz
        var nodeFy = Commands.argument("fy", IntegerArgumentType.integer());
        nodeFy.then(nodeFz);

        // fx → fy
        var nodeFx = Commands.argument("fx", IntegerArgumentType.integer());
        nodeFx.then(nodeFy);

        // Spawn subcommand: /wandscape transport spawn [item] [count]
        var nodeCountSpawn = Commands.argument("count", IntegerArgumentType.integer(1, 100))
                .executes(ctx -> spawnDebug(ctx,
                        StringArgumentType.getString(ctx, "item"),
                        IntegerArgumentType.getInteger(ctx, "count")));

        var nodeItemSpawn = Commands.argument("item", StringArgumentType.word())
                .executes(ctx -> spawnDebug(ctx, StringArgumentType.getString(ctx, "item"), 1));
        nodeItemSpawn.then(nodeCountSpawn);

        var nodeSpawn = Commands.literal("spawn")
                .executes(ctx -> spawnDebug(ctx, "minecraft:stone", 5))
                .then(nodeItemSpawn);

        // Benchmark subcommand: /wandscape transport bench [iterations] [radius]
        var nodeRadius = Commands.argument("radius", IntegerArgumentType.integer(16, 512))
                .executes(ctx -> runBenchmark(ctx,
                        IntegerArgumentType.getInteger(ctx, "iterations"),
                        IntegerArgumentType.getInteger(ctx, "radius")));

        var nodeIterations = Commands.argument("iterations", IntegerArgumentType.integer(1, 50000))
                .executes(ctx -> runBenchmark(ctx,
                        IntegerArgumentType.getInteger(ctx, "iterations"), 96))
                .then(nodeRadius);

        var nodeBench = Commands.literal("bench")
                .executes(ctx -> runBenchmark(ctx, 500, 96))
                .then(nodeIterations);

        return Commands.literal("transport")
                .then(nodeSpawn)
                .then(nodeBench)
                .then(nodeFx)
                .build();
    }

    private static int spawnDebug(CommandContext<CommandSourceStack> ctx, String itemId, int count) {
        ServerPlayer player = getPlayer(ctx);
        if (player == null) return 0;

        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
        if (item == null) {
            ctx.getSource().sendFailure(Component.literal("[Transport] Unknown item: " + itemId));
            return 0;
        }

        ItemStack stack = new ItemStack(item, count);
        TransportItemEntity entity = new TransportItemEntity(player.level(), player.getX(), player.getY() + 0.5, player.getZ(), stack);
        entity.setNoGravity(true);
        entity.setPickUpDelay(32767);
        entity.setUnlimitedLifetime();
        entity.noPhysics = true;
        entity.hasImpulse = true;

        player.level().addFreshEntity(entity);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[Transport] Spawned static debug TransportItemEntity: " + itemId + " x" + count), true);
        return 1;
    }

    /** Round-trip: from → player → back to from */
    private static int roundTrip(CommandContext<CommandSourceStack> ctx, String itemId) {
        ServerPlayer player = getPlayer(ctx);
        if (player == null) return 0;
        ItemTransportManager t = getTransporter(ctx);
        if (t == null) return 0;

        BlockPos from = readFromPos(ctx);
        BlockPos to = player.blockPosition();
        CommandSourceStack src = ctx.getSource();

        ItemKey key = ItemKey.of(itemId, null);
        t.send(key, 1, from, to, player.level(), -1).thenAccept(v -> {
            src.sendSuccess(() -> Component.literal(
                    "[Transport] " + itemId + " arrived! Returning..."), false);
            t.send(key, 1, to, from, player.level(), -1).thenAccept(v2 -> {
                src.sendSuccess(() -> Component.literal(
                        "[Transport] Round-trip complete!"), false);
            });
        });

        src.sendSuccess(() -> Component.literal(
                "[Transport] " + itemId + " flying: " + from.toShortString()
                + " → " + to.toShortString() + " → back"), true);
        return 1;
    }

    /** One-way: from → to */
    private static int oneWay(CommandContext<CommandSourceStack> ctx, String itemId) {
        ServerPlayer player = getPlayer(ctx);
        if (player == null) return 0;
        ItemTransportManager t = getTransporter(ctx);
        if (t == null) return 0;

        BlockPos from = readFromPos(ctx);
        BlockPos to = new BlockPos(
                IntegerArgumentType.getInteger(ctx, "tx"),
                IntegerArgumentType.getInteger(ctx, "ty"),
                IntegerArgumentType.getInteger(ctx, "tz"));
        CommandSourceStack src = ctx.getSource();

        ItemKey key = ItemKey.of(itemId, null);
        t.send(key, 1, from, to, player.level(), -1).thenAccept(v -> {
            src.sendSuccess(() -> Component.literal(
                    "[Transport] " + itemId + " arrived at " + to.toShortString()), false);
        });

        src.sendSuccess(() -> Component.literal(
                "[Transport] " + itemId + " flying: " + from.toShortString()
                + " → " + to.toShortString()), true);
        return 1;
    }

    /** Batch: N items in serial from → to */
    private static int batch(CommandContext<CommandSourceStack> ctx, String itemId, int count) {
        ServerPlayer player = getPlayer(ctx);
        if (player == null) return 0;
        ItemTransportManager t = getTransporter(ctx);
        if (t == null) return 0;

        BlockPos from = readFromPos(ctx);
        BlockPos to = new BlockPos(
                IntegerArgumentType.getInteger(ctx, "tx"),
                IntegerArgumentType.getInteger(ctx, "ty"),
                IntegerArgumentType.getInteger(ctx, "tz"));
        CommandSourceStack src = ctx.getSource();

        ItemKey key = ItemKey.of(itemId, null);
        AtomicInteger arrived = new AtomicInteger(0);

        // Chain N sends
        var chain = java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
        for (int i = 0; i < count; i++) {
            final int idx = i;
            chain = chain.thenCompose(v -> {
                arrived.incrementAndGet();
                return t.send(key, 1, from, to, player.level(), -1);
            });
        }
        chain.thenAccept(v -> {
            src.sendSuccess(() -> Component.literal(
                    "[Transport] Batch complete: " + count + " x " + itemId
                    + " arrived at " + to.toShortString()), false);
        });

        src.sendSuccess(() -> Component.literal(
                "[Transport] Batch " + count + " x " + itemId
                + " flying: " + from.toShortString() + " → " + to.toShortString()), true);
        return 1;
    }

    // ── helpers ──

    private static ServerPlayer getPlayer(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer p = ctx.getSource().getPlayer();
        if (p == null) ctx.getSource().sendFailure(Component.literal("[Transport] Player-only"));
        return p;
    }

    private static ItemTransportManager getTransporter(CommandContext<CommandSourceStack> ctx) {
        var t = WandscapeEngine.getTransporter();
        if (t == null) ctx.getSource().sendFailure(Component.literal("[Transport] Not initialized"));
        return t;
    }

    private static BlockPos readFromPos(CommandContext<CommandSourceStack> ctx) {
        return new BlockPos(
                IntegerArgumentType.getInteger(ctx, "fx"),
                IntegerArgumentType.getInteger(ctx, "fy"),
                IntegerArgumentType.getInteger(ctx, "fz"));
    }

    private static int runBenchmark(CommandContext<CommandSourceStack> ctx, int iterations, int radius) {
        CommandSourceStack src = ctx.getSource();
        var level = src.getLevel();
        BlockPos center = BlockPos.containing(src.getPosition());

        var roadData = RoadSavedData.getOrCreate(level);
        var network = roadData.getNetwork();

        int totalEdges = network.edgeCount();
        long completeEdges = network.getEdges().values().stream()
                .filter(e -> e.getStatus() == RoadEdge.EdgeStatus.COMPLETE
                        && e.getSpline() != null && e.getSpline().getSegmentsCount() > 0)
                .count();

        if (completeEdges == 0) {
            src.sendFailure(Component.literal("§c[Wandscape Road Bench] 当前世界路网中暂无已建成的道路！请先使用道路工具建造道路。"));
            return 0;
        }

        // Random queries within [center - radius, center + radius]
        java.util.Random rand = new java.util.Random(System.currentTimeMillis());
        java.util.List<PathPoint[]> pairs = new java.util.ArrayList<>(iterations);
        for (int i = 0; i < iterations; i++) {
            int sx = center.getX() + rand.nextInt(radius * 2 + 1) - radius;
            int sz = center.getZ() + rand.nextInt(radius * 2 + 1) - radius;
            int sy = center.getY();

            int ex = center.getX() + rand.nextInt(radius * 2 + 1) - radius;
            int ez = center.getZ() + rand.nextInt(radius * 2 + 1) - radius;
            int ey = center.getY();

            pairs.add(new PathPoint[]{
                    new PathPoint(sx, sy, sz),
                    new PathPoint(ex, ey, ez)
            });
        }

        // Warm-up
        for (int i = 0; i < Math.min(50, iterations); i++) {
            PathPoint[] p = pairs.get(i);
            RoadRouter.plan(network, p[0], p[1]);
        }

        // Benchmark
        long[] latencies = new long[iterations];
        int onRoadCount = 0;
        int directCount = 0;
        long totalLegs = 0;
        long totalOnRoadLegs = 0;
        long totalOffRoadLegs = 0;

        long tStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            PathPoint[] p = pairs.get(i);
            long t0 = System.nanoTime();
            TransportRoute route = RoadRouter.plan(network, p[0], p[1]);
            long t1 = System.nanoTime();
            latencies[i] = t1 - t0;

            int legs = route.legs().size();
            totalLegs += legs;
            long onRoadLegs = route.legs().stream().filter(l -> !l.offRoad()).count();
            long offRoadLegs = route.legs().stream().filter(SplineLeg::offRoad).count();
            totalOnRoadLegs += onRoadLegs;
            totalOffRoadLegs += offRoadLegs;

            if (onRoadLegs > 0) onRoadCount++;
            else directCount++;
        }
        long totalNanos = System.nanoTime() - tStart;

        java.util.Arrays.sort(latencies);

        double totalMs = totalNanos / 1_000_000.0;
        double avgMicros = (totalNanos / 1000.0) / iterations;
        double minMicros = latencies[0] / 1000.0;
        double p50Micros = latencies[(int) (iterations * 0.50)] / 1000.0;
        double p95Micros = latencies[(int) (iterations * 0.95)] / 1000.0;
        double maxMicros = latencies[iterations - 1] / 1000.0;
        double opsPerSec = (iterations / (double) totalNanos) * 1_000_000_000.0;

        double usagePct = (onRoadCount * 100.0) / iterations;

        int finalOnRoad = onRoadCount;
        int finalDirect = directCount;
        long finalTotalLegs = totalLegs;
        long finalTotalOnRoadLegs = totalOnRoadLegs;
        long finalTotalOffRoadLegs = totalOffRoadLegs;

        src.sendSuccess(() -> Component.literal("§6══════════ §e§lWandscape 路网寻路压力测试报告 §6══════════"), false);
        src.sendSuccess(() -> Component.literal(String.format("§7• §f路网规模: §a%d §7条道路已建成 (总计 §a%d §7条)", completeEdges, totalEdges)), false);
        src.sendSuccess(() -> Component.literal(String.format("§7• §f采样范围: §b%d §7次寻路请求 (半径: §e%d 格§7, 耗时: §b%.2f ms§7)", iterations, radius, totalMs)), false);
        src.sendSuccess(() -> Component.literal(String.format("§7• §f寻路吞吐量: §a%,.0f §7次/秒 (QPS)", opsPerSec)), false);
        src.sendSuccess(() -> Component.literal(String.format("§7• §f平均延迟: §a%.2f μs §7(%.4f ms)", avgMicros, avgMicros / 1000.0)), false);
        src.sendSuccess(() -> Component.literal(String.format("§7• §f延迟分布: §7Min §a%.1fμs §7| P50 §a%.1fμs §7| P95 §e%.1fμs §7| Max §c%.1fμs", minMicros, p50Micros, p95Micros, maxMicros)), false);
        src.sendSuccess(() -> Component.literal(String.format("§7• §f路网利用率: §a%.1f%% §7(走道路: §a%d§7, 直飞: §7%d)", usagePct, finalOnRoad, finalDirect)), false);
        src.sendSuccess(() -> Component.literal(String.format("§7• §f平均航段数: §f%.2f §7段 (贴路: §a%.2f§7, 野路跳跃: §e%.2f§7)",
                (double) finalTotalLegs / iterations, (double) finalTotalOnRoadLegs / iterations, (double) finalTotalOffRoadLegs / iterations)), false);
        src.sendSuccess(() -> Component.literal("§6══════════════════════════════════════════════"), false);

        return 1;
    }
}
