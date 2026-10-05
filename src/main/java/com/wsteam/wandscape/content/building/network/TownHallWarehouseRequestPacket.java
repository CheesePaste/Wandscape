package com.wsteam.wandscape.content.building.network;

import com.wsteam.wandscape.content.colony.roster.ColonyRole;
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

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→server: town hall "warehouse access" button pressed. Server validates the
 * position is a government building of the given colony, then opens the warehouse
 * container menu so the town hall acts as a warehouse when the colony has no
 * storage building.
 */
public record TownHallWarehouseRequestPacket(BlockPos buildingPos, UUID colonyId)
        implements CustomPacketPayload {

    private static final String TAG = "TownHallWarehouseRequest";

    public static final Type<TownHallWarehouseRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "town_hall_warehouse_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TownHallWarehouseRequestPacket> STREAM_CODEC =
            StreamCodec.of(TownHallWarehouseRequestPacket::write, TownHallWarehouseRequestPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server-side handler. */
    public static void handleServer(TownHallWarehouseRequestPacket pkt, ServerPlayer sp) {

        sp.getServer().execute(() -> {
            var level = sp.serverLevel();
            BuildingSavedData data = BuildingSavedData.get(level);
            UUID buildingId = data.getBuildingIdAt(pkt.buildingPos());
            if (buildingId == null) {
                Log.warn(TAG, "[TownHallWarehouse] no building at {}", pkt.buildingPos());
                return;
            }

            BuildingState state = data.getBuilding(buildingId);
            if (state == null || !"government".equals(state.getCategory())) {
                Log.warn(TAG, "[TownHallWarehouse] building {} is not a government building",
                        buildingId);
                return;
            }

            UUID colonyId = state.getColonyId();
            if (colonyId == null || !colonyId.equals(pkt.colonyId())) {
                Log.warn(TAG, "[TownHallWarehouse] colony mismatch for {}", buildingId);
                return;
            }
            // 档位：市政厅代开仓库是操作性入口（映射划 MANAGER），非成员与档位不足一律拒止。
            if (!com.wsteam.wandscape.content.colony.ownership.ColonyOwnership
                    .hasRole(sp, colonyId, ColonyRole.MANAGER)) {
                com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.deny(sp, "warehouse", "仓库");
                return;
            }

            // 市政厅代开：不给建筑上下文——那时的建筑是市政厅，挂上复原/拆除会打到市政厅身上，
            // 而市政厅面板自己已有这对按钮。
            BuildingInteractHandler.openWarehouseMenu(sp, colonyId, pkt.buildingPos(),
                    BuildingInteractHandler.resolveCreator(level, pkt.buildingPos()), null);
        });
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, TownHallWarehouseRequestPacket pkt) {
        buf.writeBlockPos(pkt.buildingPos);
        buf.writeUUID(pkt.colonyId);
    }

    static TownHallWarehouseRequestPacket read(RegistryFriendlyByteBuf buf) {
        return new TownHallWarehouseRequestPacket(buf.readBlockPos(), buf.readUUID());
    }
}
