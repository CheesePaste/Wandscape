package com.wsteam.wandscape.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.transport.ItemTransportManager;
import com.wsteam.wandscape.shared.data.ItemKey;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

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

        return Commands.literal("transport")
                .then(nodeFx)
                .build();
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
        t.send(key, from, to, player.level(), -1).thenAccept(v -> {
            src.sendSuccess(() -> Component.literal(
                    "[Transport] " + itemId + " arrived! Returning..."), false);
            t.send(key, to, from, player.level(), -1).thenAccept(v2 -> {
                src.sendSuccess(() -> Component.literal(
                        "[Transport] Round-trip complete! ✓"), false);
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
        t.send(key, from, to, player.level(), -1).thenAccept(v -> {
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
                return t.send(key, from, to, player.level(), -1);
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
}
