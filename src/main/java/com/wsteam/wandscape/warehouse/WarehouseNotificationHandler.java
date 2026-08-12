package com.wsteam.wandscape.warehouse;

import com.wsteam.wandscape.shared.event.ResourceInsufficientEvent;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
/**
 * Subscribes to Wandscape game-layer events on {@code NeoForge.EVENT_BUS}.
 *
 * <p>Registered in {@link com.wsteam.wandscape.Wandscape#Wandscape}.
 */
public class WarehouseNotificationHandler {

    @SubscribeEvent
    public void onResourceInsufficient(ResourceInsufficientEvent event) {
        // 资源不足通知此前会刷屏聊天区，已移除上屏，保留订阅以挂接后续处理
    }

    /** Register this handler on the NeoForge event bus. */
    public static void register() {
        NeoForge.EVENT_BUS.register(new WarehouseNotificationHandler());
    }
}
