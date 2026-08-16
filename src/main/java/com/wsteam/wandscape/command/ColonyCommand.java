package com.wsteam.wandscape.command;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import com.wsteam.wandscape.engine.service.ParticleService;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.event.ColonyCreatedEvent;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.shared.registry.WandscapeConstants;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.ui.I18n;

/**
 * Colony lifecycle commands.
 *
 * <p>Usage:
 * <pre>
 *   /wandscape colony create <name> [x y z]
 *   /wandscape colony destroy          — destroy the colony nearest the player
 *   /wandscape colony level <n>        — set the colony level to n (debug/test, e.g. 100)
 * </pre>
 *
 * <p>Creates a colony with a new UUID, spawns 3 initial builder NPCs, and fills
 * the NPCs' ECS {@link Inventory} with town_hall building materials.
 */
public final class ColonyCommand {

    private static final String TAG = "ColonyCommand";
    private static final int WAND_RANGE = 8;
    /** 新建小镇时生成的初始 builder NPC 数量。 */
    private static final int INITIAL_BUILDER_COUNT = 3;

    /**
     * Outcome of a colony-creation attempt: whether it succeeded and the
     * message to surface to the player (shared by the command and the
     * town-hall naming flow).
     */
    public record ColonyCreateOutcome(boolean success, Component message) {
        public static ColonyCreateOutcome success(Component message) {
            return new ColonyCreateOutcome(true, message);
        }

        public static ColonyCreateOutcome failure(Component message) {
            return new ColonyCreateOutcome(false, message);
        }
    }

