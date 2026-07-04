package com.wsteam.wandscape.projection.network;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.EnqueueHelper;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Client→Server: Player confirms building placement from projection mode.
 *
 * <p>Server handler:
 * <ol>
 *   <li>Validates the building type exists in config</li>
 *   <li>Checks position is not overlapping an existing building</li>
 *   <li>Registers the building via {@link EnqueueHelper#registerIfAbsent}</li>
 *   <li>Creates and enqueues a {@link WorkItem} for NPC construction</li>
 * </ol>
 */
public record ProjectionPlacePacket(
        String buildingTypeId,
        BlockPos anchorPos) implements CustomPacketPayload {

    private static final String TAG = "ProjectionPlacePacket";

    public static final Type<ProjectionPlacePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "projection_place"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProjectionPlacePacket> STREAM_CODEC =
            StreamCodec.of(ProjectionPlacePacket::write, ProjectionPlacePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    public static void handleServer(ProjectionPlacePacket packet, ServerPlayer player) {
        // 1. Validate building type exists
        BuildingConfig config = BuildingConfigLoader.getInstance().get(packet.buildingTypeId);
        if (config == null) {
            player.displayClientMessage(
                    Component.literal("[Projection] §cUnknown building type: " + packet.buildingTypeId),
                    false);
            Log.warn(TAG, "[Projection] Unknown building type '{}' from player {}",
                    packet.buildingTypeId, player.getGameProfile().getName());
            return;
        }

        // 2. Validate position not overlapping
        BuildingApi api = WandscapeApis.getBuildingApi();
        if (api == null) {
            player.displayClientMessage(
                    Component.literal("[Projection] §cBuilding API unavailable"),
                    false);
            return;
        }

        if (api.getBuildingAt(packet.anchorPos) != null) {
            player.displayClientMessage(
                    Component.literal("[Projection] §cCannot place here — another building occupies this location"),
                    false);
            return;
        }

        // 3. Register building
        boolean registered = EnqueueHelper.registerIfAbsent(
                packet.anchorPos, config, packet.buildingTypeId);

        if (!registered) {
            player.displayClientMessage(
                    Component.literal("[Projection] §cFailed to register building at " +
                            packet.anchorPos.getX() + ", " +
                            packet.anchorPos.getY() + ", " +
                            packet.anchorPos.getZ()),
                    false);
            return;
        }

        // 4. Enqueue build work item (filtered clear_offsets — skip other buildings' blocks)
        var buildingData = api.getBuildingAt(packet.anchorPos);
        if (buildingData != null) {
            BuildingSavedData sd = BuildingSavedData.get(player.serverLevel());
            WorkItem workItem = EnqueueHelper.buildWorkItem(
                    config, packet.anchorPos, packet.buildingTypeId, 0,
                    sd, buildingData.getBuildingId());
            api.enqueueWork(buildingData.getBuildingId(), workItem);

            player.displayClientMessage(
                    Component.literal("[Projection] §a" + config.displayName() +
                            " §fplaced at (" +
                            packet.anchorPos.getX() + ", " +
                            packet.anchorPos.getY() + ", " +
                            packet.anchorPos.getZ() + ") — §aNPC will construct"),
                    false);

            Log.info(TAG, "[Projection] Building '{}' placed at {} by player {}. WorkItem enqueued.",
                    config.displayName(), packet.anchorPos, player.getGameProfile().getName());
        } else {
            Log.warn(TAG, "[Projection] Building registered but getBuildingAt returned null at {}",
                    packet.anchorPos);
        }
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, ProjectionPlacePacket pkt) {
        buf.writeUtf(pkt.buildingTypeId);
        buf.writeBlockPos(pkt.anchorPos);
    }

    static ProjectionPlacePacket read(RegistryFriendlyByteBuf buf) {
        return new ProjectionPlacePacket(buf.readUtf(), buf.readBlockPos());
    }
}
