package com.wsteam.wandscape.projection.network;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.projection.data.BuildingSlot;
import com.wsteam.wandscape.projection.client.ProjectionClientState;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→Client: Response to {@link ProjectionEnterPacket}.
 * Carries the building selection list and body anchor position.
 *
 * <p>If {@code granted} is true, the client sets up projection mode
 * (free flight, ghost rendering, flight controller).
 * If {@code granted} is false, the client exits projection mode
 * (teleports to body anchor, restores abilities).
 */
public record ProjectionEnterResponsePacket(
        boolean granted,
        List<BuildingSlot> buildingSlots,
        BlockPos bodyAnchor) implements CustomPacketPayload {

    public static final Type<ProjectionEnterResponsePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "projection_enter_response"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProjectionEnterResponsePacket> STREAM_CODEC =
            StreamCodec.of(ProjectionEnterResponsePacket::write, ProjectionEnterResponsePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Client handler ──

    public static void handleClient(ProjectionEnterResponsePacket packet) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;

        if (packet.granted) {
            ProjectionClientState.enterProjection(packet.bodyAnchor, packet.buildingSlots);
            // Auto-open building selection bar
            WandscapePanelState.openBuildingBar();
            mc.player.displayClientMessage(
                    Component.literal("[Build] §a" + packet.buildingSlots.size()
                            + " buildings available — double-click to select"),
                    true);
        } else {
            ProjectionClientState.exitProjection();
            WandscapePanelState.closeBuildingBar();
            if (WandscapePanelState.isPanelOpen()) {
                WandscapePanelState.setSubMode(WandscapePanelState.SubMode.NONE);
                WandscapePanelState.closeBuildingBar();
            }
            mc.player.displayClientMessage(
                    Component.literal("[Projection] §eCannot enter projection mode"),
                    true);
        }
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, ProjectionEnterResponsePacket pkt) {
        buf.writeBoolean(pkt.granted);
        buf.writeVarInt(pkt.buildingSlots.size());
        for (BuildingSlot slot : pkt.buildingSlots) {
            buf.writeUtf(slot.id());
            buf.writeUtf(slot.displayName());
            buf.writeUtf(slot.category());
        }
        buf.writeBlockPos(pkt.bodyAnchor);
    }

    static ProjectionEnterResponsePacket read(RegistryFriendlyByteBuf buf) {
        boolean granted = buf.readBoolean();
        int count = buf.readVarInt();
        List<BuildingSlot> slots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            slots.add(new BuildingSlot(buf.readUtf(), buf.readUtf(), buf.readUtf()));
        }
        BlockPos anchor = buf.readBlockPos();
        return new ProjectionEnterResponsePacket(granted, slots, anchor);
    }
}
