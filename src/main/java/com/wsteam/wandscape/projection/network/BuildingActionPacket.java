package com.wsteam.wandscape.projection.network;

import java.util.UUID;

import com.wsteam.wandscape.building.internal.BuildingBreakHandler;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Client→Server: perform an admin action on a building (shutdown, restart, destroy).
 */
public record BuildingActionPacket(UUID buildingId, String action) implements CustomPacketPayload {

    private static final String TAG = "BuildingActionPacket";

    public static final Type<BuildingActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "building_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingActionPacket> STREAM_CODEC =
            StreamCodec.of(BuildingActionPacket::write, BuildingActionPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(BuildingActionPacket packet, ServerPlayer player) {
        var sd = BuildingSavedData.get(player.level());
        if (sd == null) {
            Log.warn(TAG, "No BuildingSavedData for player {}", player.getGameProfile().getName());
            return;
        }

        BuildingState state = sd.getBuilding(packet.buildingId());
        if (state == null) {
            Log.warn(TAG, "Building {} not found for action {}", packet.buildingId(), packet.action());
            return;
        }

        var api = WandscapeApis.getBuildingApi();

        switch (packet.action()) {
            case "shutdown" -> {
                api.shutdown(packet.buildingId(), "manual");
                Log.info(TAG, "Player {} shutdown building {} ({})",
                        player.getGameProfile().getName(), state.getBuildingTypeId(), packet.buildingId());
            }
            case "restart" -> {
                api.restart(packet.buildingId());
                Log.info(TAG, "Player {} restarted building {} ({})",
                        player.getGameProfile().getName(), state.getBuildingTypeId(), packet.buildingId());
            }
            case "destroy" -> {
                api.demolishBuilding(packet.buildingId());
                Log.info(TAG, "Player {} initiated demolition of {} ({}) at {}",
                        player.getGameProfile().getName(), state.getBuildingTypeId(),
                        packet.buildingId(), state.getAnchor());
            }
            case "repair" -> {
                boolean ok = BuildingBreakHandler.triggerRepair(player.level(), packet.buildingId());
                if (ok) {
                    Log.info(TAG, "Player {} triggered repair for {} ({})",
                            player.getGameProfile().getName(), state.getBuildingTypeId(), packet.buildingId());
                } else {
                    Log.warn(TAG, "Player {} tried to repair {} ({}) but repair failed",
                            player.getGameProfile().getName(), state.getBuildingTypeId(), packet.buildingId());
                }
            }
            default -> Log.warn(TAG, "Unknown action: {}", packet.action());
        }
    }

    static void write(RegistryFriendlyByteBuf buf, BuildingActionPacket pkt) {
        buf.writeUUID(pkt.buildingId());
        buf.writeUtf(pkt.action(), 32);
    }

    static BuildingActionPacket read(RegistryFriendlyByteBuf buf) {
        return new BuildingActionPacket(buf.readUUID(), buf.readUtf(32));
    }
}
