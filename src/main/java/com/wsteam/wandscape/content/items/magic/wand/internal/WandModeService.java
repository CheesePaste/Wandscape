package com.wsteam.wandscape.content.items.magic.wand.internal;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.colony.ownership.ColonyOwnership;
import com.wsteam.wandscape.content.items.magic.wand.item.WandMode;
import com.wsteam.wandscape.content.items.scepter.internal.ScepterService;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.content.production.ProductionRecipeManager;
import com.wsteam.wandscape.content.production.data.SynthesizeRecipe;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.UUID;

/**
 * 玩家手持法杖时的服务端业务：把当前模式落成一条殖民地命令 + 玩家上屏反馈。
 *
 * <p>四种模式共一个入口，由调用方（{@link com.wsteam.wandscape.content.items.magic.wand.item.WandItem}）
 * 按 {@link WandMode#affectsBlocks()}/{@link WandMode#affectsCreatures()} 分流到位：
 * 生物落点走 {@link #onInteractNpc}/{@link #onInteractCreature}，方块落点走 {@link #onUseBlock}。
 *
 * <p>庇护/敌对不在此重写——它们与权杖同源，直接复用 {@link ScepterService} 的同一套校验与
 * 标记写入（标记落 {@code ScepterMarksSavedData}，长期持久化），本类只做模式分发。集合/鉴定是
 * 法杖新增的一次性动作，不存任何状态：集合借 ECS 的 {@code movementOps.navigateTo} 下移动指令，
 * 鉴定就地解锁配方。
 *
 * <p>所有分支都要求玩家有自己的小镇（{@link ColonyOwnership#ownColony}）：没有小镇就没有可指挥的
 * 法师，也没有可解锁配方的殖民地账本。
 */
public final class WandModeService {

    private static final String TAG = "WandModeService";

    /** 集合模式的生效半径（格）：只指挥玩家周围这个范围内的本镇法师。 */
    public static final double COMMAND_RADIUS = 32.0;

    private WandModeService() {}

    /** 玩家右键本镇法师（{@code NpcInteractHook} 转交）：四种模式都经此，方块模式给一句提示。 */
    public static void onInteractNpc(ServerPlayer player, Mob mage, WandMode mode) {
        if (!(mage instanceof WandscapeNpc npc)) return;
        if (mode.affectsBlocks()) {
            // 集合/鉴定以方块为落点；对法师右键不成立，明说一句，别静默吞掉这次右键
            ok(player, "message.wandscape.wand.block_only", Component.translatable(mode.langKey()));
            return;
        }
        onInteractCreature(player, npc, mode);
    }

    /** 玩家右键非法师生物（EntityInteract 转交）：仅庇护/敌对适用，其余模式调用方已放行原版。 */
    public static void onInteractCreature(ServerPlayer player, LivingEntity target, WandMode mode) {
        switch (mode) {
            case SHELTER -> ScepterService.toggleShelter(player, target);
            case HOSTILE -> ScepterService.toggleHostile(player, target);
            default -> { /* 方块模式：不接生物 */ }
        }
    }

    /**
     * 玩家右键方块：按当前模式对点击位置下命令。
     *
     * <p>调用方已按 {@link WandMode#affectsBlocks()} 判定接管与否，本方法不再回绝模式。
     *
     * @param clickedPos 被右键的方块
     * @param face       被右键的那一面（集合模式的落点取它外侧一格，即玩家指着的位置）
     */
    public static void onUseBlock(ServerPlayer player, Level level, BlockPos clickedPos,
                                  Direction face, WandMode mode) {
        switch (mode) {
            case GATHER -> gather(player, level, clickedPos.relative(face));
            case IDENTIFY -> identify(player, level, clickedPos);
            default -> { /* 生物模式：调用方已放行方块交互 */ }
        }
    }

    // ── 集合：把玩家周围的本镇法师一次性调去目标点 ──

