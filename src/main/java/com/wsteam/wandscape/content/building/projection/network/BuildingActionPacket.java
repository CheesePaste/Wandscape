package com.wsteam.wandscape.content.building.projection.network;

import com.wsteam.wandscape.content.building.internal.BuildingRepairHandler;
import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: perform an admin action on a building (repair, destroy, cancel).
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

        String name = state.getDisplayName();
        switch (packet.action()) {
            case "destroy" -> {
                var reason = api.demolishBlockReason(packet.buildingId());
                if (reason != null) {
                    player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal("§c[建筑] 无法拆除「" + name + "」：").append(reason), true);
                    Log.warn(TAG, "Player {} blocked demolition of {} ({}) — last {} building protected",
                            player.getGameProfile().getName(), state.getBuildingTypeId(),
                            packet.buildingId(), state.getCategory());
                } else {
                    api.demolishBuilding(packet.buildingId());
                    player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal("§c[建筑] 正在拆除「" + name + "」... 已下发拆除任务"), true);
                    Log.info(TAG, "Player {} initiated demolition of {} ({}) at {}",
                            player.getGameProfile().getName(), state.getBuildingTypeId(),
                            packet.buildingId(), state.getAnchor());
                }
            }
            case "repair" -> {
                boolean ok = BuildingRepairHandler.triggerRepair(player.level(), packet.buildingId());
                if (ok) {
                    player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal("§a[建筑] 正在维修「" + name + "」... 已下发修复任务"), true);
                    Log.info(TAG, "Player {} triggered repair for {} ({})",
                            player.getGameProfile().getName(), state.getBuildingTypeId(), packet.buildingId());
                } else {
                    player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal("§e[建筑]「" + name + "」当前无需维修"), true);
                    Log.warn(TAG, "Player {} tried to repair {} ({}) but repair failed",
                            player.getGameProfile().getName(), state.getBuildingTypeId(), packet.buildingId());
                }
            }
            case "cancel" -> {
                // Undo an under-construction building (waiting for materials / being built).
                var reason = api.demolishBlockReason(packet.buildingId());
                if (reason != null) {
                    player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal("§c[建筑] 无法撤销「" + name + "」的建造：").append(reason), true);
                    Log.warn(TAG, "Player {} blocked cancel of {} ({}) — last {} building protected",
                            player.getGameProfile().getName(), state.getBuildingTypeId(),
                            packet.buildingId(), state.getCategory());
                } else {
                    boolean ok = api.cancelBuilding(packet.buildingId());
                    if (ok) {
                        player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal("§e[建筑] 已撤销「" + name + "」的建造"), true);
                        Log.info(TAG, "Player {} cancelled under-construction building {} ({})",
                                player.getGameProfile().getName(), state.getBuildingTypeId(), packet.buildingId());
                    } else {
                        player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal("§c[建筑] 无法撤销「" + name + "」的建造"), true);
                        Log.warn(TAG, "Player {} tried to cancel {} ({}) but it cannot be cancelled",
                                player.getGameProfile().getName(), state.getBuildingTypeId(), packet.buildingId());
                    }
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
