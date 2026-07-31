package com.wsteam.wandscape.shared.network;

import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.api.ColonyMetricsApi;
import com.wsteam.wandscape.shared.data.ColonyMetricsSnapshot;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Notifies server that the player opened or closed the Wandscape panel.
 * Server stores this state to gate building right-click interactions.
 */
public record PanelStateTogglePacket(boolean open) implements CustomPacketPayload {

    public static final Type<PanelStateTogglePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "panel_state_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PanelStateTogglePacket> STREAM_CODEC =
            StreamCodec.of(PanelStateTogglePacket::write, PanelStateTogglePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleServer(PanelStateTogglePacket packet, ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (packet.open) {
            PanelStateTracker.open(playerId);
            ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
            if (colonyApi != null) {
                UUID colonyId = colonyApi.getColonyId(player.blockPosition());
                if (colonyId != null) {
                    ColonyMetricsApi metricsApi = WandscapeApis.getColonyMetricsApiSilently();
                    if (metricsApi != null) {
                        ColonyMetricsSnapshot snap = metricsApi.getSnapshotSafe(colonyId);
                        if (snap.colonyId() != null) {
                            PacketDistributor.sendToPlayer(player, ColonyStatsSyncPacket.fromSnapshot(snap));
                        }
                    }

                    // Sync building interaction areas for overlay rendering
                    BuildingApi buildingApi = WandscapeApis.getBuildingApi();
                    List<BuildingAreaSyncPacket.BuildingEntry> entries =
                            buildingApi.getColonyBuildings(colonyId).stream()
                                    .map(BuildingAreaSyncPacket::fromBuildingData)
                                    .toList();
                    PacketDistributor.sendToPlayer(player,
                            new BuildingAreaSyncPacket(entries));
                }
            }
        } else {
            PanelStateTracker.close(playerId);
        }
    }

    static void write(RegistryFriendlyByteBuf buf, PanelStateTogglePacket pkt) {
        buf.writeBoolean(pkt.open);
    }

    static PanelStateTogglePacket read(RegistryFriendlyByteBuf buf) {
        return new PanelStateTogglePacket(buf.readBoolean());
    }
}
