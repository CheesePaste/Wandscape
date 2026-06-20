package com.wsteam.wandscape.warehouse;

import com.wsteam.wandscape.shared.event.ResourceInsufficientEvent;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.LogicalSide;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Subscribes to Wandscape game-layer events on {@code NeoForge.EVENT_BUS}
 * and sends player-facing notifications.
 *
 * <p>Registered in {@link com.wsteam.wandscape.Wandscape#Wandscape}.
 */
public class WarehouseNotificationHandler {

    private static final String NOTIFY_TAG = "[Wandscape] ";

    @SubscribeEvent
    public void onResourceInsufficient(ResourceInsufficientEvent event) {
        // Send to all online players — later can filter by colony ownership
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        Component msg = Component.literal(NOTIFY_TAG + event.getShortageMessage());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(msg);
        }
    }

    /** Register this handler on the NeoForge event bus. */
    public static void register() {
        NeoForge.EVENT_BUS.register(new WarehouseNotificationHandler());
    }
}
