package com.wsteam.wandscape.content.colony.overview.network;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.EntityId;

import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.tourist.entity.TouristEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Player requests entity interaction from overview mode.
 * Server looks up the entity and triggers the same {@code mobInteract()}
 * flow that normal right-click would (sends back TouristDataPacket / NpcDataPacket).
 */
public record OverviewEntityInteractPacket(int entityId) implements CustomPacketPayload {

    public static final Type<OverviewEntityInteractPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "overview_entity_interact"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OverviewEntityInteractPacket> STREAM_CODEC =
            StreamCodec.of(OverviewEntityInteractPacket::write, OverviewEntityInteractPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    public static void handleServer(OverviewEntityInteractPacket packet, ServerPlayer player) {
        Entity entity = player.serverLevel().getEntity(packet.entityId());
        if (!(entity.isAlive())) return;
        // Cast to concrete types whose mobInteract() is public via override.
        // mobInteract() on Mob is protected, but TouristEntity and WandscapeNpc
        // override it publicly — call directly after instanceof checks.
        if (entity instanceof TouristEntity tourist) {
            tourist.mobInteract(player, InteractionHand.MAIN_HAND);
        } else if (entity instanceof WandscapeNpc npc) {
            npc.mobInteract(player, InteractionHand.MAIN_HAND);
        }
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, OverviewEntityInteractPacket pkt) {
        buf.writeInt(pkt.entityId);
    }

    static OverviewEntityInteractPacket read(RegistryFriendlyByteBuf buf) {
        return new OverviewEntityInteractPacket(buf.readInt());
    }
}
