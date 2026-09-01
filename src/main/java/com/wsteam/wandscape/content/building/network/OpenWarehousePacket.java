package com.wsteam.wandscape.content.building.network;
import com.wsteam.wandscape.content.task.component.Position;

import com.wsteam.wandscape.content.building.internal.BuildingInteractHandler;
import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.foundation.log.Log;
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
 * Client→server: an "open warehouse" button was pressed on a colony building
 * screen (workstation / crafting station / magic station / mage hut). The server
 * resolves the building at the given position, looks up its colony, then opens
 * the warehouse container menu so the player can view elements and stored items.
 */
public record OpenWarehousePacket(BlockPos buildingPos)
        implements CustomPacketPayload {

    private static final String TAG = "OpenWarehouse";

    public static final Type<OpenWarehousePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "open_warehouse"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenWarehousePacket> STREAM_CODEC =
            StreamCodec.of(OpenWarehousePacket::write, OpenWarehousePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server-side handler. */
    public static void handleServer(OpenWarehousePacket pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;

        sp.getServer().execute(() -> {
            var level = sp.serverLevel();
            BuildingSavedData data = BuildingSavedData.get(level);
            UUID buildingId = data.getBuildingIdAt(pkt.buildingPos());
            if (buildingId == null) {
                Log.warn(TAG, "[OpenWarehouse] no building at {}", pkt.buildingPos());
                return;
            }

            BuildingState state = data.getBuilding(buildingId);
            if (state == null) {
                Log.warn(TAG, "[OpenWarehouse] building state null for {}", buildingId);
                return;
            }

            UUID colonyId = state.getColonyId();
            if (colonyId == null) {
                Log.warn(TAG, "[OpenWarehouse] building {} has no colony — cannot open warehouse",
                        buildingId);
                return;
            }

            BuildingInteractHandler.openWarehouseMenu(sp, colonyId, pkt.buildingPos(),
                    BuildingInteractHandler.resolveCreator(level, pkt.buildingPos()));
        });
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, OpenWarehousePacket pkt) {
        buf.writeBlockPos(pkt.buildingPos);
    }

    static OpenWarehousePacket read(RegistryFriendlyByteBuf buf) {
        return new OpenWarehousePacket(buf.readBlockPos());
    }
}
