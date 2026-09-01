package com.wsteam.wandscape.content.building.network;

import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.building.internal.NodeGatherTaskFactory;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import com.wsteam.wandscape.api.BuildingApi;
import com.wsteam.wandscape.content.building.data.WorkItem;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→server packet requesting a manual node gather task.
 * {@code harvests} is the number of harvest operations — the amount and mana
 * are scaled from the building's {@code node_config} in
 * {@link NodeGatherTaskFactory#buildWorkItem}.
 */
public record RequestGatherTaskPacket(
    BlockPos nodePos,
    int harvests
) implements CustomPacketPayload {

    private static final String TAG = "RequestGatherTaskPacket";

    public static final Type<RequestGatherTaskPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "request_gather_task"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestGatherTaskPacket> STREAM_CODEC =
            StreamCodec.of(RequestGatherTaskPacket::write, RequestGatherTaskPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server-side handler. The client refreshes the queue afterwards via TaskQueueModifyPacket. */
    public static void handleServer(RequestGatherTaskPacket pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;

        sp.getServer().execute(() -> {
            var level = sp.serverLevel();
            BuildingSavedData data = BuildingSavedData.get(level);
            UUID buildingId = data.getBuildingIdAt(pkt.nodePos);
            if (buildingId == null) {
                Log.warn(TAG, "RequestGatherTask: no building at {}", pkt.nodePos);
                return;
            }

            BuildingState state = data.getBuilding(buildingId);
            if (state == null) {
                Log.warn(TAG, "RequestGatherTask: building state null for {}", buildingId);
                return;
            }

            BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
            if (config == null || config.nodeConfig() == null) {
                Log.warn(TAG, "RequestGatherTask: building {} has no node_config", state.getBuildingTypeId());
                return;
            }

            WorkItem work = NodeGatherTaskFactory.buildWorkItem(pkt.nodePos, config.nodeConfig(), pkt.harvests);
            BuildingApi api = WandscapeApis.getBuildingApi();
            api.enqueueWork(buildingId, work);

            // A player-published gather task counts toward onboarding step 8.
            if (state.getColonyId() != null) {
                var bank = ColonyItemBank.get(level);
                if (bank != null) bank.recordGatherPublished(state.getColonyId());
                var guideApi = com.wsteam.wandscape.api.WandscapeApis.getGuideProgressApiSilently();
                if (guideApi != null) guideApi.sendToPlayer(sp, state.getColonyId());
            }

            Log.info(TAG, "[GatherTask] enqueued {} x{} at building {} (harvests={})",
                    config.nodeConfig().element(), config.nodeConfig().amountPerHarvest() * pkt.harvests,
                    buildingId.toString().substring(0, 8),
                    Math.max(pkt.harvests, 1));
        });
    }

    static void write(RegistryFriendlyByteBuf buf, RequestGatherTaskPacket pkt) {
        buf.writeBlockPos(pkt.nodePos);
        buf.writeVarInt(pkt.harvests);
    }

    static RequestGatherTaskPacket read(RegistryFriendlyByteBuf buf) {
        return new RequestGatherTaskPacket(buf.readBlockPos(), buf.readVarInt());
    }
}