    private ColonyCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("colony")
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ColonyCommand::createColony)))
                .then(Commands.literal("destroy")
                        .executes(ColonyCommand::destroyColony))
                .then(Commands.literal("level")
                        .then(Commands.argument("level", IntegerArgumentType.integer(1))
                                .executes(ColonyCommand::setColonyLevel)))
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

        UUID founder = sender instanceof net.minecraft.world.entity.player.Player p
                ? p.getUUID() : null;
        ColonyCreateOutcome outcome = createColonyAt(level, origin, name, founder);
        if (outcome == null || !outcome.success()) {
            ctx.getSource().sendFailure(outcome != null
                    ? outcome.message()
                    : Component.literal("[Wandscape] Colony creation failed"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> outcome.message(), true);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Core colony-creation routine shared by {@code /wandscape colony create}
     * and the town-hall naming flow ({@code ColonyCreateRequestPacket}).
     *
     * <p>Creates the colony, spawns the initial builder NPCs, seeds their
     * inventories and fires {@link ColonyCreatedEvent}. Returns a
     * {@link ColonyCreateOutcome} with a player-facing message; on failure the
     * outcome carries {@code success=false} and the failure message.
     */
    public static ColonyCreateOutcome createColonyAt(ServerLevel level, BlockPos origin, String name,
                                        @Nullable UUID founder) {
        // 一人一小镇：玩家已拥有小镇时拒绝创建第二个（V 面板/市政厅命名/命令共用此入口）
        if (founder != null && ColonyApiImpl.get().getColonyByFounder(founder) != null) {
            return ColonyCreateOutcome.failure(I18n.name(
                    "message.wandscape.command.colony_already_owned",
                    "[Wandscape] Failed: 你已拥有小镇，不能创建第二个。"));
        }

        // ── Step 1: load config ─────────────────────────────────────────────
        BuildingConfig townHallConfig = BuildingConfigLoader.getInstance()
                .getByCategory(WandscapeConstants.BUILDING_CATEGORY_GOVERNMENT);
        if (townHallConfig == null) {
            return ColonyCreateOutcome.failure(Component.literal(
                    "[Wandscape] no government building config found "
                    + "(need a building JSON with category=government)"));
        }

        // ── Step 2: create colonyId ─────────────────────────────────────────
        ColonyApi colonyApi = ColonyApiImpl.get();
        UUID colonyId = colonyApi.createColony(origin, founder);
        var levelMgr = WandscapeEngine.getColonyLevelManager();
        if (levelMgr != null && name != null && !name.isBlank()) {
            levelMgr.setColonyName(colonyId, name);
        }
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
        List<ResourceStack> starterItems = computeStarterInventory(townHallConfig);
        List<BlockPos> spawnPositions = findBuilderSpawns(level, origin, INITIAL_BUILDER_COUNT);
        List<WandscapeNpc> spawnedNpcs = new ArrayList<>();
        for (BlockPos spawnPos : spawnPositions) {
            var npc = Wandscape.WANDSCAPE_NPC.get().spawn(level, spawnPos, MobSpawnType.COMMAND);
            if (npc == null) {
                return ColonyCreateOutcome.failure(Component.literal(
                        "[Wandscape] Failed to spawn NPC at " + spawnPos));
            }
            npc.setPersistenceRequired();
            npc.colonyId = colonyId;

            // ── Step 5: fix ECS state + fill inventory ─────────────────────
            // spawn() already ran onNpcJoinWorld(). If the NPC joined ECS
            // immediately (engine was bootstrapped), its ColonyMember has
            // PLACEHOLDER_COLONY and its Inventory is empty. Fix both now.
            // For the deferred-join case (engine not yet bootstrapped), the
            // scheduleInventoryFill fallback handles it when flushDeferredJoins
            // runs — npc.colonyId is already set by then.
            fixEcsAfterSpawn(npc, colonyId, starterItems);
            EntityComponentBridge.INSTANCE.scheduleInventoryFill(
                    npc.getUUID(), colonyId, starterItems);

            // Seed builder_wand into NPC's MC inventory so WandEquip can
            // shortfill it without needing a storage building (cold-start
            // bootstrap). All initial NPCs are universal workers.
            seedBuilderWand(npc);
            // 早期法师太脆容易死：给初始法师赠送铁套（仅护甲数值生效，外观不渲染）
            equipStarterArmor(npc);
            spawnedNpcs.add(npc);
        }
        Log.info(TAG, "[Colony] Spawned {} builder NPCs at {} for colony {}",
                spawnedNpcs.size(), spawnPositions.get(0),
                colonyId.toString().substring(0, 8));

        // ── Step 6: fire event ──────────────────────────────────────────────
        NeoForge.EVENT_BUS.post(new ColonyCreatedEvent(colonyId, origin));

        // ── 创建庆祝：市政厅包围盒一圈烟花 ──
        var townHall = WandscapeApis.getBuildingApi().getBuildingAt(origin);
        if (townHall != null && townHall.getBounds() != null) {
            ParticleService.celebrateRing(level, townHall.getBounds(), 3);
        } else {
            ParticleService.celebrateAt(level, origin.getCenter(), 3);
        }

        // ── Step 7: reply ───────────────────────────────────────────────────
        int materialTypes = computeUniqueBlockTypes(townHallConfig);
        return ColonyCreateOutcome.success(Component.literal(
                "[Wandscape] Colony '" + name + "' created!\n" +
                "  ID: " + colonyId.toString().substring(0, 8) + "\n" +
                "  TownHall: " + origin.toShortString() + "\n" +
                "  NPC: " + INITIAL_BUILDER_COUNT + " builders at "
                        + spawnPositions.get(0).toShortString() + "\n" +
                "  Inventory: " + starterItems.size() + " stacks (" + materialTypes + " types)\n" +
                "  Radius: 256 blocks\n" +
                "\nTip: use /wandscape fill " + townHallConfig.id()
                        + " 1 1 to queue construction"));
    }

    /**
     * Ensure a colony exists near {@code origin}; if none is within range, create
     * one at {@code origin} and persist its display name.
     *
     * <p>Used to auto-found the colony when the player opens the Wandscape panel
     * (V key) on a world with no colony yet — BEFORE any building is placed.
     * The per-colony first-free ({@code first_free}) claim happens at placement
     * time, so the colony must already exist for the first building (the town
     * hall) to build for free.
     *
     * @return the existing or newly created colonyId, or null if creation failed
     */
    @Nullable
    public static UUID ensureColonyNear(ServerLevel level, BlockPos origin,
                                        String name, @Nullable UUID founder) {
        ColonyApi colonyApi = ColonyApiImpl.get();
        // 一人一小镇：玩家已有小镇时返回它（无论多远），绝不新建第二个
        if (founder != null) {
            UUID owned = colonyApi.getColonyByFounder(founder);
            if (owned != null) return owned;
        }
        UUID existing = colonyApi.getColonyId(origin);
        if (existing != null) return existing;

        ColonyCreateOutcome outcome = createColonyAt(level, origin, name, founder);
        if (outcome == null || !outcome.success()) {
            Log.warn(TAG, "[Colony] Auto-create failed at {}: {}", origin,
                    outcome != null ? outcome.message().getString() : "null");
            return null;
        }

        UUID created = colonyApi.getColonyId(origin);
        if (created != null) {
            var levelMgr = com.wsteam.wandscape.engine.WandscapeEngine.getColonyLevelManager();
            if (levelMgr != null) {
                levelMgr.setColonyName(created, name);
            }
        }
        return created;
    }

    /** Destroy the colony nearest the executing player (within 256 blocks). */
    private static int destroyColony(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.server.level.ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("[Wandscape] Player-only command"));
            return 0;
        }

        ColonyApi colonyApi = ColonyApiImpl.get();
        UUID colonyId = colonyApi.getColonyByFounder(player.getUUID());
        if (colonyId == null) {
            colonyId = colonyApi.getColonyId(player.blockPosition());
        }
        if (colonyId == null) {
            ctx.getSource().sendFailure(I18n.name(
                    "message.wandscape.command.colony_none_found",
                    "[Wandscape] 你当前没有所属的小镇，附近 256 格内也没有发现小镇。"));
            return 0;
        }

        final UUID targetColonyId = colonyId;
        colonyApi.deleteColony(targetColonyId);
        ctx.getSource().sendSuccess(() -> I18n.name(
                "message.wandscape.command.colony_destroyed",
                "[Wandscape] 成功销毁小镇 %s",
                targetColonyId.toString().substring(0, 8)), true);
        return Command.SINGLE_SUCCESS;
    }

    /** 设置小镇等级到指定值（调试/测试用，如 /wandscape colony level 100）。 */
    private static int setColonyLevel(CommandContext<CommandSourceStack> ctx) {
        int level = IntegerArgumentType.getInteger(ctx, "level");
        net.minecraft.server.level.ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("[Wandscape] Player-only command"));
            return 0;
        }

        ColonyApi colonyApi = ColonyApiImpl.get();
        UUID colonyId = colonyApi.getColonyId(player.blockPosition());
        if (colonyId == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[Wandscape] No colony within 256 blocks of your position"));
            return 0;
        }

        var levelMgr = WandscapeEngine.getColonyLevelManager();
        if (levelMgr == null) {
            ctx.getSource().sendFailure(Component.literal("[Wandscape] Level manager not ready"));
            return 0;
        }
        levelMgr.setLevel(colonyId, level);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[Wandscape] Colony " + colonyId.toString().substring(0, 8)
                        + " level → " + level), true);
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

    /**
     * Find {@code count} distinct ground positions around {@code origin} so the
     * initial NPCs do not stack on the same block.
     */
    private static List<BlockPos> findBuilderSpawns(ServerLevel level, BlockPos origin, int count) {
        BlockPos[] bases = {
                origin, origin.east(), origin.west(),
                origin.north(), origin.south(), origin.east(2), origin.west(2)
        };
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos base : bases) {
            if (result.size() >= count) break;
            BlockPos ground = findGroundAbove(level, base);
            if (!result.contains(ground)) {
                result.add(ground);
            }
        }
        // Fallback: nudge upward if terrain produced fewer distinct spots.
        BlockPos last = result.isEmpty() ? origin.above(2) : result.get(result.size() - 1);
        while (result.size() < count) {
            last = last.above(1);
            result.add(last);
        }
        return result;
    }

    /** Seed a builder_wand into the NPC's MC inventory (cold-start bootstrap). */
    private static void seedBuilderWand(WandscapeNpc npc) {
        var wandPreset = Wandscape.WAND_PRESET_LOADER.getPreset("builder_wand");
        if (wandPreset == null) return;
        var wandRegItem = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.tryParse("wandscape:wand"));
        if (wandRegItem == null) return;
        var wandStack = new net.minecraft.world.item.ItemStack(wandRegItem);
        wandStack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(wandPreset.nbt().copy()));
        npc.inventory.addItem(wandStack);
        Log.info(TAG, "[Colony] Seeded builder_wand into NPC inventory");
    }

    /**
     * 给初始法师配一套铁甲（4 件塞 {@code armorInventory}，仅护甲数值生效、外观不渲染）。
     * 早期法师太脆容易死，赠送铁套提升开局生存。同步 ECS 使护甲值立即计入伤害减免；
     * 延迟入 ECS 的场景由 {@code onNpcJoinWorld → syncArmorAttributes} 兜底。
     */
    private static void equipStarterArmor(WandscapeNpc npc) {
        npc.setArmorItem(0, new ItemStack(Items.IRON_HELMET));
        npc.setArmorItem(1, new ItemStack(Items.IRON_CHESTPLATE));
        npc.setArmorItem(2, new ItemStack(Items.IRON_LEGGINGS));
        npc.setArmorItem(3, new ItemStack(Items.IRON_BOOTS));
        npc.syncArmorAttributes();
        Log.info(TAG, "[Colony] Equipped starter iron armor on NPC {}",
                npc.getUUID().toString().substring(0, 8));
    }

    /** Compute exact material stacks for the NPC's inventory to construct the Town Hall. */
    private static List<ResourceStack> computeStarterInventory(BuildingConfig config) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (BlockOffset offset : config.pattern()) {
            String blockId = config.blockMapping().get(offset.toKey());
            if (blockId == null || "minecraft:air".equals(blockId)) continue;
            String cleanId = blockId.replaceAll("\\[.*?\\]", "").trim();
            counts.put(cleanId, counts.getOrDefault(cleanId, 0) + 1);
        }
        List<ResourceStack> stacks = new ArrayList<>();
        for (var entry : counts.entrySet()) {
            // Give exact required quantity (at least 64 per type to be generous)
            int qty = Math.max(64, entry.getValue());
            stacks.add(new ResourceStack(new ResourceId(entry.getKey()), qty));
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
