package com.wsteam.wandscape.command;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;

import com.wsteam.wandscape.core.component.ColonyMember;
import com.wsteam.wandscape.core.component.ColonyMetadata;
import com.wsteam.wandscape.core.component.Inventory;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.ColonyApiImpl;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.event.ColonyCreatedEvent;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Colony lifecycle commands.
 *
 * <p>Usage:
 * <pre>
 *   /wandscape colony create <name> [x y z]
 * </pre>
 *
 * <p>Creates a colony with a new UUID, spawns a builder NPC, and fills
 * the NPC's ECS {@link Inventory} with town_hall building materials.
 */
public final class ColonyCommand {

    private static final String TAG = "ColonyCommand";
    private static final int WAND_RANGE = 8;

    private ColonyCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("colony")
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ColonyCommand::createColony)))
                .build();
    }

    private static int createColony(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");

        net.minecraft.server.MinecraftServer server = ctx.getSource().getServer();
        ServerLevel level = server.overworld();
        if (level == null) {
            ctx.getSource().sendFailure(Component.literal("[Wandscape] No overworld available"));
            return 0;
        }

        Entity sender = ctx.getSource().getEntity();
        Vec3 lookVec = sender != null ? sender.getLookAngle() : new Vec3(0, 0, -1);
        Vec3 posVec = sender != null ? sender.position() : Vec3.ZERO;
        BlockPos origin;
        if (sender instanceof net.minecraft.world.entity.player.Player player && player.isShiftKeyDown()) {
            origin = BlockPos.containing(posVec);
        } else {
            origin = BlockPos.containing(
                    posVec.x + lookVec.x * 5,
                    posVec.y,
                    posVec.z + lookVec.z * 5
            );
        }

        // ── Step 1: load config ─────────────────────────────────────────────
        BuildingConfig townHallConfig = BuildingConfigLoader.getInstance().get("town_hall");
        if (townHallConfig == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[Wandscape] town_hall config not found"));
            return 0;
        }

        // ── Step 2: create colonyId ─────────────────────────────────────────
        ColonyApi colonyApi = ColonyApiImpl.get();
        UUID colonyId = colonyApi.createColony(origin);
        Log.info(TAG, "[Colony] Creating colony '{}' id={} at {}", name,
                colonyId.toString().substring(0, 8), origin);

        // ── Step 3: create ECS colony entity ────────────────────────────────
        World world = WandscapeEngine.getWorld();
        if (world != null) {
            ColonyMetadata meta =
                    ColonyMetadata.create(
                            new GridPos(
                                    origin.getX(), origin.getY(), origin.getZ()),
                            64);
            long colonyEntity = world.createEntity();
            world.addComponent(colonyEntity, meta);
            Log.info(TAG, "[Colony] ECS colony entity #{} created", colonyEntity);
        }

        // ── Step 4: spawn builder NPC ───────────────────────────────────────
        // IMPORTANT: spawn() synchronously triggers onAddedToLevel() →
        // onNpcJoinWorld(), which reads npc.colonyId and calls
        // fillDeferredInventory(). Both run BEFORE we set colonyId or
        // schedule inventory — so the ECS NPC gets PLACEHOLDER_COLONY
        // and an empty inventory. We fix both immediately after spawn.
        BlockPos spawnPos = findGroundAbove(level, origin);
        List<ResourceStack> starterItems = computeStarterInventory(townHallConfig);
        var npc = Wandscape.WANDSCAPE_NPC.get().spawn(level, spawnPos, MobSpawnType.COMMAND);
        if (npc == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[Wandscape] Failed to spawn NPC at " + spawnPos));
            return 0;
        }
        npc.setInvulnerable(true);
        npc.setPersistenceRequired();
        npc.colonyId = colonyId;

        Log.info(TAG, "[Colony] Spawned builder NPC at {} for colony {}",
                spawnPos, colonyId.toString().substring(0, 8));

        // ── Step 5: fix ECS state + fill inventory ─────────────────────────
        // spawn() already ran onNpcJoinWorld(). If the NPC joined ECS
        // immediately (engine was bootstrapped), its ColonyMember has
        // PLACEHOLDER_COLONY and its Inventory is empty. Fix both now.
        // For the deferred-join case (engine not yet bootstrapped), the
        // scheduleInventoryFill fallback handles it when flushDeferredJoins
        // runs — npc.colonyId is already set by then.
        fixEcsAfterSpawn(npc, colonyId, starterItems);
        EntityComponentBridge.INSTANCE.scheduleInventoryFill(
                npc.getUUID(), colonyId, starterItems);

        // Seed builder_wand into NPC's MC inventory so WandEquip can shortfill
        // it without needing a storage building (cold-start bootstrap).
        var wandPreset = Wandscape.WAND_PRESET_LOADER.getPreset("builder_wand");
        if (wandPreset != null) {
            var wandRegItem = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .get(net.minecraft.resources.ResourceLocation.tryParse("wandscape:wand"));
            if (wandRegItem != null) {
                var wandStack = new net.minecraft.world.item.ItemStack(wandRegItem);
                wandStack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.of(wandPreset.nbt().copy()));
                npc.inventory.addItem(wandStack);
                Log.info(TAG, "[Colony] Seeded builder_wand into NPC inventory");
            }
        }

        // ── Step 6: fire event ──────────────────────────────────────────────
        NeoForge.EVENT_BUS.post(new ColonyCreatedEvent(colonyId, origin));

        // ── Step 7: reply ───────────────────────────────────────────────────
        int materialTypes = computeUniqueBlockTypes(townHallConfig);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[Wandscape] Colony '" + name + "' created!\n" +
                "  ID: " + colonyId.toString().substring(0, 8) + "\n" +
                "  TownHall: " + origin.toShortString() + "\n" +
                "  NPC: builder at " + spawnPos.toShortString() + "\n" +
                "  Inventory: " + starterItems.size() + " stacks (" + materialTypes + " types)\n" +
                "  Radius: 256 blocks\n" +
                "\nTip: use /wandscape fill town_hall 1 1 to queue construction"),
                true);

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Fix ECS {@code ColonyMember} and {@code Inventory} after spawn.
     *
     * <p>{@code spawn()} synchronously triggers onNpcJoinWorld(), which reads
     * {@code npc.colonyId} (still PLACEHOLDER_COLONY at that point) and calls
     * fillDeferredInventory() (empty at that point). This method corrects both
     * after the fact for the immediate-join case.
     *
     * <p>For the deferred-join case (engine not bootstrapped yet), the ECS
     * entity doesn't exist yet — skip. The {@code scheduleInventoryFill}
     * fallback + correct {@code npc.colonyId} will take effect when
     * flushDeferredJoins runs on the next tick.
     */
    private static void fixEcsAfterSpawn(WandscapeNpc npc, UUID colonyId,
                                         List<ResourceStack> items) {
        World ecsWorld = WandscapeEngine.getWorld();
        if (ecsWorld == null) return;

        Long ecsId = EntityComponentBridge.INSTANCE.getEcsId(npc.getUUID());
        if (ecsId == null) return;

        // Fix ColonyMember: onNpcJoinWorld used PLACEHOLDER_COLONY
        ColonyMember member =
                ecsWorld.get(ecsId, ColonyMember.class);
        if (member != null && !colonyId.equals(member.colonyId())) {
            ecsWorld.addComponent(ecsId,
                    new ColonyMember(colonyId));
            Log.info(TAG, "[Colony] Fixed NPC {} ECS colony {} → {}",
                    npc.getUUID().toString().substring(0, 8),
                    member.colonyId().toString().substring(0, 8),
                    colonyId.toString().substring(0, 8));
        }

        // Fill inventory: deferred fill already consumed by onNpcJoinWorld
        Inventory inv = ecsWorld.get(ecsId, Inventory.class);
        if (inv != null) {
            int added = 0;
            for (ResourceStack stack : items) {
                if (inv.add(stack)) added++;
            }
            if (added > 0) {
                Log.info(TAG, "[Colony] Filled NPC {} ECS inventory with {} stacks",
                        npc.getUUID().toString().substring(0, 8), added);
            }
        }
    }

    /** Find the first air block above a solid surface within 8 blocks of origin. */
    private static BlockPos findGroundAbove(ServerLevel level, BlockPos origin) {
        for (int dy = 0; dy < 8; dy++) {
            BlockPos candidate = origin.offset(0, dy, 0);
            if (level.isEmptyBlock(candidate) && !level.isEmptyBlock(candidate.below())) {
                return candidate;
            }
        }
        return origin.above(2);
    }

    /** Compute material stacks for the NPC's inventory. Each unique block type = 64. */
    private static List<ResourceStack> computeStarterInventory(BuildingConfig config) {
        Set<String> seen = new LinkedHashSet<>();
        for (BlockOffset offset : config.pattern()) {
            String blockId = config.blockMapping().get(offset.toKey());
            if (blockId == null || "minecraft:air".equals(blockId)) continue;
            seen.add(blockId);
        }
        List<ResourceStack> stacks = new ArrayList<>();
        for (String blockId : seen) {
            stacks.add(new ResourceStack(new ResourceId(blockId), 64));
        }
        return stacks;
    }

    private static int computeUniqueBlockTypes(BuildingConfig config) {
        Set<String> seen = new LinkedHashSet<>();
        for (BlockOffset offset : config.pattern()) {
            String blockId = config.blockMapping().get(offset.toKey());
            if (blockId == null || "minecraft:air".equals(blockId)) continue;
            seen.add(blockId);
        }
        return seen.size();
    }
}
