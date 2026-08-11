package com.wsteam.wandscape.building.network;

import java.util.UUID;

import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→server packet: request a fresh {@link ConstructionSiteDataPacket} for a building.
 * Sent periodically by the open construction-site screen so material/status estimates
 * stay live as construction and workstation synthesis progress.
 */
public record ConstructionSiteRefreshPacket(UUID buildingId) implements CustomPacketPayload {

    private static final String TAG = "ConstructionSiteRefreshPacket";

    public static final Type<ConstructionSiteRefreshPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "construction_site_refresh"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConstructionSiteRefreshPacket> STREAM_CODEC =
            StreamCodec.of(ConstructionSiteRefreshPacket::write, ConstructionSiteRefreshPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void write(RegistryFriendlyByteBuf buf, ConstructionSiteRefreshPacket pkt) {
        buf.writeUUID(pkt.buildingId);
    }

    static ConstructionSiteRefreshPacket read(RegistryFriendlyByteBuf buf) {
        return new ConstructionSiteRefreshPacket(buf.readUUID());
    }

    public static void handleServer(ConstructionSiteRefreshPacket packet, ServerPlayer player) {
        if (player == null || player.isRemoved()) return;
        var level = player.serverLevel();
        BuildingState state = BuildingSavedData.get(level).getBuilding(packet.buildingId());
        if (state == null) {
            Log.warn(TAG, "refresh for missing building {}", packet.buildingId());
            return;
        }
        PacketDistributor.sendToPlayer(player, ConstructionSiteDataPacket.from(level, state));
    }
}
