package com.wsteam.wandscape.command;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.wand.internal.WandPresetLoader.WandPreset;
import com.wsteam.wandscape.warehouse.ColonyItemBank;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Debug command: inject starter resources into the colony warehouse.
 *
 * <p>Seeds the colony at the player's position with:
 * <ul>
 *   <li>1x builder_wand</li>
 *   <li>64x of every non-air block used by any building config</li>
 *   <li>128x of every WarehouseSource-tracked resource (wood, stone, etc.)</li>
 * </ul>
 *
 * <p>Usage: {@code /wandscape seed_warehouse}
 */
public final class SeedWarehouseCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

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

        // 1. Resolve colony
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

        // WarehouseManager implements both WarehouseApi and ColonyResourceAccess
        var warehouseApi = WandscapeApis.getWarehouseApiSilently();
        ColonyResourceAccess resources = (warehouseApi instanceof ColonyResourceAccess cra) ? cra : null;

        int seeded = 0;

        // 2. Builder wand (has NBT — only goes through bank)
        WandPreset preset = Wandscape.WAND_PRESET_LOADER.getPreset("builder_wand");
        if (preset != null) {
            ItemKey wandKey = ItemKey.of("wandscape:wand", preset.nbt().copy());
            bank.add(colonyId, wandKey, 1);
            seeded++;
        }

        // 3. 64x of every unique non-air block across ALL building configs
        Set<String> seen = new LinkedHashSet<>();
        for (BuildingConfig cfg : BuildingConfigLoader.getInstance().getAll().values()) {
            for (String blockId : cfg.blockMapping().values()) {
                if ("minecraft:air".equals(blockId)) continue;
                seen.add(blockId);
            }
        }
        for (String blockId : seen) {
            if (resources != null) {
                resources.addResource(new ResourceId(blockId), 64);
            } else {
                bank.add(colonyId, ItemKey.of(blockId, null), 64);
            }
            seeded++;
        }

        // 4. 128x of threshold-tracked resources
        ResourceId[] thresholdResources = {
                ResourceId.WOOD, ResourceId.STONE, ResourceId.STONE_BRICKS,
                ResourceId.GLASS, ResourceId.IRON_INGOT
        };
        for (ResourceId rid : thresholdResources) {
            if (resources != null) {
                resources.addResource(rid, 128);
            } else {
                bank.add(colonyId, ItemKey.of(rid.id(), null), 128);
            }
            seeded++;
        }

        LOGGER.info("[SeedWarehouse] colony={} seeded {} item types ({} unique materials + starter resources)",
                colonyId.toString().substring(0, 8), seeded, seen.size());

        int totalSeeded = seeded;
        int materialTypes = seen.size();
        String colonyIdShort = colonyId.toString().substring(0, 8);

        src.sendSuccess(() -> Component.literal(
                "[Wandscape] Seeded warehouse for colony " + colonyIdShort
                + ": builder_wand + " + materialTypes + " material types x64 + 5 resource types x128 ("
                + totalSeeded + " total entries)"),
                true);
        return Command.SINGLE_SUCCESS;
    }
}
