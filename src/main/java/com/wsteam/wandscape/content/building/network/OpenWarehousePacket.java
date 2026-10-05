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
    public static void handleServer(OpenWarehousePacket pkt, ServerPlayer sp) {

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
            // 档位：仓库存取 = MEMBER（映射「仓库存取」），非成员与档位不足一律拒止。
            if (!com.wsteam.wandscape.content.colony.ownership.ColonyOwnership
                    .hasRole(sp, colonyId, ColonyRole.MEMBER)) {
                com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.deny(sp, "warehouse", "仓库");
                return;
            }

            // 本包是「从别的建筑屏跳到本镇仓库」的快捷入口（法师小屋/合成站/工作台），
            // buildingPos 是那座建筑而不是仓库——刻意不给建筑上下文：否则面板会把复原/拆除
            // 挂到法师小屋身上。只有右键仓库建筑本体（BuildingInteractHandler 的 storage 分支）
            // 才带 buildingId。
            BuildingInteractHandler.openWarehouseMenu(sp, colonyId, pkt.buildingPos(),
                    BuildingInteractHandler.resolveCreator(level, pkt.buildingPos()), null);
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
