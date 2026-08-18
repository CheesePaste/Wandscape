package com.wsteam.wandscape.road.network;

import static com.wsteam.wandscape.Wandscape.MODID;

import com.wsteam.wandscape.road.client.RoadPlacementState;
import com.wsteam.wandscape.road.client.SplineEditorClientState;
import com.wsteam.wandscape.road.client.studio.RoadStudioOverlay;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server→Client: Directs client to enter or exit the self-drawn Native Road Studio.
 */
public record RoadStudioEnterPacket(boolean enter) implements CustomPacketPayload {

    public static final Type<RoadStudioEnterPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "road_studio_enter"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoadStudioEnterPacket> STREAM_CODEC =
            StreamCodec.of(RoadStudioEnterPacket::write, RoadStudioEnterPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(RoadStudioEnterPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (packet.enter()) {
                RoadPlacementState.setActiveTool(RoadPlacementState.ToolMode.SPLINE);
                RoadPlacementState.enterProjection();
                SplineEditorClientState.enterEditMode();
                RoadStudioOverlay.open();
            } else {
                RoadPlacementState.exitProjection();
                SplineEditorClientState.exitEditMode();
                RoadStudioOverlay.close();
            }
        });
    }

    private static void write(RegistryFriendlyByteBuf buf, RoadStudioEnterPacket pkt) {
        buf.writeBoolean(pkt.enter());
    }

    private static RoadStudioEnterPacket read(RegistryFriendlyByteBuf buf) {
        return new RoadStudioEnterPacket(buf.readBoolean());
    }
}
