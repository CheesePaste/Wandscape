package com.wsteam.wandscape.command;

import java.util.UUID;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.wand.internal.WandPresetLoader.WandPreset;
import com.wsteam.wandscape.warehouse.ColonyItemBank;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Debug command: seed 9999 of every registered Minecraft item into the colony warehouse.
 *
 * <p>Also adds a builder_wand. Items are written directly to {@link ColonyItemBank}.
 *
 * <p>Usage: {@code /wandscape seed_warehouse}
 */
public final class SeedWarehouseCommand {

    private static final String TAG = "SeedWarehouseCommand";
    private static final int DEBUG_COUNT = 9999;

    private SeedWarehouseCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("seed_warehouse")
                .requires(src -> src.hasPermission(2))
                .executes(SeedWarehouseCommand::execute)
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

        // Builder wand
        WandPreset preset = Wandscape.WAND_PRESET_LOADER.getPreset("builder_wand");
        if (preset != null) {
            bank.add(colonyId, ItemKey.of("wandscape:wand", preset.nbt().copy()), 1);
        }

        // 9999 of every registered Minecraft item
        int seeded = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (key == null) continue;
            String itemId = key.toString();
            bank.add(colonyId, ItemKey.of(itemId, null), DEBUG_COUNT);
            seeded++;
        }

        // 9999 of every element type for shop restock testing
        for (ElementType element : ElementType.values()) {
            bank.addElement(colonyId, element, DEBUG_COUNT);
        }

        String colonyIdShort = colonyId.toString().substring(0, 8);
        Log.info(TAG, "[SeedWarehouse] colony={} seeded {} item types x{}", colonyIdShort, seeded, DEBUG_COUNT);

        int totalSeeded = seeded;
        src.sendSuccess(() -> Component.literal(
                "[Wandscape] Seeded warehouse for colony " + colonyIdShort
                + ": " + totalSeeded + " item types x" + DEBUG_COUNT),
                true);
        return Command.SINGLE_SUCCESS;
    }
}
