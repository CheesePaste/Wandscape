package com.wsteam.wandscape.projection.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.shared.data.WorkItem;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: request debug data for the building at the given position.
 */
public record BuildingDebugRequestPacket(BlockPos pos) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<BuildingDebugRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "building_debug_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingDebugRequestPacket> STREAM_CODEC =
            StreamCodec.of(BuildingDebugRequestPacket::write, BuildingDebugRequestPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(BuildingDebugRequestPacket packet, ServerPlayer player) {
        var sd = com.wsteam.wandscape.building.internal.BuildingSavedData.get(player.level());
        if (sd == null) {
            LOGGER.warn("[Debug] No BuildingSavedData for player {}", player.getGameProfile().getName());
            return;
        }

        var state = sd.getBuildingAt(packet.pos());
        if (state == null) {
            LOGGER.info("[Debug] No building at {} for player {}", packet.pos(), player.getGameProfile().getName());
            return;
        }

        List<WorkItem> queueSnapshot = new ArrayList<>(state.getTaskQueue());
        var response = new BuildingDebugResponsePacket(
                state.getBuildingId(), state.getBuildingTypeId(), state.getCategory(),
                state.getColonyId(), state.getAnchor(),
                state.isStructureIntact(), state.isShutdown(),
                state.getComfort(), state.getMagic(), state.getWonder(),
                state.getQueueCapacity(),
                queueSnapshot, state.getCurrentTaskId()
        );
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, response);

        LOGGER.info("[Debug] Sent debug data for '{}' at {} to {}",
                state.getBuildingTypeId(), packet.pos(), player.getGameProfile().getName());
    }

    static void write(RegistryFriendlyByteBuf buf, BuildingDebugRequestPacket pkt) {
        buf.writeBlockPos(pkt.pos());
    }

    static BuildingDebugRequestPacket read(RegistryFriendlyByteBuf buf) {
        return new BuildingDebugRequestPacket(buf.readBlockPos());
    }
}
