package com.wsteam.wandscape.content.building.projection.network;

import com.wsteam.wandscape.content.building.data.BuildingPackage;
import com.wsteam.wandscape.content.building.projection.data.BuildingSlot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→Client: Response to {@link ProjectionEnterPacket}.
 * Carries the building selection list, package metadata list, and body anchor position.
 *
 * <p>If {@code granted} is true, the client sets up projection mode
 * (free flight, ghost rendering, flight controller).
 * If {@code granted} is false, the client exits projection mode
 * (teleports to body anchor, restores abilities).
 */
public record ProjectionEnterResponsePacket(
        boolean granted,
        List<BuildingSlot> buildingSlots,
        List<BuildingPackage> packages,
        BlockPos bodyAnchor) implements CustomPacketPayload {

    public static final Type<ProjectionEnterResponsePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "projection_enter_response"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProjectionEnterResponsePacket> STREAM_CODEC =
            StreamCodec.of(ProjectionEnterResponsePacket::write, ProjectionEnterResponsePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static java.util.function.Consumer<ProjectionEnterResponsePacket> clientHandler = packet -> {};
    public static void setClientHandler(java.util.function.Consumer<ProjectionEnterResponsePacket> handler) { clientHandler = handler; }

    public static void handleClient(ProjectionEnterResponsePacket packet) {
        clientHandler.accept(packet);
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, ProjectionEnterResponsePacket pkt) {
        buf.writeBoolean(pkt.granted);
        buf.writeVarInt(pkt.buildingSlots.size());
        for (BuildingSlot slot : pkt.buildingSlots) {
            buf.writeUtf(slot.id());
            buf.writeUtf(slot.packageId());
            buf.writeUtf(slot.displayName());
            buf.writeUtf(slot.category());
            buf.writeBoolean(slot.firstFreeAvailable());
        }
        buf.writeVarInt(pkt.packages.size());
        for (BuildingPackage pkg : pkt.packages) {
            pkg.writeToBuf(buf);
        }
        buf.writeBlockPos(pkt.bodyAnchor);
    }

    static ProjectionEnterResponsePacket read(RegistryFriendlyByteBuf buf) {
        boolean granted = buf.readBoolean();
        int count = buf.readVarInt();
        List<BuildingSlot> slots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            slots.add(new BuildingSlot(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readBoolean()));
        }
        int pkgCount = buf.readVarInt();
        List<BuildingPackage> pkgs = new ArrayList<>(pkgCount);
        for (int i = 0; i < pkgCount; i++) {
            pkgs.add(BuildingPackage.readFromBuf(buf));
        }
        BlockPos anchor = buf.readBlockPos();
        return new ProjectionEnterResponsePacket(granted, slots, pkgs, anchor);
    }
}
