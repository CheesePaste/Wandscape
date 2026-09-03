package com.wsteam.wandscape.content.items.oathring.internal;

import com.wsteam.wandscape.content.items.oathring.network.OathRingDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 玩家登录后推送其盟誓戒指共享空间占用掩码，保证客户端 tooltip 首次渲染即有正确数量。
 */
public final class OathRingSyncHandler {

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            byte mask = OathRingSavedData.get(player.getServer()).maskFor(player.getUUID());
            PacketDistributor.sendToPlayer(player, new OathRingDataPacket(mask));
        }
    }
}