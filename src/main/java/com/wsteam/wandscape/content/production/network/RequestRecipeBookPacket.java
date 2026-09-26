package com.wsteam.wandscape.content.production.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client->Server packet requesting open/refresh of the recipe book screen for a colony.
 */
public record RequestRecipeBookPacket(UUID colonyId) implements CustomPacketPayload {

    public static final Type<RequestRecipeBookPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "request_recipe_book"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestRecipeBookPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> buf.writeUUID(pkt.colonyId()),
                    buf -> new RequestRecipeBookPacket(buf.readUUID())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(RequestRecipeBookPacket pkt, ServerPlayer player) {
        if (player.getServer() != null) {
            player.getServer().execute(() -> RecipeBookDataPacket.sendTo(player, pkt.colonyId()));
        }
    }
}
