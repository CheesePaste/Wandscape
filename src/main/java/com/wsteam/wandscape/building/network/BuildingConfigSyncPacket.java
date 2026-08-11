package com.wsteam.wandscape.building.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;

public record BuildingConfigSyncPacket(List<String> jsonConfigs) implements CustomPacketPayload {

    public static final Type<BuildingConfigSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "building_config_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingConfigSyncPacket> STREAM_CODEC =
            StreamCodec.of(BuildingConfigSyncPacket::write, BuildingConfigSyncPacket::read);

    private static Consumer<BuildingConfigSyncPacket> clientHandler = packet -> {};
    public static void setClientHandler(Consumer<BuildingConfigSyncPacket> handler) { clientHandler = handler; }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(BuildingConfigSyncPacket packet) {
        clientHandler.accept(packet);
    }

    static void write(RegistryFriendlyByteBuf buf, BuildingConfigSyncPacket pkt) {
        buf.writeVarInt(pkt.jsonConfigs.size());
        for (String json : pkt.jsonConfigs) {
            buf.writeUtf(json, 262144);
        }
    }

    static BuildingConfigSyncPacket read(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(buf.readUtf(262144));
        }
        return new BuildingConfigSyncPacket(list);
    }
}
