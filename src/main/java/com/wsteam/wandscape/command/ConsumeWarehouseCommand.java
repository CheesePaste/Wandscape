package com.wsteam.wandscape.command;

import java.util.UUID;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;

import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.warehouse.ColonyItemBank;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Debug command: consume (clear) all items and elements from the colony warehouse.
 *
 * <p>Useful for testing auto-production thresholds — after clearing everything,
 * WarehouseSource will detect low resources and trigger synthesis tasks.
 *
 * <p>Usage: {@code /wandscape consume_warehouse}
 */
public final class ConsumeWarehouseCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ConsumeWarehouseCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("consume_warehouse")
                .requires(src -> src.hasPermission(2))
                .executes(ConsumeWarehouseCommand::execute)
                .build();
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Vec3 pos = src.getPosition();
        BlockPos blockPos = BlockPos.containing(pos);

        ServerLevel level = src.getServer().overworld();
        if (level == null) {
            src.sendFailure(Component.literal("[Wandscape] No overworld available"));
            return 0;
        }

        // Resolve colony
        ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
        UUID colonyId;
        if (colonyApi != null) {
            colonyId = colonyApi.getColonyId(blockPos);
        } else {
            colonyId = new UUID(0, 0);
        }
        if (colonyId == null) {
            colonyId = new UUID(0, 0);
        }

        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) {
            src.sendFailure(Component.literal("[Wandscape] ColonyItemBank not available"));
            return 0;
        }

        // Count before clearing
        var itemSnapshot = bank.getSnapshot(colonyId);
        int itemTypesBefore = itemSnapshot.size();
        long totalItemsBefore = itemSnapshot.values().stream().mapToLong(Long::longValue).sum();

        var elemSnapshot = bank.getElementSnapshot(colonyId);
        int elemTypesBefore = elemSnapshot.size();
        long totalElementsBefore = elemSnapshot.values().stream().mapToLong(Long::longValue).sum();

        // Clear all items — use exact available count (Long.MAX_VALUE would fail the guard check)
        for (ItemKey key : itemSnapshot.keySet()) {
            long avail = itemSnapshot.getOrDefault(key, 0L);
            if (avail > 0) {
                bank.consume(colonyId, key, avail);
            }
        }

        // Clear all elements — use exact available count
        for (ElementType type : elemSnapshot.keySet()) {
            long avail = elemSnapshot.getOrDefault(type, 0L);
            if (avail > 0) {
                bank.consumeElement(colonyId, type, avail);
            }
        }

        String colonyIdShort = colonyId.toString().substring(0, 8);
        LOGGER.info("[ConsumeWarehouse] colony={} cleared {} item types ({} total), {} element types ({} total)",
                colonyIdShort, itemTypesBefore, totalItemsBefore, elemTypesBefore, totalElementsBefore);

        src.sendSuccess(() -> Component.literal(
                "[Wandscape] Cleared warehouse for colony " + colonyIdShort
                + ": " + totalItemsBefore + " items (" + itemTypesBefore + " types), "
                + totalElementsBefore + " elements (" + elemTypesBefore + " types)"),
                true);
        return Command.SINGLE_SUCCESS;
    }
}
