package com.wsteam.wandscape.shared.network;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→Client: Syncs all building positions and types for interaction area overlay
 * when the Wandscape panel is opened.
 */
public record BuildingAreaSyncPacket(List<BuildingEntry> buildings) implements CustomPacketPayload {

    private static final String TAG = "BuildingAreaSync";

    public static final Type<BuildingAreaSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "building_area_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingAreaSyncPacket> STREAM_CODEC =
            StreamCodec.of(BuildingAreaSyncPacket::write, BuildingAreaSyncPacket::read);

    /** Client-side cache, updated each time the panel opens. */
    private static volatile List<BuildingEntry> cached = List.of();

    public static List<BuildingEntry> getCached() {
        return cached;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Client handler ──

    public static void handleClient(BuildingAreaSyncPacket packet) {
        cached = packet.buildings;
        Log.info(TAG, "[Area] Cached {} building areas", packet.buildings.size());
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, BuildingAreaSyncPacket pkt) {
        buf.writeVarInt(pkt.buildings.size());
        for (BuildingEntry entry : pkt.buildings) {
            buf.writeBlockPos(entry.anchor());
            buf.writeUtf(entry.buildingTypeId());
        }
    }

    static BuildingAreaSyncPacket read(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<BuildingEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos anchor = buf.readBlockPos();
            String typeId = buf.readUtf();
            entries.add(new BuildingEntry(anchor, typeId));
        }
        return new BuildingAreaSyncPacket(entries);
    }

    // ── Data ──

    public record BuildingEntry(BlockPos anchor, String buildingTypeId) {}
}
