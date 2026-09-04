package com.wsteam.wandscape.content.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.content.task.component.ColonyMember;
import com.wsteam.wandscape.content.task.component.ColonyMetadata;
import com.wsteam.wandscape.content.task.component.NpcInventory;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.GridPos;
import com.wsteam.wandscape.foundation.service.ParticleService;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.api.ColonyApi;
import com.wsteam.wandscape.api.ColonyStatusApi;
import com.wsteam.wandscape.content.colony.data.ColonyStatusSnapshot;
import com.wsteam.wandscape.content.colony.event.ColonyCreatedEvent;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;
import com.wsteam.wandscape.foundation.ui.I18n;
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

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 * the NPCs' ECS {@link NpcInventory} with town_hall building materials.
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
                .then(Commands.literal("status")
                        .executes(ColonyCommand::status))
                .then(Commands.literal("list")
                        .executes(ColonyCommand::list))
                .then(Commands.literal("create")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ColonyCommand::createColony)))
                .then(Commands.literal("destroy")
                        .requires(src -> src.hasPermission(2))
                        .executes(ColonyCommand::destroyColony))
                .then(Commands.literal("level")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("level", IntegerArgumentType.integer(1))
                                .executes(ColonyCommand::setColonyLevel)))
                .then(Commands.literal("exp")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                .executes(ColonyCommand::grantExp)))
                .then(Commands.literal("name")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ColonyCommand::rename)))
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
        if (founder != null && WandscapeApis.getColonyApi().getColonyByFounder(founder) != null) {
            return ColonyCreateOutcome.failure(I18n.name(
                    "message.wandscape.command.colony_already_owned",
                    "[Wandscape] Failed: 你已拥有小镇，不能创建第二个。"));
        }
        // ── 可选：小镇隔离距离（当前注释保留，未启用；镇可建任意近，靠归属判定隔离）──
        // 若启用：新镇 origin 须与所有已有小镇 origin 保持隔离距离（两倍工作半径），
        // 保证各镇工作圈永不交叠，彻底杜绝「建筑/道路/仓库误归属到邻近别人的小镇」这类跨镇串扰。
        //
        // int minSep = 512; // 2 × ColonyApiImpl.MAX_COLONY_RANGE(256)
        // var csd = ColonySavedData.getOrCreate(level);
        // for (UUID cid : WandscapeApis.getColonyApi().getAllColonyIds()) {
        //     BlockPos otherOrigin = csd.getOrigin(cid);
        //     if (otherOrigin != null && otherOrigin.distSqr(origin) < (long) minSep * minSep) {
        //         return ColonyCreateOutcome.failure(I18n.name(
        //                 "message.wandscape.command.colony_too_close",
        //                 "[Wandscape] Failed: 与已有小镇距离过近，请在至少 " + minSep
        //                         + " 格外选址（两镇工作圈不重叠）。"));
        //     }
        // }

        // ── Step 1: load config ─────────────────────────────────────────────
        BuildingConfig townHallConfig = BuildingConfigLoader.getInstance()
                .getByCategory(WandscapeConstants.BUILDING_CATEGORY_GOVERNMENT);
        if (townHallConfig == null) {
            return ColonyCreateOutcome.failure(Component.literal(
                    "[Wandscape] no government building config found "
                    + "(need a building JSON with category=government)"));
        }

        // ── Step 2: create colonyId ─────────────────────────────────────────
        ColonyApi colonyApi = WandscapeApis.getColonyApi();
        UUID colonyId = colonyApi.createColony(origin, founder);
        if (name != null && !name.isBlank()) {
            colonyApi.setColonyName(colonyId, name);
        }
        Log.info(TAG, "[Colony] Creating colony '{}' id={} at {}", name,
                colonyId.toString().substring(0, 8), origin);

        // ── Step 3: create ECS colony entity ────────────────────────────────
        World world = World.getActive();
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

            // ── Step 5: fix ECS state (ColonyMember) ────────────────────────
            // spawn() already ran onNpcJoinWorld(). If the NPC joined ECS
            // immediately (engine was bootstrapped), its ColonyMember has
            // PLACEHOLDER_COLONY. Fix it now.
            fixEcsAfterSpawn(npc, colonyId);

            // Seed carpenter_wand into NPC's MC inventory so WandEquip can
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
        return ColonyCreateOutcome.success(Component.literal(
                "[Wandscape] Colony '" + name + "' created!\n" +
                "  ID: " + colonyId.toString().substring(0, 8) + "\n" +
                "  TownHall: " + origin.toShortString() + "\n" +
                "  NPC: " + INITIAL_BUILDER_COUNT + " builders at "
                        + spawnPositions.get(0).toShortString() + "\n" +
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
        ColonyApi colonyApi = WandscapeApis.getColonyApi();
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
            colonyApi.setColonyName(created, name);
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

        ColonyApi colonyApi = WandscapeApis.getColonyApi();
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

        ColonyApi colonyApi = WandscapeApis.getColonyApi();
        UUID colonyId = colonyApi.getColonyId(player.blockPosition());
        if (colonyId == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "[Wandscape] No colony within 256 blocks of your position"));
            return 0;
        }

        if (!colonyApi.setColonyLevel(colonyId, level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "[Wandscape] 设置等级失败：越界（有效 1.." + colonyApi.getMaxLevel()
                            + "）或小镇不存在"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[Wandscape] Colony " + colonyId.toString().substring(0, 8)
                        + " level → " + level), true);
        return Command.SINGLE_SUCCESS;
    }

    /** 小镇状态概览（只读，玩家无需权限）。 */
    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID colonyId = CommandUtil.resolveColony(src);
        if (colonyId == null) {
            src.sendFailure(Component.literal(
                    "[Wandscape] 未检测到小镇：请在小镇范围内使用，或先创建小镇"));
            return 0;
        }

        ColonyStatusApi statusApi = WandscapeApis.getColonyStatusApiSilently();
        if (statusApi == null) {
            src.sendFailure(Component.literal("[Wandscape] 小镇状态系统未就绪"));
            return 0;
        }
        ColonyStatusSnapshot snap = statusApi.getSnapshotSafe(colonyId);
        String cid = CommandUtil.shortId(colonyId);
        String name = snap.colonyName() != null && !snap.colonyName().isEmpty()
                ? snap.colonyName() : "(未命名)";
        src.sendSuccess(() -> Component.literal(
                "[Wandscape] 小镇 " + name + " (" + cid + ") Lv." + snap.colonyLevel()
                        + " · 经验 " + snap.colonyExperience()), false);
        src.sendSuccess(() -> Component.literal(
                "  三值: 舒适 " + snap.comfort() + " · 魔法 " + snap.magic()
                        + " · 奇观 " + snap.wonder()), false);
        src.sendSuccess(() -> Component.literal(
                "  游客 " + snap.touristCount() + "（过夜 " + snap.overnightStayerCount()
                        + "） · 法师 " + snap.npcTotalCount()
                        + "（空闲 " + snap.npcIdleCount() + "） · 在建 " + snap.underConstructionCount()), false);
        src.sendSuccess(() -> Component.literal(
                "  元素: 土 " + snap.earthAmount() + " 木 " + snap.woodAmount()
                        + " 水 " + snap.waterAmount() + " 火 " + snap.fireAmount()
                        + " 金 " + snap.metalAmount() + " 风 " + snap.windAmount()
                        + " 暗 " + snap.darkAmount()), false);
        return Command.SINGLE_SUCCESS;
    }

    /** 列出全部已注册小镇（只读，玩家无需权限）。 */
    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi == null) {
            src.sendFailure(Component.literal("[Wandscape] 小镇系统未就绪"));
            return 0;
        }
        var ids = colonyApi.getAllColonyIds();
        src.sendSuccess(() -> Component.literal(
                "[Wandscape] 已注册小镇（共 " + ids.size() + " 个）："), false);
        if (ids.isEmpty()) {
            src.sendSuccess(() -> Component.literal("  （无小镇）"), false);
            return Command.SINGLE_SUCCESS;
        }
        for (UUID id : ids) {
            final UUID fid = id;
            String nm = colonyApi.getColonyName(fid);
            if (nm == null) nm = "";
            final String name = nm.isEmpty() ? "(未命名)" : nm;
            final int lv = colonyApi.getColonyLevel(fid);
            src.sendSuccess(() -> Component.literal(
                    String.format("  %s [%s] Lv.%d", name, fid.toString().substring(0, 8), lv)), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 授予小镇经验（op-2；可能触发升级）。 */
    private static int grantExp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        UUID colonyId = resolvePlayerColony(ctx, "未找到可授经验的小镇");
        if (colonyId == null) return 0;
        ColonyApi colonyApi = WandscapeApis.getColonyApi();
        colonyApi.grantExperience(colonyId, amount);
        src.sendSuccess(() -> Component.literal(
                "[Wandscape] 已授予小镇 " + CommandUtil.shortId(colonyId) + " " + amount
                        + " 经验（当前 Lv." + colonyApi.getColonyLevel(colonyId)
                        + " / " + colonyApi.getColonyExp(colonyId) + "）"),
                true);
        return Command.SINGLE_SUCCESS;
    }

    /** 重命名小镇（op-2）。 */
    private static int rename(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name").trim();
        UUID colonyId = resolvePlayerColony(ctx, "未找到可重命名的小镇");
        if (colonyId == null) return 0;
        WandscapeApis.getColonyApi().setColonyName(colonyId, name);
        src.sendSuccess(() -> Component.literal(
                "[Wandscape] 已重命名小镇 " + CommandUtil.shortId(colonyId) + " → " + name), true);
        return Command.SINGLE_SUCCESS;
    }

    /** 解析执行者所属小镇（创始人优先，其次位置），玩家-only；失败输出给定提示并返回 null。 */
    @javax.annotation.Nullable
    private static UUID resolvePlayerColony(CommandContext<CommandSourceStack> ctx, String failMsg) {
        CommandSourceStack src = ctx.getSource();
        var player = src.getPlayer();
        ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
        if (player != null && colonyApi != null) {
            UUID owned = colonyApi.getColonyByFounder(player.getUUID());
            if (owned != null) return owned;
        }
        UUID near = colonyApi != null
                ? colonyApi.getColonyId(BlockPos.containing(src.getPosition())) : null;
        if (near != null) return near;
        src.sendFailure(Component.literal("[Wandscape] " + failMsg));
        return null;
    }

    /**
     * Fix ECS {@code ColonyMember} and {@code NpcInventory} after spawn.
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
    private static void fixEcsAfterSpawn(WandscapeNpc npc, UUID colonyId) {
        World ecsWorld = World.getActive();
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

    /** Seed a builder wand (Lv1 work-type) into the NPC's MC inventory (cold-start bootstrap). */
    private static void seedBuilderWand(WandscapeNpc npc) {
        var wandPreset = Wandscape.WAND_PRESET_LOADER.getPreset("carpenter_wand");
        if (wandPreset == null) return;
        var wandRegItem = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.tryParse("wandscape:wand"));
        if (wandRegItem == null) return;
        var wandStack = new net.minecraft.world.item.ItemStack(wandRegItem);
        wandStack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(wandPreset.nbt().copy()));
        npc.inventory.addItem(wandStack);
        Log.info(TAG, "[Colony] Seeded carpenter_wand into NPC inventory");
    }

    /**
     * 给初始法师配一套铁甲（写 vanilla 装备槽——护甲值/韧性/附魔由原版每 tick 装备结算生效，
     * 其它模组可见；巫师袍外观不受影响，渲染器无盔甲层）。早期法师太脆容易死，赠送铁套提升
     * 开局生存。铁魔法属性桥由 {@code onNpcJoinWorld → syncIronArmorAttributes} 兜底。
     */
    private static void equipStarterArmor(WandscapeNpc npc) {
        npc.setItemSlot(WandscapeNpc.ARMOR_VANILLA_SLOTS[0], new ItemStack(Items.IRON_HELMET));
        npc.setItemSlot(WandscapeNpc.ARMOR_VANILLA_SLOTS[1], new ItemStack(Items.IRON_CHESTPLATE));
        npc.setItemSlot(WandscapeNpc.ARMOR_VANILLA_SLOTS[2], new ItemStack(Items.IRON_LEGGINGS));
        npc.setItemSlot(WandscapeNpc.ARMOR_VANILLA_SLOTS[3], new ItemStack(Items.IRON_BOOTS));
        npc.syncIronArmorAttributes();
        Log.info(TAG, "[Colony] Equipped starter iron armor on NPC {}",
                npc.getUUID().toString().substring(0, 8));
    }
}
