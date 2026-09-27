package com.wsteam.wandscape.content.building.network;

import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.building.internal.ConstructionSupply;
import com.wsteam.wandscape.content.road.core.RoadEdge;
import com.wsteam.wandscape.content.road.engine.RoadSavedData;
import com.wsteam.wandscape.content.road.engine.RoadSiteData;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.Net;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: 工地面板的「一键制作」按钮。
 *
 * <p>按当前缺口（需求 − 仓库库存 − 已在制）把该工地的建材下成殖民工作站的合成任务，
 * 走玩家优先级档；不碰「已自动补过料」的记账，所以玩家随时可以再点一次补上后来缺的料。
 * 建筑与道路共用同一个工地面板，用 {@code road} 分流。
 *
 * <p>处理完把工地面板的新快照发回客户端，让「制作中 / 待制作」当场刷新
 * （建筑快照有 5 秒 CD，不失效会把点按钮前的旧状态发回去）。
 */
public record ConstructionCraftAllPacket(UUID targetId, boolean road) implements CustomPacketPayload {

    private static final String TAG = "ConstructionCraftAll";

    public static final Type<ConstructionCraftAllPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "construction_craft_all"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConstructionCraftAllPacket> STREAM_CODEC =
            StreamCodec.of(ConstructionCraftAllPacket::write, ConstructionCraftAllPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    public static void handleServer(ConstructionCraftAllPacket packet, ServerPlayer player) {
        if (player == null || player.level() == null) return;
        if (packet.road()) {
            craftRoad(packet.targetId(), player);
        } else {
            craftBuilding(packet.targetId(), player);
        }
    }

    private static void craftBuilding(UUID buildingId, ServerPlayer player) {
        BuildingSavedData sd = BuildingSavedData.get(player.level());
        BuildingState state = sd != null ? sd.getBuilding(buildingId) : null;
        if (state == null) {
            Log.warn(TAG, "Building {} not found for craft-all", buildingId);
            return;
        }

        // 完全平行隔离：只能对自己小镇的工地补料。
        if (!com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.isOwn(state.getColonyId(), player)) {
            com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.deny(player, "building", "建筑");
            return;
        }

        ConstructionSupply.Result result =
                ConstructionSupply.craftForBuilding(state, WandscapeConstants.TASK_PRIORITY_PLAYER);
        ConstructionSupply.announce(player, result);

        ConstructionSiteDataPacket.invalidateSnapshot(buildingId);
        Net.toPlayer(player, ConstructionSiteDataPacket.from(player.level(), state));
        Log.info(TAG, "Player {} craft-all on building {} — enqueued={} covered={} blocked={}",
                player.getGameProfile().getName(), buildingId.toString().substring(0, 8),
                result.enqueued(), result.covered(), result.blocked());
    }

    private static void craftRoad(UUID edgeId, ServerPlayer player) {
        var level = player.serverLevel();
        RoadEdge edge = RoadSavedData.getOrCreate(level).getNetwork().getEdge(edgeId);
        if (edge == null) {
            Log.warn(TAG, "Road edge {} not found for craft-all", edgeId);
            return;
        }

        if (!com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.isOwn(edge.getColonyId(), player)) {
            com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.deny(player, "road", "道路");
            return;
        }

        ConstructionSupply.Result result =
                ConstructionSupply.craftForRoad(edge, WandscapeConstants.TASK_PRIORITY_PLAYER);
        ConstructionSupply.announce(player, result);

        UUID colonyId = com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.ownColony(player);
        Net.toPlayer(player, RoadSiteData.fromEdge(
                level, edge, colonyId != null ? colonyId : edge.getColonyId()));
        Log.info(TAG, "Player {} craft-all on road edge {} — enqueued={} covered={} blocked={}",
                player.getGameProfile().getName(), edgeId.toString().substring(0, 8),
                result.enqueued(), result.covered(), result.blocked());
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, ConstructionCraftAllPacket pkt) {
        buf.writeUUID(pkt.targetId);
        buf.writeBoolean(pkt.road);
    }

    static ConstructionCraftAllPacket read(RegistryFriendlyByteBuf buf) {
        return new ConstructionCraftAllPacket(buf.readUUID(), buf.readBoolean());
    }
}
