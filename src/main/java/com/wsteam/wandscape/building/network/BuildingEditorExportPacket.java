package com.wsteam.wandscape.building.network;

import com.wsteam.wandscape.building.editor.BuildingEditorExportService;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Client→Server: Player requests to export the building JSON.
 * Carries the complete BuildingConfig JSON string built by the client,
 * and whether to overwrite an existing file.
 */
public record BuildingEditorExportPacket(String buildingJson, boolean overwrite) implements CustomPacketPayload {

    private static final String TAG = "BuildingEditorExportPacket";

    public static final Type<BuildingEditorExportPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "build_editor_export"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingEditorExportPacket> STREAM_CODEC =
            StreamCodec.of(BuildingEditorExportPacket::write, BuildingEditorExportPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    public static void handleServer(BuildingEditorExportPacket packet, ServerPlayer player) {
        Log.info(TAG, "[BuildEditor] Export request from {} (overwrite={})",
                player.getGameProfile().getName(), packet.overwrite);

        BuildingEditorExportService.ExportResult result =
                BuildingEditorExportService.export(packet.buildingJson, packet.overwrite);

        var response = new BuildingEditorExportResultPacket(
                result.success(), result.message(), result.warnings());
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, response);
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, BuildingEditorExportPacket pkt) {
        buf.writeUtf(pkt.buildingJson != null ? pkt.buildingJson : "");
        buf.writeBoolean(pkt.overwrite);
    }

    static BuildingEditorExportPacket read(RegistryFriendlyByteBuf buf) {
        return new BuildingEditorExportPacket(buf.readUtf(), buf.readBoolean());
    }
}
