package com.wsteam.wandscape.compass;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 玩家登录 / 切换维度时同步一次市政厅目标到客户端，让指南针一进世界就校准。
 * 中途新建/重建市政厅由指南针物品的 {@code inventoryTick} 节流重发兜底。
 */
public final class CompassSyncHandler {

    private CompassSyncHandler() {}

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CompassService.syncFor(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CompassService.syncFor(player);
        }
    }
}
