package com.wsteam.wandscape.blueprint.editor;

import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Save a blueprint definition as a JSON file.
 *
 * <p>Server handler writes the JSON to {@code <world_save>/wandscape/blueprints/<id>.json}.
 * The file can be reloaded on next server start if the blueprint pack path is included.
 */
public record BlueprintSavePacket(String blueprintId, String json)
        implements CustomPacketPayload {

    private static final String TAG = "BlueprintSavePacket";

    public static final Type<BlueprintSavePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "blueprint_save"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlueprintSavePacket> STREAM_CODEC =
            StreamCodec.of(BlueprintSavePacket::write, BlueprintSavePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    public static void handleServer(BlueprintSavePacket packet, ServerPlayer player) {
        String id = packet.blueprintId();
        String json = packet.json();

        if (id == null || id.isEmpty()) {
            Log.warn(TAG, "Rejected save: empty blueprint ID from player {}", player.getGameProfile().getName());
            return;
        }
        if (json == null || json.isBlank()) {
            Log.warn(TAG, "Rejected save '{}': empty JSON from player {}", id, player.getGameProfile().getName());
            return;
        }

        // Write to <world_save>/wandscape/blueprints/<id>.json
        Path worldDir = player.getServer().getWorldPath(LevelResource.ROOT);
        Path blueprintsDir = worldDir.resolve("wandscape").resolve("blueprints");

        try {
            Files.createDirectories(blueprintsDir);
            Path targetFile = blueprintsDir.resolve(id.replace(':', '_') + ".json");
            Files.writeString(targetFile, json);
            Log.info(TAG, "Blueprint '{}' saved to {} ({} bytes) by player {}",
                    id, targetFile, json.length(), player.getGameProfile().getName());
        } catch (IOException e) {
            Log.warn(TAG, "Failed to save blueprint '{}': {}", id, e.getMessage());
        }
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, BlueprintSavePacket pkt) {
        buf.writeUtf(pkt.blueprintId);
        buf.writeUtf(pkt.json);
    }

    static BlueprintSavePacket read(RegistryFriendlyByteBuf buf) {
        return new BlueprintSavePacket(buf.readUtf(), buf.readUtf());
    }
}
