package com.wsteam.wandscape.task.network;

import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.core.task.TaskRequest;
import com.wsteam.wandscape.engine.WandscapeEngine;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server packet requesting to create a new task from a blueprint.
 */
public record TaskCreatePacket(
        String blueprintId,
        Map<String, String> params,
        int priority
) implements CustomPacketPayload {

    public static final Type<TaskCreatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "task_create"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TaskCreatePacket> STREAM_CODEC =
            StreamCodec.of(TaskCreatePacket::write, TaskCreatePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handle on server: validate and publish the task. */
    public static void handleServer(TaskCreatePacket packet, ServerPlayer player) {
        var playerSource = WandscapeEngine.getPlayerManualSource();
        if (playerSource == null) {
            return;
        }

        try {
            // Convert String params → JsonElement for the API
            Map<String, com.google.gson.JsonElement> jsonParams =
                    new java.util.LinkedHashMap<>();
            if (packet.params() != null) {
                for (var entry : packet.params().entrySet()) {
                    jsonParams.put(entry.getKey(), parseParamValue(entry.getValue()));
                }
            }

            long taskId = playerSource.publish(new TaskRequest(packet.blueprintId(), jsonParams, packet.priority()));

            player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal(
                            "[Wandscape] Task created: #" + new UUID(taskId, 0)
                                    + " '" + packet.blueprintId() + "'"), true);

        } catch (Exception e) {
            player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal(
                            "[Wandscape] Failed to create task: " + e.getMessage()));
        }
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, TaskCreatePacket pkt) {
        buf.writeUtf(pkt.blueprintId());
        Map<String, String> params = pkt.params();
        buf.writeVarInt(params.size());
        for (var entry : params.entrySet()) {
            buf.writeUtf(entry.getKey());
            buf.writeUtf(entry.getValue());
        }
        buf.writeVarInt(pkt.priority());
    }

    static TaskCreatePacket read(RegistryFriendlyByteBuf buf) {
        String blueprintId = buf.readUtf();
        int paramCount = buf.readVarInt();
        Map<String, String> params = new java.util.LinkedHashMap<>();
        for (int i = 0; i < paramCount; i++) {
            String key = buf.readUtf();
            String value = buf.readUtf();
            params.put(key, value);
        }
        int priority = buf.readVarInt();
        return new TaskCreatePacket(blueprintId, params, priority);
    }

    // ── Value parser (mirrors PublishBlueprintCommand logic) ──

    private static com.google.gson.JsonElement parseParamValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return new com.google.gson.JsonPrimitive("");
        }
        raw = raw.trim();
        if (raw.startsWith("[[") && raw.endsWith("]]")) {
            return parsePosList(raw);
        }
        if (raw.startsWith("[") && raw.endsWith("]")) {
            return parsePos(raw);
        }
        if (raw.startsWith("{")) {
            return new com.google.gson.JsonPrimitive(raw);
        }
        try {
            return new com.google.gson.JsonPrimitive(Integer.parseInt(raw));
        } catch (NumberFormatException ignored) {
            return new com.google.gson.JsonPrimitive(raw);
        }
    }

    private static com.google.gson.JsonArray parsePos(String raw) {
        String inner = raw.substring(1, raw.length() - 1);
        String[] parts = inner.split(",");
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (String p : parts) {
            arr.add(Integer.parseInt(p.trim()));
        }
        return arr;
    }

    private static com.google.gson.JsonArray parsePosList(String raw) {
        String inner = raw.substring(1, raw.length() - 1);
        com.google.gson.JsonArray result = new com.google.gson.JsonArray();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : inner.toCharArray()) {
            if (c == '[') { depth++; if (depth == 1) continue; }
            if (c == ']') {
                depth--;
                if (depth == 0) {
                    result.add(parsePos("[" + current + "]"));
                    current.setLength(0);
                    continue;
                }
            }
            if (depth > 0) current.append(c);
        }
        return result;
    }
}
