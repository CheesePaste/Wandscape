package com.wsteam.wandscape.content.road.network;

import com.wsteam.wandscape.content.colony.roster.ColonyRole;
import com.wsteam.wandscape.content.task.component.Position;

import com.wsteam.wandscape.content.building.network.ConstructionSiteDataPacket;
import com.wsteam.wandscape.content.colony.overview.network.OverviewInteractPacket;
import com.wsteam.wandscape.content.road.core.PathPoint;
import com.wsteam.wandscape.content.road.core.RoadEdge;
import com.wsteam.wandscape.content.road.engine.RoadSavedData;
import com.wsteam.wandscape.content.road.engine.RoadSiteData;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.networking.Net;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

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

        // 档位：打开道路工地面板（含撤路/补料按钮）= MANAGER；edge 无归属时沿用原语义放行。
        UUID edgeColonyId = edge.getColonyId();
        if (edgeColonyId != null
                && !com.wsteam.wandscape.content.colony.ownership.ColonyOwnership
                        .hasRole(player, edgeColonyId, ColonyRole.MANAGER)) {
            com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.deny(player, "road", "道路");
            return;
        }

        UUID colonyId = com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.activeColony(player);
        if (colonyId == null) {
            colonyId = edgeColonyId;
        }
        ConstructionSiteDataPacket siteData = RoadSiteData.fromEdge(level, edge, colonyId);
        Net.toPlayer(player, siteData);
        Log.info(TAG, "[Interact] Opened road construction panel for edge {}", edge.getEdgeId());
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, RoadInteractPacket pkt) {
        buf.writeBlockPos(pkt.pos);
    }

    static RoadInteractPacket read(RegistryFriendlyByteBuf buf) {
        return new RoadInteractPacket(buf.readBlockPos());
    }
}
