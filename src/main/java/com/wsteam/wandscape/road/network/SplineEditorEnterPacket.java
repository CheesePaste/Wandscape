package com.wsteam.wandscape.road.network;

import static com.wsteam.wandscape.Wandscape.MODID;

import com.wsteam.wandscape.road.client.SplineEditorClientState;

import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server→Client: Directs client to enter or exit Spline Road Editor.
 */
public record SplineEditorEnterPacket(boolean enter) implements CustomPacketPayload {

    public static final Type<SplineEditorEnterPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "spline_editor_enter"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SplineEditorEnterPacket> STREAM_CODEC =
            StreamCodec.of(SplineEditorEnterPacket::write, SplineEditorEnterPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(SplineEditorEnterPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (packet.enter()) {
                SplineEditorClientState.enterEditMode();
                // Command entry: the V-panel is closed, so lift the cursor so the overlay is clickable.
                if (mc.mouseHandler != null) mc.mouseHandler.releaseMouse();
            } else {
                SplineEditorClientState.exitEditMode();
                if (mc.mouseHandler != null) mc.mouseHandler.grabMouse();
            }
        });
    }

    private static void write(RegistryFriendlyByteBuf buf, SplineEditorEnterPacket pkt) {
        buf.writeBoolean(pkt.enter());
    }

    private static SplineEditorEnterPacket read(RegistryFriendlyByteBuf buf) {
        return new SplineEditorEnterPacket(buf.readBoolean());
    }
}
