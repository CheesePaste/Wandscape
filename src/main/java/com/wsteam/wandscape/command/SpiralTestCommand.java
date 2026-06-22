package com.wsteam.wandscape.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.road.PathGenerator;
import com.wsteam.wandscape.core.road.PathPoint;
import com.wsteam.wandscape.engine.road.RoadBuilder;
import com.wsteam.wandscape.engine.road.RoadConfig;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * DIRECT block placement test for spiral/switchback road.
 *
 * <p>Places 2 buildings A and B, generates the road path between them,
 * and directly sets blocks in one frame — no NPCs, no task pool.
 * Use this to visually verify the path generator output.
 *
 * <p>Usage:
 * <pre>
 *   /wandscape spiraltest                — default: destY = playerY-20, XZ=8
 *   /wandscape spiraltest &lt;destY&gt; &lt;xz&gt; — custom Y and XZ distance
 * </pre>
 *
 * <p>Building A is at the player position. Building B is offset XZ blocks
 * away at the given destY. Check the road visually; adjust params to
 * test different steepnesses.
 */
public final class SpiralTestCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private SpiralTestCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("spiraltest")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> execute(ctx, null, 8))
                .then(Commands.argument("destY", IntegerArgumentType.integer(-64, 320))
                        .executes(ctx -> execute(ctx,
                                IntegerArgumentType.getInteger(ctx, "destY"), 8))
                        .then(Commands.argument("xz", IntegerArgumentType.integer(4, 64))
                                .executes(ctx -> execute(ctx,
                                        IntegerArgumentType.getInteger(ctx, "destY"),
                                        IntegerArgumentType.getInteger(ctx, "xz")))))
                .build();
    }

    private static int execute(CommandContext<CommandSourceStack> ctx,
                                Integer destYArg, int xz) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("[SpiralTest] Player-only command"));
            return 0;
        }
        ServerLevel level = player.serverLevel();

        // ── 1. Building anchors ──
        BlockPos anchorA = new BlockPos(
                (int) player.getX(), (int) player.getY(), (int) player.getZ());
        int destY = (destYArg != null) ? destYArg : anchorA.getY() - 20;
        BlockPos anchorB = new BlockPos(anchorA.getX() + xz, destY, anchorA.getZ());

        // ── 2. Generate path ──
        RoadConfig config = RoadConfig.getInstance();
        int amplitude = config.getDefaultWidth() * 2;
        PathPoint from = new PathPoint(anchorA.getX(), anchorA.getY(), anchorA.getZ());
        PathPoint to = new PathPoint(anchorB.getX(), anchorB.getY(), anchorB.getZ());

        List<PathPoint> path = PathGenerator.lShape3D(from, to, amplitude);
        if (path.isEmpty()) {
            src.sendFailure(Component.literal("[SpiralTest] Path empty — same XZ and Y?"));
            return 0;
        }

        // ── 3. Build road tiles (no building bounds, empty occupied set) ──
        String tier = "dirt";
        Set<PathPoint> occupied = new HashSet<>();
        JsonArray tiles = RoadBuilder.buildTiles(
                level, path, tier, List.of(), occupied, 3);

        // ── 4. Directly set blocks (no tasks, no NPCs) ──
        int placed = 0;
        int errored = 0;
        for (int i = 0; i < tiles.size(); i++) {
            JsonObject tile = tiles.get(i).getAsJsonObject();
            JsonArray posArr = tile.getAsJsonArray("pos");
            String blockId = tile.get("block").getAsString();

            BlockPos pos = new BlockPos(
                    posArr.get(0).getAsInt(),
                    posArr.get(1).getAsInt(),
                    posArr.get(2).getAsInt());

            BlockState state;
            if ("minecraft:air".equals(blockId)) {
                state = Blocks.AIR.defaultBlockState();
            } else {
                Block block = BuiltInRegistries.BLOCK.get(
                        ResourceLocation.parse(blockId));
                if (block == Blocks.AIR && !"minecraft:air".equals(blockId)) {
                    LOGGER.warn("[SpiralTest] Unknown block '{}' at {}", blockId, pos);
                    errored++;
                    continue;
                }
                state = block.defaultBlockState();
            }

            level.setBlock(pos, state, 3); // Block.UPDATE_ALL
            placed++;
        }

        // ── 5. Place marker blocks at anchors (glowstone) ──
        level.setBlock(anchorA, Blocks.GLOWSTONE.defaultBlockState(), 3);
        level.setBlock(anchorB, Blocks.GLOWSTONE.defaultBlockState(), 3);

        // ── 6. Report ──
        int dy = Math.abs(anchorA.getY() - anchorB.getY());
        int approxXzSteps = Math.abs(anchorA.getX() - anchorB.getX())
                + Math.abs(anchorA.getZ() - anchorB.getZ());
        String msg = String.format(
                "[SpiralTest] Path + road placed directly (1 frame):\n"
                        + "  A: %s  (glowstone)\n"
                        + "  B: %s  (glowstone)\n"
                        + "  ΔY=%d  XZ steps=%d  path points=%d  tiles=%d  errors=%d\n"
                        + "  Amplitude=%d  roadWidth=%d",
                anchorA, anchorB, dy, approxXzSteps,
                path.size(), tiles.size(), errored,
                amplitude, config.getDefaultWidth());

        src.sendSuccess(() -> Component.literal("§a" + msg), false);
        LOGGER.info("[SpiralTest] A={} B={} dy={} pathLen={} tiles={} placed={}",
                anchorA, anchorB, dy, path.size(), tiles.size(), placed);

        return Command.SINGLE_SUCCESS;
    }
}
