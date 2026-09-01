package com.wsteam.wandscape.content.building.projection.network;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.building.network.BuildingAreaSyncPacket;
import com.wsteam.wandscape.content.colony.network.ColonyCreatePromptPacket;
import com.wsteam.wandscape.content.building.data.WorkItem;

import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.foundation.sound.SoundService;
import com.wsteam.wandscape.foundation.registry.WandscapeSounds;
import com.wsteam.wandscape.content.building.projection.data.BuildingSlot;
import com.wsteam.wandscape.api.BuildingApi;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;

import java.util.List;
import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Player confirms building placement from projection mode.
 *
 * <p>Server handler delegates to {@link BuildingApi#placeBuilding} which
 * validates, checks overlap, registers, handles first-free, and enqueues
 * the WorkItem in a single unified call.
 */
public record ProjectionPlacePacket(
        String buildingTypeId,
        BlockPos anchorPos,
        int rotationSteps) implements CustomPacketPayload {

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
            ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.projection.unknown_type",
                    "[Projection] §cUnknown building type: %s", packet.buildingTypeId), true);
            Log.warn(TAG, "[Projection] Unknown building type '{}' from player {}",
                    packet.buildingTypeId, player.getGameProfile().getName());
            return;
        }

        // 2. Unified placement — validates, registers, handles first-free, enqueues WorkItem
        BuildingApi api = WandscapeApis.getBuildingApi();
        if (api == null) {
            ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.projection.api_unavailable",
                    "[Projection] §cBuilding API unavailable"), true);
            return;
        }

        BuildingApi.PlacementResult result = api.placeBuilding(
                packet.anchorPos, packet.buildingTypeId, packet.rotationSteps);

        if (!result.success()) {
            ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.projection.place_failed",
                    "[Projection] §c%s", result.error()), true);
            return;
        }

        // 3. Success — construction task enqueued. No chat feedback: the construction
        // ghost on the client shows the result, and placement is its own confirmation.
        Log.info(TAG, "[Projection] '{}' placed at {} by {} firstFree={}",
                config.displayName(), packet.anchorPos,
                player.getGameProfile().getName(), result.firstFree());

        SoundService.playAt(player.serverLevel(), packet.anchorPos,
                WandscapeSounds.BUILDING_PLACE, SoundSource.BLOCKS, 0.5f, 1.0f);

        // 4. Refresh the client's building-area cache so the newly placed
        // building's construction ghost appears immediately (no need to
        // reopen the panel).
        com.wsteam.wandscape.content.building.network.BuildingAreaSyncPacket.sendToPlayer(player);

        // 4b. Refresh projection slots so first-free badges stay accurate —
        // placing a first-free building claims it server-side. Resolve the
        // colony from the placement anchor (ground position near the colony,
        // unlike the free-flying body position).
        if (ProjectionNetwork.isProjecting(player)) {
            UUID colonyId = null;
            var colonyApi = com.wsteam.wandscape.api.WandscapeApis.getColonyApiSilently();
            if (colonyApi != null) {
                colonyId = colonyApi.getColonyId(packet.anchorPos);
            }
            List<BuildingSlot> slots = ProjectionNetwork.getAvailableBuildings(colonyId);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    new ProjectionSlotsRefreshPacket(slots));
        }

        // 4c. Push tutorial progress — the newly placed building may advance a step.
        var tutorialApi = com.wsteam.wandscape.api.WandscapeApis.getTutorialApiSilently();
        if (tutorialApi != null) {
            var colonyApi2 = com.wsteam.wandscape.api.WandscapeApis.getColonyApiSilently();
            UUID guideColony = colonyApi2 != null ? colonyApi2.getColonyId(packet.anchorPos) : null;
            tutorialApi.sendToPlayer(player, guideColony);
        }

        // 5. If placing a government building (Town Hall) and no colony is linked to this position, prompt for colony creation
        if ("government".equals(config.category())) {
            var colonyApi = com.wsteam.wandscape.api.WandscapeApis.getColonyApiSilently();
            if (colonyApi == null || colonyApi.getColonyId(packet.anchorPos) == null) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new com.wsteam.wandscape.content.colony.network.ColonyCreatePromptPacket(
                                packet.anchorPos, config.creator() != null ? config.creator() : ""));
                Log.info(TAG, "[Projection] Government building placed at {}, prompting for colony creation", packet.anchorPos);
            }
        }
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, ProjectionPlacePacket pkt) {
        buf.writeUtf(pkt.buildingTypeId);
        buf.writeBlockPos(pkt.anchorPos);
        buf.writeVarInt(pkt.rotationSteps & 3);
    }

    static ProjectionPlacePacket read(RegistryFriendlyByteBuf buf) {
        return new ProjectionPlacePacket(buf.readUtf(), buf.readBlockPos(), buf.readVarInt());
    }
}
