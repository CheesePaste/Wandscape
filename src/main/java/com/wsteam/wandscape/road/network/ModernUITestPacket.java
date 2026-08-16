package com.wsteam.wandscape.road.network;

import static com.wsteam.wandscape.Wandscape.MODID;

import com.wsteam.wandscape.road.client.modernui.ModernUIRoadTestFragment;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server→Client packet to open the ModernUI Test Screen / Fragment.
 */
public record ModernUITestPacket() implements CustomPacketPayload {

    public static final Type<ModernUITestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "modern_ui_test"));

    public static final StreamCodec<ByteBuf, ModernUITestPacket> STREAM_CODEC =
            StreamCodec.unit(new ModernUITestPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(ModernUITestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(ModernUIRoadTestFragment::open);
    }
}
