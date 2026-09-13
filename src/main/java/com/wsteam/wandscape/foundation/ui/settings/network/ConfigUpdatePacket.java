package com.wsteam.wandscape.foundation.ui.settings.network;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.ui.settings.SettingItem;
import com.wsteam.wandscape.foundation.ui.settings.SettingsRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Arrays;
import java.util.List;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client->Server: Requests updating a common config value.
 * Validates player permissions (OP level 2 or singleplayer host) before applying.
 *
 * <p>通用配置只有服务端说了算：客户端把「请求」发过来，服务端校验通过才落盘，并把权威值广播回去；
 * 校验不通过则把服务端当前值回吐给发起者，让客户端撤回那笔乐观改动。
 *
 * <p>Common config is server-authoritative: the client sends a request, the server validates, persists,
 * and broadcasts the authoritative value back. On rejection the server echoes its current value to the
 * requester so the client can roll back its optimistic edit.
 */
public record ConfigUpdatePacket(String path, String value) implements CustomPacketPayload {

    private static final String TAG = "ConfigUpdatePacket";

    /** 建筑包相关路径不是注册设置项（包 ID 由客户端建筑包动态生成），单独处理。 */
    private static final String PACKAGE_PREFIX = "building.package.";
    private static final String DISABLED_PACKAGES = "building.disabledPackages";

    public static final Type<ConfigUpdatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "config_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigUpdatePacket> STREAM_CODEC =
            StreamCodec.of(ConfigUpdatePacket::write, ConfigUpdatePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void write(RegistryFriendlyByteBuf buf, ConfigUpdatePacket pkt) {
        buf.writeUtf(pkt.path);
        buf.writeUtf(pkt.value);
    }

    static ConfigUpdatePacket read(RegistryFriendlyByteBuf buf) {
        return new ConfigUpdatePacket(buf.readUtf(), buf.readUtf());
    }

    public static void handleServer(ConfigUpdatePacket packet, ServerPlayer player) {
        if (player == null || player.isRemoved()) return;

        boolean isOp = player.hasPermissions(2)
                || (player.getServer() != null && player.getServer().isSingleplayerOwner(player.getGameProfile()));
        if (!isOp) {
            Log.warn(TAG, "Player {} attempted to modify config {} without permissions",
                    player.getName().getString(), packet.path);
            reject(player, packet.path);
            return;
        }

        if (applyConfig(packet.path, packet.value)) {
            Config.SPEC.save();
            Log.info(TAG, "Config {} updated to {} by player {}",
                    packet.path, packet.value, player.getName().getString());
            // 广播的是服务端落盘后的值，不是客户端报上来的值：夹取/取整只在这里发生一次，两端才不会各持一份。
            PacketDistributor.sendToAllPlayers(new ConfigSyncPacket(packet.path, readConfig(packet.path), true));
        } else {
            // 未知路径 / 客户端专属项 / 值解析失败：同样回吐权威值，客户端据此撤回。
            reject(player, packet.path);
        }
    }

    /** 拒绝一次修改：只回吐给发起者，带上服务端当前的权威值让他还原。 */
    private static void reject(ServerPlayer player, String path) {
        PacketDistributor.sendToPlayer(player, new ConfigSyncPacket(path, readConfig(path), false));
    }

    public static boolean applyConfig(String path, String value) {
        try {
            Boolean special = applySpecial(path, value);
            if (special != null) return special;

            SettingItem item = SettingsRegistry.findByKey(path);
            if (item == null) {
                Log.warn(TAG, "Unknown config path: {}", path);
                return false;
            }
            if (item.isClientOnly()) {
                // 客户端专属项没有跨端同步的意义，且 dedicated server 上它的 spec 未加载。
                Log.warn(TAG, "Refusing client-only config from network: {}", path);
                return false;
            }
            if (!item.applyFromString(value)) {
                Log.warn(TAG, "Failed to apply config {}={}", path, value);
                return false;
            }
            item.onApplied();
            return true;
        } catch (Exception e) {
            Log.warn(TAG, "Failed to apply config {}={}: {}", path, value, e.getMessage());
            return false;
        }
    }

    /** path 对应的权威当前值，用于拒绝时回吐、以及广播时避免回传未夹取的原始输入。 */
    public static String readConfig(String path) {
        try {
            if (path.startsWith(PACKAGE_PREFIX)) {
                return String.valueOf(Config.isPackageEnabled(path.substring(PACKAGE_PREFIX.length())));
            }
            if (DISABLED_PACKAGES.equals(path)) {
                List<? extends String> disabled = Config.DISABLED_BUILDING_PACKAGES.get();
                return disabled == null ? "" : String.join(",", disabled);
            }
            SettingItem item = SettingsRegistry.findByKey(path);
            return item == null ? "" : item.rawValue();
        } catch (Exception e) {
            Log.warn(TAG, "Failed to read config {}: {}", path, e.getMessage());
            return "";
        }
    }

    /** 建筑包那两条不是注册设置项，单独处理。返回 null 表示不是特例。 */
    private static Boolean applySpecial(String path, String value) {
        if (path.startsWith(PACKAGE_PREFIX)) {
            Config.setPackageEnabled(path.substring(PACKAGE_PREFIX.length()), Boolean.parseBoolean(value));
            return true;
        }
        if (DISABLED_PACKAGES.equals(path)) {
            Config.setDisabledPackages(value.isEmpty() ? List.of() : Arrays.asList(value.split(",")));
            return true;
        }
        return null;
    }
}
