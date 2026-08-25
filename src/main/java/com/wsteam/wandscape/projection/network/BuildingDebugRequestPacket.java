package com.wsteam.wandscape.projection.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.shared.data.WorkItem;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Client→Server: request debug data for the building at the given position.
 */
public record BuildingDebugRequestPacket(BlockPos pos) implements CustomPacketPayload {

    private static final String TAG = "BuildingDebugRequestPacket";

    public static final Type<BuildingDebugRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "building_debug_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingDebugRequestPacket> STREAM_CODEC =
            StreamCodec.of(BuildingDebugRequestPacket::write, BuildingDebugRequestPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(BuildingDebugRequestPacket packet, ServerPlayer player) {
        var sd = com.wsteam.wandscape.building.internal.BuildingSavedData.get(player.level());
        if (sd == null) {
            Log.warn(TAG, "[Debug] No BuildingSavedData for player {}", player.getGameProfile().getName());
            return;
        }

        var state = sd.getBuildingAt(packet.pos());
        if (state == null) {
            Log.info(TAG, "[Debug] No building at {} for player {}", packet.pos(), player.getGameProfile().getName());
            return;
        }

        int comfort = state.getComfort();
        int magic = state.getMagic();
        int wonder = state.getWonder();

        // For shops, add in-stock goods bonuses so the V panel reflects effective values
        if ("shop".equals(state.getCategory())) {
            var stockMgr = com.wsteam.wandscape.building.internal.ShopStockManager.getActive();
            if (stockMgr != null) {
                comfort += stockMgr.getGoodsBonusComfort(state.getBuildingId());
                magic += stockMgr.getGoodsBonusMagic(state.getBuildingId());
                wonder += stockMgr.getGoodsBonusWonder(state.getBuildingId());
            }
        }

        List<WorkItem> queueSnapshot = new ArrayList<>(state.getTaskQueue());
        String typeId = state.getBuildingTypeId();
        var config = com.wsteam.wandscape.building.internal.BuildingConfigLoader.getInstance().get(typeId);
        String displayName = (config != null && config.displayName() != null && !config.displayName().isEmpty())
                ? config.displayName() : typeId;

        // Whether the building has any damaged pattern blocks (minor < 1/3 or broken
        // >= 1/3) — drives the client-side Repair button availability.
        boolean needsRepair = config != null && !com.wsteam.wandscape.building.internal.BuildCompleteListener
                .findDamagedBlocks(player.level(), state.getAnchor(), config, state.getRotationSteps())
                .isEmpty();

        var response = new BuildingDebugResponsePacket(
                state.getBuildingId(), typeId, displayName, state.getCategory(),
                state.getColonyId(), state.getAnchor(),
                state.isStructureIntact(), needsRepair, state.isShutdown(),
                !state.hasEverCompleted(), state.isConstructionStarted(),
                state.isDemolishing(),
                comfort, magic, wonder,
                queueSnapshot, state.getCurrentTaskId()
        );
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, response);

        Log.info(TAG, "[Debug] Sent debug data for '{}' at {} to {}",
                state.getBuildingTypeId(), packet.pos(), player.getGameProfile().getName());
    }

    static void write(RegistryFriendlyByteBuf buf, BuildingDebugRequestPacket pkt) {
        buf.writeBlockPos(pkt.pos());
    }

    static BuildingDebugRequestPacket read(RegistryFriendlyByteBuf buf) {
        return new BuildingDebugRequestPacket(buf.readBlockPos());
    }
}
