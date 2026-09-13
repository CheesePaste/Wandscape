package com.wsteam.wandscape.foundation.ui.settings.network;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.ui.settings.SettingsOverlay;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server->Client: hands the client the authoritative value of a common config key.
 *
 * <p>{@code accepted=false} 表示服务端拒绝了刚才那次修改（无权限 / 值非法），
 * {@code value} 是服务端当前的真实值，客户端据此撤回乐观改动。
 *
 * <p>{@code accepted=false} means the server rejected the change; {@code value} carries the server's
 * current value so the client can roll back.
 */
public record ConfigSyncPacket(String path, String value, boolean accepted) implements CustomPacketPayload {

    private static final String TAG = "ConfigSyncPacket";

    public static final Type<ConfigSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "config_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigSyncPacket> STREAM_CODEC =
            StreamCodec.of(ConfigSyncPacket::write, ConfigSyncPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void write(RegistryFriendlyByteBuf buf, ConfigSyncPacket pkt) {
        buf.writeUtf(pkt.path);
        buf.writeUtf(pkt.value);
        buf.writeBoolean(pkt.accepted);
    }

    static ConfigSyncPacket read(RegistryFriendlyByteBuf buf) {
        return new ConfigSyncPacket(buf.readUtf(), buf.readUtf(), buf.readBoolean());
    }

    public static void handleClient(ConfigSyncPacket packet) {
        if (packet.accepted) {
            Log.info(TAG, "[ConfigSync] Received synced config: {} = {}", packet.path, packet.value);
        } else {
            Log.warn(TAG, "[ConfigSync] Server rejected config change, reverting: {}", packet.path);
        }

        if (ConfigUpdatePacket.applyConfig(packet.path, packet.value)) {
            // 通用配置的落盘只发生在这里：值来自服务端，客户端不再自己写盘，避免把被拒绝的值留在本地。
            if (Config.SPEC.isLoaded()) {
                Config.SPEC.save();
            }
        }

        if (!packet.accepted) {
            SettingsOverlay.showToast("无权限修改服务端配置，已还原");
        }
    }
}
