package com.wsteam.wandscape.foundation.ui.settings.network;

import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server->Client: Broadcasts updated config values to keep client cache in sync.
 */
public record ConfigSyncPacket(String path, String value) implements CustomPacketPayload {

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
    }

    static ConfigSyncPacket read(RegistryFriendlyByteBuf buf) {
        return new ConfigSyncPacket(buf.readUtf(), buf.readUtf());
    }

    public static void handleClient(ConfigSyncPacket packet) {
        Log.info(TAG, "[ConfigSync] Received synced config: {} = {}", packet.path, packet.value);
        ConfigUpdatePacket.applyConfig(packet.path, packet.value);
    }
}
