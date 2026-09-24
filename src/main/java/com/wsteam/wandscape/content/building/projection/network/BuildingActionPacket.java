package com.wsteam.wandscape.content.building.projection.network;

import com.wsteam.wandscape.content.building.internal.BuildingRepairHandler;
import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.networking.Net;
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

        // 完全平行隔离：只能对自己小镇的建筑执行销毁/撤销/维修。
        if (!com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.isOwn(state.getColonyId(), player)) {
            com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.deny(player, "building", "建筑");
            return;
        }

        var api = WandscapeApis.getBuildingApi();

        // 名字按玩家上报的语言挑——服务端只有这一条按语言取名的路径，其余服务端用途
        // （日志、任务参数）都吃 displayName() 那个无语言兜底值。
        String name = state.getDisplayName();
        var config = com.wsteam.wandscape.content.building.internal.BuildingConfigLoader
                .getInstance().get(state.getBuildingTypeId());
        if (config != null) {
            name = config.displayNameFor(player.getLanguage());
        }

        switch (packet.action()) {
            case "destroy" -> {
                var reason = api.demolishBlockReason(packet.buildingId());
                if (reason != null) {
                    player.displayClientMessage(
                            I18n.name("message.wandscape.building.demolish_blocked",
                                    "§c[建筑] 无法拆除「%s」：", name).append(reason), true);
                    Log.warn(TAG, "Player {} blocked demolition of {} ({}) — last {} building protected",
                            player.getGameProfile().getName(), state.getBuildingTypeId(),
                            packet.buildingId(), state.getCategory());
                } else {
                    api.demolishBuilding(packet.buildingId());
                    player.displayClientMessage(
                            I18n.name("message.wandscape.building.demolishing",
                                    "§c[建筑] 正在拆除「%s」... 已下发拆除任务", name), true);
                    Log.info(TAG, "Player {} initiated demolition of {} ({}) at {}",
                            player.getGameProfile().getName(), state.getBuildingTypeId(),
                            packet.buildingId(), state.getAnchor());
                }
            }
            case "repair" -> {
                boolean ok = BuildingRepairHandler.triggerRepair(player.level(), packet.buildingId());
                if (ok) {
                    player.displayClientMessage(
                            I18n.name("message.wandscape.building.repairing",
                                    "§a[建筑] 正在维修「%s」... 已下发修复任务", name), true);
                    Log.info(TAG, "Player {} triggered repair for {} ({})",
                            player.getGameProfile().getName(), state.getBuildingTypeId(), packet.buildingId());
                } else {
                    player.displayClientMessage(
                            I18n.name("message.wandscape.building.repair_failed",
                                    "§e[建筑]「%s」当前无需维修", name), true);
                    Log.warn(TAG, "Player {} tried to repair {} ({}) but repair failed",
                            player.getGameProfile().getName(), state.getBuildingTypeId(), packet.buildingId());
                }
            }
            case "cancel" -> {
                // Undo an under-construction building (waiting for materials / being built).
                var reason = api.demolishBlockReason(packet.buildingId());
                if (reason != null) {
                    player.displayClientMessage(
                            I18n.name("message.wandscape.building.cancel_blocked",
                                    "§c[建筑] 无法撤销「%s」的建造：", name).append(reason), true);
                    Log.warn(TAG, "Player {} blocked cancel of {} ({}) — last {} building protected",
                            player.getGameProfile().getName(), state.getBuildingTypeId(),
                            packet.buildingId(), state.getCategory());
                } else {
                    boolean ok = api.cancelBuilding(packet.buildingId());
                    if (ok) {
                        player.displayClientMessage(
                                I18n.name("message.wandscape.building.cancelled",
                                        "§e[建筑] 已撤销「%s」的建造", name), true);
                        Log.info(TAG, "Player {} cancelled under-construction building {} ({})",
                                player.getGameProfile().getName(), state.getBuildingTypeId(), packet.buildingId());
                    } else {
                        player.displayClientMessage(
                                I18n.name("message.wandscape.building.cancel_failed",
                                        "§c[建筑] 无法撤销「%s」的建造", name), true);
                        Log.warn(TAG, "Player {} tried to cancel {} ({}) but it cannot be cancelled",
                                player.getGameProfile().getName(), state.getBuildingTypeId(), packet.buildingId());
                    }
                }
            }
            default -> Log.warn(TAG, "Unknown action: {}", packet.action());
        }

        // Resend updated building snapshot to player so client UI updates immediately
        var updatedState = sd.getBuilding(packet.buildingId());
        if (updatedState != null) {
            var updatedPkt = BuildingDebugRequestPacket.buildResponse(player.level(), updatedState);
            Net.toPlayer(player, updatedPkt);
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
