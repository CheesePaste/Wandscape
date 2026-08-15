package com.wsteam.wandscape.projection.network;

import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.projection.data.BuildingSlot;
import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.shared.ui.I18n;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;

import static com.wsteam.wandscape.Wandscape.MODID;
import com.wsteam.wandscape.shared.log.Log;

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
        // 1. Validate building type exists (needed for display name in messages)
        BuildingConfig config = BuildingConfigLoader.getInstance().get(packet.buildingTypeId);
        if (config == null) {
            player.displayClientMessage(
                    Component.literal("[Projection] §cUnknown building type: " + packet.buildingTypeId),
                    false);
            Log.warn(TAG, "[Projection] Unknown building type '{}' from player {}",
                    packet.buildingTypeId, player.getGameProfile().getName());
            return;
        }

        // 2. Unified placement — validates, registers, handles first-free, enqueues WorkItem
        BuildingApi api = WandscapeApis.getBuildingApi();
        if (api == null) {
            player.displayClientMessage(
                    Component.literal("[Projection] §cBuilding API unavailable"),
                    false);
            return;
        }

        BuildingApi.PlacementResult result = api.placeBuilding(
                packet.anchorPos, packet.buildingTypeId, packet.rotationSteps);

        if (!result.success()) {
            player.displayClientMessage(
                    Component.literal("[Projection] §c").append(result.error()),
                    false);
            return;
        }

        // 3. Success — notify player. Building name resolves against the client locale
        // via building.wandscape.<id>; displayName stays as the fallback (D4).
        String posStr = packet.anchorPos.getX() + ", " +
                packet.anchorPos.getY() + ", " +
                packet.anchorPos.getZ();
        Component buildingName = I18n.name("building.wandscape." + config.id(), config.displayName());

        if (result.firstFree()) {
            player.displayClientMessage(
                    Component.literal("[Projection] §a")
                            .append(buildingName)
                            .append(Component.literal(" §fplaced at (" + posStr +
                                    ") — §eFREE first build, no materials consumed")),
                    false);
        } else {
            player.displayClientMessage(
                    Component.literal("[Projection] §a")
                            .append(buildingName)
                            .append(Component.literal(" §fplaced at (" + posStr +
                                    ") — §aNPC will construct")),
                    false);
        }

        Log.info(TAG, "[Projection] '{}' placed at {} by {} firstFree={}",
                config.displayName(), packet.anchorPos,
                player.getGameProfile().getName(), result.firstFree());

        SoundService.playAt(player.serverLevel(), packet.anchorPos,
                WandscapeSounds.BUILDING_PLACE, SoundSource.BLOCKS, 0.5f, 1.0f);

        // 4. Refresh the client's building-area cache so the newly placed
        // building's construction ghost appears immediately (no need to
        // reopen the panel).
        com.wsteam.wandscape.shared.network.BuildingAreaSyncPacket.sendToPlayer(player);

        // 4b. Refresh projection slots so first-free badges stay accurate —
        // placing a first-free building claims it server-side. Resolve the
        // colony from the placement anchor (ground position near the colony,
        // unlike the free-flying body position).
        if (ProjectionNetwork.isProjecting(player)) {
            UUID colonyId = null;
            var colonyApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getColonyApiSilently();
            if (colonyApi != null) {
                colonyId = colonyApi.getColonyId(packet.anchorPos);
            }
            List<BuildingSlot> slots = ProjectionNetwork.getAvailableBuildings(colonyId);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    new ProjectionSlotsRefreshPacket(slots));
        }

        // 4c. Push tutorial progress — the newly placed building may advance a step.
        var guideApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getGuideProgressApiSilently();
        if (guideApi != null) {
            var colonyApi2 = com.wsteam.wandscape.shared.registry.WandscapeApis.getColonyApiSilently();
            UUID guideColony = colonyApi2 != null ? colonyApi2.getColonyId(packet.anchorPos) : null;
            guideApi.sendToPlayer(player, guideColony);
        }

        // 5. If placing a government building (Town Hall) and no colony is linked to this position, prompt for colony creation
        if ("government".equals(config.category())) {
            var colonyApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getColonyApiSilently();
            if (colonyApi == null || colonyApi.getColonyId(packet.anchorPos) == null) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new com.wsteam.wandscape.shared.network.ColonyCreatePromptPacket(packet.anchorPos));
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
