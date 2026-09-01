package com.wsteam.wandscape.overview.network;

import com.wsteam.wandscape.building.internal.BuildingInteractHandler;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.shared.log.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Player requests building interaction from overview mode.
 * Server looks up the building at the given position and delegates to
 * {@link BuildingInteractHandler#handleInteraction} — the same dispatch
 * used by normal-mode right-click.
 */
public record OverviewInteractPacket(BlockPos buildingBlockPos) implements CustomPacketPayload {

    private static final String TAG = "OverviewInteractPacket";

    public static final Type<OverviewInteractPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "overview_interact"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OverviewInteractPacket> STREAM_CODEC =
            StreamCodec.of(OverviewInteractPacket::write, OverviewInteractPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    public static void handleServer(OverviewInteractPacket packet, ServerPlayer player) {
        Level level = player.serverLevel();
        BlockPos pos = packet.buildingBlockPos();

        // Look up building at position
        BuildingSavedData data = BuildingSavedData.get(level);
        java.util.UUID buildingId = data.getBuildingIdAt(pos);

        // Fallback: interaction zone
        if (buildingId == null) {
            buildingId = data.getBuildingIdInInteractionZone(pos);
        }

        if (buildingId == null) {
            Log.info(TAG, "[Overview] No building found at {}", pos);
            return;
        }

        BuildingState state = data.getBuilding(buildingId);
        if (state == null) {
            Log.warn(TAG, "[Overview] Building state null for {}", buildingId);
            return;
        }

        // Delegate to the shared building interaction dispatch
        BuildingInteractHandler.handleInteraction(player, level, pos, state);
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, OverviewInteractPacket pkt) {
        buf.writeBlockPos(pkt.buildingBlockPos);
    }

    static OverviewInteractPacket read(RegistryFriendlyByteBuf buf) {
        return new OverviewInteractPacket(buf.readBlockPos());
    }
}
