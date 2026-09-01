package com.wsteam.wandscape.road.network;

import com.wsteam.wandscape.building.network.ConstructionSiteDataPacket;
import com.wsteam.wandscape.content.colony.overview.network.OverviewInteractPacket;
import com.wsteam.wandscape.road.core.PathPoint;
import com.wsteam.wandscape.road.core.RoadEdge;
import com.wsteam.wandscape.road.engine.RoadSavedData;
import com.wsteam.wandscape.road.engine.RoadSiteData;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: player right-clicks a block that lands on an under-construction
 * road edge. Server resolves the edge by position and sends the road
 * {@link ConstructionSiteDataPacket} back to open the shared construction-site panel
 * (mirrors {@link OverviewInteractPacket} for buildings).
 */
public record RoadInteractPacket(BlockPos pos) implements CustomPacketPayload {

    private static final String TAG = "RoadInteractPacket";

    public static final Type<RoadInteractPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "road_interact"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoadInteractPacket> STREAM_CODEC =
            StreamCodec.of(RoadInteractPacket::write, RoadInteractPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    public static void handleServer(RoadInteractPacket packet, ServerPlayer player) {
        if (player == null || player.level() == null) return;
        var level = player.serverLevel();

        RoadSavedData data = RoadSavedData.getOrCreate(level);
        var network = data.getNetwork();
        RoadEdge edge = network.findEdgeAt(new PathPoint(
                packet.pos().getX(), packet.pos().getY(), packet.pos().getZ()));
        if (edge == null) {
            Log.info(TAG, "[Interact] No road edge at {}", packet.pos());
            return;
        }
        if (edge.getStatus() == RoadEdge.EdgeStatus.COMPLETE) {
            return; // completed roads show no construction panel
        }

        UUID colonyId = resolveColonyId(player);
        ConstructionSiteDataPacket siteData = RoadSiteData.fromEdge(level, edge, colonyId);
        PacketDistributor.sendToPlayer(player, siteData);
        Log.info(TAG, "[Interact] Opened road construction panel for edge {}", edge.getEdgeId());
    }

    private static UUID resolveColonyId(ServerPlayer player) {
        var colonyApi = WandscapeApis.getColonyApiSilently();
        UUID colonyId = colonyApi != null ? colonyApi.getColonyId(player.blockPosition()) : null;
        return colonyId != null ? colonyId : new UUID(0, 0);
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, RoadInteractPacket pkt) {
        buf.writeBlockPos(pkt.pos);
    }

    static RoadInteractPacket read(RegistryFriendlyByteBuf buf) {
        return new RoadInteractPacket(buf.readBlockPos());
    }
}
