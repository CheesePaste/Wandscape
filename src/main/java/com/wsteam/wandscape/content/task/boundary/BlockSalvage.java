package com.wsteam.wandscape.content.task.boundary;

import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.content.npc.worker.ColonyWorker;
import com.wsteam.wandscape.content.task.component.ColonyMember;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.BlockType;
import com.wsteam.wandscape.content.task.types.GridPos;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.util.ItemKey;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * 拆除 / 清空时的掉落物回收：把要覆盖掉的方块按原版掉落表算出来，直接入殖民地仓库
 * （不走飞行动画——批量拆除会刷爆客户端的运输实体）。
 *
 * <p>从 {@link AsyncTransformExecutor} 里抽出来，供两个执行器共用：
 * 逐格 {@code TransformOp}（拆除 / 覆盖）与 {@link ClearBoxExecutor}（整箱清空的批量清格）。
 * 抽出来时逻辑一字未改。
 */
public final class BlockSalvage {

    private static final String TAG = "BlockSalvage";

    private BlockSalvage() {}

    /**
     * 把 {@code pos} 上即将被 {@code to} 覆盖的方块回收进殖民地仓库。
     * 目标格是空气且原来是别的方块时，等价于拆除。
     *
     * <p><b>调用方必须在同一次调用里紧跟着写入新方块</b>（中间不能让出 tick）：两个并发清场
     * 游标扫同一格时，靠「读到非空气 → 立刻置空气」这一步的连续性保证不会重复回收。
     */
    public static void salvage(World world, long npcId, GridPos pos, BlockType to) {
        ColonyWorker worker = EntityComponentBridge.INSTANCE.getWorker(npcId);
        Level level = worker != null ? worker.entity().level() : null;
        if (level == null && ServerLifecycleHooks.getCurrentServer() != null) {
            level = ServerLifecycleHooks.getCurrentServer().overworld();
        }
        if (!(level instanceof ServerLevel sl)) return;

        BlockPos bp = new BlockPos(pos.x(), pos.y(), pos.z());
        BlockState oldState = sl.getBlockState(bp);
        if (!isSalvageable(oldState, sl, bp, to)) return;

        BlockEntity be = sl.getBlockEntity(bp);
        List<ItemStack> drops = Block.getDrops(oldState, sl, bp, be,
                worker != null ? worker.entity() : null, ItemStack.EMPTY);
        if (drops.isEmpty()) {
            if (!oldState.canBeReplaced()) {
                Item item = oldState.getBlock().asItem();
                if (item != Items.AIR) {
                    drops = List.of(new ItemStack(item, 1));
                }
            }
        }
        if (drops.isEmpty()) return;

        UUID colonyId = resolveColonyId(worker, world, bp);
        ColonyItemBank bank = ColonyItemBank.get(sl);
        if (bank == null || colonyId == null) return;

        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            String itemId = BuiltInRegistries.ITEM.getKey(drop.getItem()).toString();
            ItemKey key = ItemKey.of(itemId, null);
            int count = drop.getCount();
            if (!bank.tryAdd(colonyId, key, count)) {
                // 满仓：不入仓库也不吞物品——掉落物落回拆除点（等价箱满溢出，损失为零，
                // 也不让拆迁/平地被满仓卡死）。见 ColonyItemBank 容量机制。
                dropSalvageOnGround(sl, bp, drop);
                continue;
            }
        }
    }

    /** 满仓时把拆迁/回收掉落物生成在拆除点上（等价箱满溢出，不丢物品、不阻塞平地）。 */
    private static void dropSalvageOnGround(ServerLevel level, BlockPos pos, ItemStack drop) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;
        net.minecraft.world.entity.item.ItemEntity entity =
                new net.minecraft.world.entity.item.ItemEntity(level, x, y, z, drop.copy());
        entity.setDeltaMovement(0, 0.1, 0);
        entity.setPickUpDelay(10);
        level.addFreshEntity(entity);
    }

    private static boolean isSalvageable(BlockState oldState, ServerLevel sl, BlockPos bp, BlockType toType) {
        if (oldState.isAir()) return false;
        if (!oldState.getFluidState().isEmpty()) return false;
        if (oldState.getDestroySpeed(sl, bp) < 0) return false;
        if (oldState.is(net.minecraft.tags.BlockTags.FIRE)) return false;
        if (toType != null && !toType.id().isEmpty() && !"minecraft:air".equals(toType.id())) {
            String pureOld = BuiltInRegistries.BLOCK.getKey(oldState.getBlock()).toString();
            String pureTo = toType.stripBlockStateSuffix().id();
            return !pureOld.equals(pureTo); // same block type, no replacement salvage needed
        }
        return true;
    }

    private static UUID resolveColonyId(@Nullable ColonyWorker worker, World world, BlockPos bp) {
        if (worker != null) {
            Long ecsId = EntityComponentBridge.INSTANCE.getEcsId(worker.workerId());
            var member = ecsId != null ? world.get(ecsId, ColonyMember.class) : null;
            if (member != null && member.colonyId() != null) return member.colonyId();
            if (worker.colonyId() != null) return worker.colonyId();
        }
        var colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi != null) {
            UUID cid = colonyApi.getColonyId(bp);
            if (cid != null) return cid;
        }
        return new UUID(0, 0);
    }
}
