package com.wsteam.wandscape.building.network;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.editor.BuildingEditorNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Player requests entry into building editor mode.
 * Optionally carries a building_id to edit an existing config.
 *
 * Server validates permissions, loads existing config if id is provided,
 * and replies with {@link BuildingEditorEnterResponsePacket}.
 */
public record BuildingEditorEnterPacket(String buildingId) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<BuildingEditorEnterPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "build_editor_enter"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingEditorEnterPacket> STREAM_CODEC =
            StreamCodec.of(BuildingEditorEnterPacket::write, BuildingEditorEnterPacket::read);

    /** Create for new building mode. */
    public static BuildingEditorEnterPacket createNew() {
        return new BuildingEditorEnterPacket("");
    }

    /** Create for editing an existing building. */
    public static BuildingEditorEnterPacket edit(String id) {
        return new BuildingEditorEnterPacket(id);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    public static void handleServer(BuildingEditorEnterPacket packet, ServerPlayer player) {
        String error = BuildingEditorNetwork.validateEntry(player);
        if (error != null) {
            player.displayClientMessage(Component.literal("[BuildEditor] " + error), false);
            return;
        }

        // Already editing? Toggle off.
        if (BuildingEditorNetwork.isEditing(player)) {
            BuildingEditorNetwork.removeEditing(player);
            player.displayClientMessage(Component.literal("[BuildEditor] Exited editor mode"), false);
            var deny = BuildingEditorEnterResponsePacket.deny("Exited editor mode");
            sendResponse(player, deny);
            return;
        }

        // Load existing building if id provided
        String buildingId = packet.buildingId;
        String existingJson = null;
        BlockPos worldAnchor = player.blockPosition();

        if (buildingId != null && !buildingId.isEmpty()) {
            BuildingConfig config = BuildingEditorNetwork.loadBuildingConfig(buildingId);
            if (config == null) {
                var deny = BuildingEditorEnterResponsePacket.deny(
                        "Building '" + buildingId + "' not found. Use /wandscape build edit (no args) to create new.");
                sendResponse(player, deny);
                return;
            }
            existingJson = toJsonString(config);
            LOGGER.info("[BuildEditor] Loaded existing building '{}' for editing by {}",
                    buildingId, player.getGameProfile().getName());
        }

        // Grant entry
        BuildingEditorNetwork.addEditing(player);

        var response = new BuildingEditorEnterResponsePacket(
                true, null, buildingId, existingJson, worldAnchor);
        sendResponse(player, response);

        LOGGER.info("[BuildEditor] Player {} entered build editor. buildingId={}, bodyAt={}",
                player.getGameProfile().getName(),
                buildingId != null && !buildingId.isEmpty() ? buildingId : "(new)",
                worldAnchor);
    }

    private static void sendResponse(ServerPlayer player, BuildingEditorEnterResponsePacket response) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, response);
    }

    /** Quick JSON string conversion using the loader's Gson. */
    private static String toJsonString(BuildingConfig config) {
        try {
            var gson = new com.google.gson.GsonBuilder()
                    .setPrettyPrinting()
                    .registerTypeAdapter(BlockOffset.class, new BlockOffset.Deserializer())
                    .create();
            // Serialize building config via Gson's default reflective adapter
            // We use a simple approach: serialize the record fields manually via a helper
            return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(config);
        } catch (Exception e) {
            LOGGER.error("[BuildEditor] Failed to serialize existing config", e);
            return null;
        }
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, BuildingEditorEnterPacket pkt) {
        buf.writeUtf(pkt.buildingId != null ? pkt.buildingId : "");
    }

    static BuildingEditorEnterPacket read(RegistryFriendlyByteBuf buf) {
        return new BuildingEditorEnterPacket(buf.readUtf());
    }
}
