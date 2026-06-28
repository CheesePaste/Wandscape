package com.wsteam.wandscape.building.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Server→Client: Result of building JSON export.
 * Carries success/failure and a message list.
 */
public record BuildingEditorExportResultPacket(
        boolean success,
        String message,
        List<String> warnings
) implements CustomPacketPayload {

    private static final String TAG = "BuildingEditorExportResultPacket";

    public static final Type<BuildingEditorExportResultPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "build_editor_export_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingEditorExportResultPacket> STREAM_CODEC =
            StreamCodec.of(BuildingEditorExportResultPacket::write, BuildingEditorExportResultPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Client handler ──

    public static void handleClient(BuildingEditorExportResultPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        String prefix = packet.success ? "§a" : "§c";
        mc.player.displayClientMessage(
                Component.literal("[BuildEditor] " + prefix + (packet.message != null ? packet.message : "")),
                false);

        if (packet.warnings != null) {
            for (String warning : packet.warnings) {
                mc.player.displayClientMessage(
                        Component.literal("[BuildEditor] §e⚠ " + warning),
                        false);
            }
        }

        Log.info(TAG, "[BuildEditor] Export result: {} — {}", packet.success, packet.message);
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, BuildingEditorExportResultPacket pkt) {
        buf.writeBoolean(pkt.success);
        buf.writeUtf(pkt.message != null ? pkt.message : "");
        List<String> w = pkt.warnings != null ? pkt.warnings : List.of();
        buf.writeVarInt(w.size());
        for (String s : w) {
            buf.writeUtf(s);
        }
    }

    static BuildingEditorExportResultPacket read(RegistryFriendlyByteBuf buf) {
        boolean success = buf.readBoolean();
        String message = buf.readUtf();
        int count = buf.readVarInt();
        List<String> warnings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            warnings.add(buf.readUtf());
        }
        return new BuildingEditorExportResultPacket(success,
                message.isEmpty() ? null : message,
                warnings);
    }
}
