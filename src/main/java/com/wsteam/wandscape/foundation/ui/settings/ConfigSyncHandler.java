package com.wsteam.wandscape.foundation.ui.settings;

import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import com.wsteam.wandscape.foundation.networking.Net;
import com.wsteam.wandscape.foundation.ui.settings.network.ConfigSnapshotPacket;
import com.wsteam.wandscape.foundation.ui.settings.network.ConfigUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家登录时把服务端当前的通用配置权威值一次性下发。
 *
 * <p>{@code Config} 是 {@code ModConfig.Type.COMMON}——专用服务器上客户端与服务器各持一份
 * {@code wandscape-common.toml}，而 {@code ConfigSyncPacket} 只在「有人改值」时下发。于是玩家一进服，
 * 设置面板显示的是**自己本地那份**值，改容量时的新值也从那个旧基线算起（可能算出来等于甚至小于
 * 服务端真值）。本处理器补齐这条缺失的入服同步。
 *
 * <p>用 {@link SettingsRegistry#getSyncableItems()} 而不是 {@code getAllItems()}：后者会带上建筑包
 * 那两条动态项，而它们由客户端从建筑包列表现建（{@code ProjectionClientState}），服务端恒空、
 * 调它也没有意义；整张建筑包页的状态由 {@code building.disabledPackages} 这一个键派生。
 */
public final class ConfigSyncHandler {

    private static final String TAG = "ConfigSyncHandler";

    /** 建筑包页的唯一权威键（由 {@code ConfigUpdatePacket.applyConfig/readConfig} 特判处理）。 */
    private static final String DISABLED_PACKAGES_KEY = "building.disabledPackages";

    private ConfigSyncHandler() {}

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        List<String> paths = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (SettingItem item : SettingsRegistry.getSyncableItems()) {
            paths.add(item.key());
            values.add(ConfigUpdatePacket.readConfig(item.key()));
        }
        paths.add(DISABLED_PACKAGES_KEY);
        values.add(ConfigUpdatePacket.readConfig(DISABLED_PACKAGES_KEY));

        Net.toPlayer(player, new ConfigSnapshotPacket(List.copyOf(paths), List.copyOf(values)));
        Log.debug(LogCategory.UI, "config_sync", "{} 已向 {} 下发配置快照 {} 项",
                TAG, player.getName().getString(), paths.size());
    }
}
