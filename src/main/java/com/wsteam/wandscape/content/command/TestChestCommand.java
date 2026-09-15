package com.wsteam.wandscape.content.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.colony.exploration.ExplorationRegionConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Debug command to spawn unopened naturally-generated loot chests at the player's feet
 * for testing exploration rewards, cluster expectations, and HUD presentation.
 *
 * <p>Usage:
 * <pre>
 *   /wandscape test chest                   — 随机生成一个未开封宝箱在脚下
 *   /wandscape test chest &lt;region&gt;          — 指定地域生成未开封宝箱（支持简单地牢、废弃矿井等预设或数据包地域）
 *   /wandscape test chest &lt;region&gt; &lt;pos&gt;    — 在指定坐标生成
 *   /wandscape test spawn_chest [args...]   — 别名
 *   /wandscape chest [args...]              — 顶层别名（需OP权限）
 * </pre>
 */
public final class TestChestCommand {

    private TestChestCommand() {}

    public record ChestPreset(String regionId, String name, ResourceKey<LootTable> lootTable) {}

    public static final List<ChestPreset> PRESETS = List.of(
            new ChestPreset("simple_dungeon", "地牢", BuiltInLootTables.SIMPLE_DUNGEON),
            new ChestPreset("abandoned_mineshaft", "废弃矿井", BuiltInLootTables.ABANDONED_MINESHAFT),
            new ChestPreset("desert_pyramid", "沙漠神殿", BuiltInLootTables.DESERT_PYRAMID),
            new ChestPreset("jungle_temple", "丛林神庙", BuiltInLootTables.JUNGLE_TEMPLE),
            new ChestPreset("ancient_city", "远古之城", BuiltInLootTables.ANCIENT_CITY),
            new ChestPreset("nether_fortress", "下界要塞", BuiltInLootTables.NETHER_BRIDGE),
            new ChestPreset("end_city", "末地城", BuiltInLootTables.END_CITY_TREASURE),
            new ChestPreset("stronghold", "要塞", BuiltInLootTables.STRONGHOLD_CORRIDOR),
            new ChestPreset("shipwreck", "沉船宝藏", BuiltInLootTables.SHIPWRECK_TREASURE),
            new ChestPreset("village", "村庄", BuiltInLootTables.VILLAGE_PLAINS_HOUSE)
    );

    public static CommandNode<CommandSourceStack> node() {
        return buildNode("chest").build();
    }

    public static CommandNode<CommandSourceStack> spawnChestNode() {
        return buildNode("spawn_chest").build();
    }

    public static CommandNode<CommandSourceStack> rootNode() {
        return buildNode("chest")
                .requires(src -> src.hasPermission(2))
                .build();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildNode(String name) {
        return Commands.literal(name)
                .executes(ctx -> execute(ctx, null, null))
                .then(Commands.argument("region", StringArgumentType.word())
                        .suggests(TestChestCommand::suggestRegions)
                        .executes(ctx -> execute(ctx, StringArgumentType.getString(ctx, "region"), null))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> execute(ctx,
                                        StringArgumentType.getString(ctx, "region"),
                                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))));
    }

    private static CompletableFuture<Suggestions> suggestRegions(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        List<String> suggestions = new ArrayList<>();
        suggestions.add("random");
        for (ChestPreset p : PRESETS) {
            suggestions.add(p.regionId());
        }
        if (Wandscape.EXPLORATION_REGION_LOADER != null) {
            for (String regId : Wandscape.EXPLORATION_REGION_LOADER.getAllRegions().keySet()) {
                if (!suggestions.contains(regId)) {
                    suggestions.add(regId);
                }
            }
        }
        return SharedSuggestionProvider.suggest(suggestions, builder);
    }

    private static int execute(CommandContext<CommandSourceStack> ctx, @Nullable String regionArg, @Nullable BlockPos posArg) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        ServerPlayer player = src.getPlayer();

        BlockPos pos;
        if (posArg != null) {
            pos = posArg;
        } else if (player != null) {
            pos = player.blockPosition();
        } else {
            pos = BlockPos.containing(src.getPosition());
        }

        ChestPreset targetPreset = resolvePreset(regionArg, level.random);
        ResourceKey<LootTable> lootKey = targetPreset.lootTable();
        String regionDisplayName = targetPreset.name();

        Direction facing = player != null ? player.getDirection().getOpposite() : Direction.NORTH;
        BlockState chestState = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, facing)
                .setValue(ChestBlock.TYPE, ChestType.SINGLE);

        level.setBlock(pos, chestState, 3);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RandomizableContainer rc) {
            rc.setLootTable(lootKey, level.random.nextLong());
            be.setChanged();
        }
        level.sendBlockUpdated(pos, chestState, chestState, 3);

        level.playSound(null, pos, SoundEvents.CHEST_LOCKED, SoundSource.BLOCKS, 0.8F, 1.2F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5, 6, 0.25, 0.25, 0.25, 0.02);

        String msg = String.format("§a已在 [%d, %d, %d] 生成未开封宝箱 (地域: §e%s§a, 战利品表: §7%s§a)",
                pos.getX(), pos.getY(), pos.getZ(), regionDisplayName, lootKey.location());
        src.sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }

    private static ChestPreset resolvePreset(@Nullable String regionArg, RandomSource random) {
        if (regionArg == null || regionArg.isBlank() || regionArg.equalsIgnoreCase("random")) {
            return PRESETS.get(random.nextInt(PRESETS.size()));
        }

        // 1. Check known presets by regionId
        for (ChestPreset p : PRESETS) {
            if (p.regionId().equalsIgnoreCase(regionArg)) {
                return p;
            }
        }

        // 2. Check loaded datapack exploration regions
        if (Wandscape.EXPLORATION_REGION_LOADER != null) {
            ExplorationRegionConfig config = Wandscape.EXPLORATION_REGION_LOADER.getRegion(regionArg.toLowerCase());
            if (config != null) {
                // If the region patterns match any preset, use that preset's loot table
                for (ChestPreset p : PRESETS) {
                    if (config.matches(p.lootTable().location().toString())) {
                        return new ChestPreset(config.id(), config.name(), p.lootTable());
                    }
                }
                // If config has raw loot table patterns that look like direct locations
                for (String pat : config.lootTablePatterns()) {
                    String clean = pat.replace("^", "").replace("$", "");
                    if (ResourceLocation.tryParse(clean) != null) {
                        return new ChestPreset(config.id(), config.name(),
                                ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse(clean)));
                    }
                }
            }
        }

        // 3. Check if user typed a direct resource location or chest name
        String candidateLoc = regionArg;
        if (!candidateLoc.contains(":")) {
            candidateLoc = candidateLoc.startsWith("chests/") ? candidateLoc : "chests/" + candidateLoc;
            candidateLoc = "minecraft:" + candidateLoc;
        }
        ResourceLocation rl = ResourceLocation.tryParse(candidateLoc);
        if (rl != null) {
            ResourceKey<LootTable> customKey = ResourceKey.create(Registries.LOOT_TABLE, rl);
            // Same naming the HUD uses: matched region name, else derived from the loot table id.
            String customName = Wandscape.EXPLORATION_REGION_LOADER != null
                    ? Wandscape.EXPLORATION_REGION_LOADER.resolveDisplayName(candidateLoc)
                    : regionArg;
            return new ChestPreset(regionArg, customName, customKey);
        }

        // Fallback to random preset
        return PRESETS.get(random.nextInt(PRESETS.size()));
    }
}
