package com.wsteam.wandscape.content.building.projection.network;

import com.wsteam.wandscape.content.building.projection.client.ProjectionClientState;
import com.wsteam.wandscape.content.building.projection.data.BuildingSlot;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→Client: refreshed building slot list (e.g. after a placement claims a
 * first-free build). Client swaps the slot list in place without resetting
 * projection/selection state, so the build panel's first-free badges stay accurate.
 */
public record ProjectionSlotsRefreshPacket(
        List<BuildingSlot> buildingSlots) implements CustomPacketPayload {

    private static final String TAG = "ProjectionSlotsRefreshPacket";

    public static final Type<ProjectionSlotsRefreshPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "projection_slots_refresh"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProjectionSlotsRefreshPacket> STREAM_CODEC =
            StreamCodec.of(ProjectionSlotsRefreshPacket::write, ProjectionSlotsRefreshPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(ProjectionSlotsRefreshPacket packet) {
        ProjectionClientState.updateBuildingSlots(packet.buildingSlots);
    }

    static void write(RegistryFriendlyByteBuf buf, ProjectionSlotsRefreshPacket pkt) {
        buf.writeVarInt(pkt.buildingSlots.size());
        for (BuildingSlot slot : pkt.buildingSlots) {
            buf.writeUtf(slot.id());
            buf.writeUtf(slot.displayName());
            buf.writeUtf(slot.category());
            buf.writeBoolean(slot.firstFreeAvailable());
        }
    }

    static ProjectionSlotsRefreshPacket read(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<BuildingSlot> slots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            slots.add(new BuildingSlot(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readBoolean()));
        }
        return new ProjectionSlotsRefreshPacket(slots);
    }
}