    private static void gather(ServerPlayer player, Level level, BlockPos target) {
        UUID colonyId = ColonyOwnership.ownColony(player);
        if (colonyId == null) {
            fail(player, "message.wandscape.wand.no_colony");
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) return;

        World world = World.getActive();
        if (world == null || world.movementOps == null) {
            fail(player, "message.wandscape.wand.command_unavailable");
            Log.warn(TAG, "Gather order dropped — ECS movement channel unavailable (player {})",
                    shortId(player.getUUID()));
            return;
        }

        List<WandscapeNpc> mages = serverLevel.getEntitiesOfClass(WandscapeNpc.class,
                player.getBoundingBox().inflate(COMMAND_RADIUS),
                npc -> npc.isColonyNpc() && colonyId.equals(npc.colonyId));

        int ordered = 0;
        for (WandscapeNpc mage : mages) {
            long ecsId = ecsIdOf(mage);
            if (ecsId <= 0) continue; // 还没进 ECS（加载中/未注册）→ 跳过，不算已下令
            world.movementOps.navigateTo(ecsId, target.getX(), target.getY(), target.getZ());
            ordered++;
        }

        if (ordered == 0) {
            fail(player, "message.wandscape.wand.gather_none");
        } else {
            ok(player, "message.wandscape.wand.gather_ok", ordered);
        }
        Log.info(TAG, "Player {} ordered {} mage(s) to {} (colony {}, reachable mages {})",
                shortId(player.getUUID()), ordered, target.toShortString(),
                shortId(colonyId), mages.size());
    }

    /** ECS 实体 id；实体字段未就绪时回查桥接表，都没有则 -1。 */
    private static long ecsIdOf(WandscapeNpc npc) {
        if (npc.ecsEntityId > 0) return npc.ecsEntityId;
        Long mapped = EntityComponentBridge.INSTANCE.getEcsId(npc.getUUID());
        if (mapped != null && mapped > 0) {
            npc.ecsEntityId = mapped;
            return mapped;
        }
        return -1L;
    }

    // ── 鉴定：解锁被右键方块对应的合成配方 ──

    private static void identify(ServerPlayer player, Level level, BlockPos pos) {
        UUID colonyId = ColonyOwnership.ownColony(player);
        if (colonyId == null) {
            fail(player, "message.wandscape.wand.no_colony");
            return;
        }
        var loader = Wandscape.PRODUCTION_RECIPE_LOADER;
        if (loader == null) {
            fail(player, "message.wandscape.wand.command_unavailable");
            return;
        }

        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        Item item = block.asItem();
        Component blockName = block.getName();

        // 配方 id 是「生产出来的那个东西」的 id：方块先用它的物品 id 查（大多数方块二者同名，
        // 城墙火把这类不一致的也以物品为准），查不到再退回方块 id（无对应物品的方块）。
        SynthesizeRecipe recipe = null;
        if (item != Items.AIR) {
            recipe = loader.getSynthesizeRecipe(BuiltInRegistries.ITEM.getKey(item).toString());
        }
        if (recipe == null) {
            recipe = loader.getSynthesizeRecipe(BuiltInRegistries.BLOCK.getKey(block).toString());
        }
        if (recipe == null) {
            fail(player, "message.wandscape.wand.identify_no_recipe", blockName);
            return;
        }

        if (ProductionRecipeManager.isSynthesizeUnlocked(colonyId, recipe.id())) {
            ok(player, "message.wandscape.wand.identify_known", blockName);
            return;
        }

        ProductionRecipeManager.unlockSynthesize(colonyId, recipe.id(),
                ProductionRecipeManager.SOURCE_WAND_IDENTIFY);
        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7f, 1.2f);
        ok(player, "message.wandscape.wand.identify_unlocked", blockName);
        Log.info(TAG, "Player {} identified {} at {} → unlocked recipe '{}' for colony {}",
                shortId(player.getUUID()), blockName.getString(), pos.toShortString(),
                recipe.id(), shortId(colonyId));
    }

    // ── 反馈（Action Bar；与 ScepterService 同口径）──

    private static void ok(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
    }

    private static void fail(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
