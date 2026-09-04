package com.wsteam.wandscape.content.colony.guard;

import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.colony.ownership.ColonyOwnership;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 领地方块保护（完全平行隔离的原版方块层）。
 *
 * <p>规则：某方块若属于某座「殖民地建筑」（位于其 pattern/交互区内），则只有该殖民地 Owner
 * 可破坏/放置/开启；非 Owner 一律取消事件并反馈。爆炸过滤掉所有殖民地建筑的受影响方块，
 * 防止 TNT/苦力怕/袭击炸毁小镇。
 *
 * <p>只保护「属于建筑」的方块，而不是整个 256 工作圈——不干扰野外地形，避免过度限制。
 */
public final class ColonyLandProtectionHandler {

    private static final String TAG = "ColonyLandProtectionHandler";

    private ColonyLandProtectionHandler() {}

    /** 该方块所属建筑对应的殖民地（无建筑/unowned 返回 null）。 */
    @Nullable
    private static UUID buildingColony(Level level, BlockPos pos) {
        BuildingSavedData sd = BuildingSavedData.get(level);
        if (sd == null) return null;
        UUID bid = sd.getBuildingIdAt(pos);
        if (bid == null) bid = sd.getBuildingIdInInteractionZone(pos);
        if (bid == null) return null;
        BuildingState st = sd.getBuilding(bid);
        return st != null ? st.getColonyId() : null;
    }

    private static boolean isClaimed(Level level, BlockPos pos) {
        return buildingColony(level, pos) != null;
    }

    private static boolean protect(ServerLevel level, BlockPos pos, ServerPlayer player, String what) {
        UUID colonyId = buildingColony(level, pos);
        if (colonyId != null && !ColonyOwnership.isOwn(colonyId, player)) {
            ColonyOwnership.deny(player, what);
            Log.warn(TAG, "Blocked player {} {} at {} in colony {}",
                    player.getGameProfile().getName(), what, pos,
                    colonyId.toString().substring(0, 8));
            return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player
                && protect((ServerLevel) event.getLevel(), event.getPos(), player, "方块")) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && protect((ServerLevel) event.getLevel(), event.getPos(), player, "方块")) {
            event.setCanceled(true);
        }
    }

    /**
     * 保护领地内的原版容器方块（箱子/熔炉/漏斗等）防翻箱。
     * Wandscape 自建建筑的右键交互由 {@link com.wsteam.wandscape.content.building.internal.BuildingInteractHandler}
     * 面板门控处理，这里只管「属于殖民地建筑、但仍是 vanilla 容器」的方块。
     */
    @SubscribeEvent
    public static void onInteractBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        BlockPos pos = event.getPos();
        BlockState state = event.getLevel().getBlockState(pos);
        if (state.isAir()) return;
        if (!(event.getLevel().getBlockEntity(pos) instanceof Container)) return;

        if (protect((ServerLevel) event.getLevel(), pos, player, "容器")) {
            event.setCanceled(true);
        }
    }

    /** 爆炸：只移除「属于殖民地建筑」的方块，保护小镇结构不被炸毁。 */
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        var level = event.getLevel();
        event.getAffectedBlocks().removeIf(pos -> isClaimed(level, pos));
    }
}
