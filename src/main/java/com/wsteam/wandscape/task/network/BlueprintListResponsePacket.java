package com.wsteam.wandscape.task.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.shared.data.BlueprintInfo;
import com.wsteam.wandscape.shared.data.ParamTypeInfo;
import com.wsteam.wandscape.shared.ui.task.TaskEditorClientState;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→Client packet carrying the full list of available blueprints.
 * Sent in response to {@link TaskEditorOpenPacket}.
 */
public record BlueprintListResponsePacket(List<BlueprintInfo> blueprints)
        implements CustomPacketPayload {

    public static final Type<BlueprintListResponsePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "task_blueprint_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlueprintListResponsePacket> STREAM_CODEC =
            StreamCodec.of(BlueprintListResponsePacket::write, BlueprintListResponsePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Build from a list of BlueprintInfo. */
    public static BlueprintListResponsePacket from(List<BlueprintInfo> infos) {
        return new BlueprintListResponsePacket(
                infos != null ? List.copyOf(infos) : List.of());
    }

    /** Handle on client: update the task editor state. */
    public static void handleClient(BlueprintListResponsePacket packet) {
        TaskEditorClientState.setBlueprints(packet.blueprints());
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, BlueprintListResponsePacket pkt) {
        List<BlueprintInfo> list = pkt.blueprints();
        buf.writeVarInt(list.size());
        for (BlueprintInfo info : list) {
            buf.writeUtf(info.id());
            buf.writeUtf(info.displayName());
            buf.writeUtf(info.description());
            Map<String, ParamTypeInfo> params = info.params();
            buf.writeVarInt(params.size());
            for (var entry : params.entrySet()) {
                buf.writeUtf(entry.getKey());
                buf.writeUtf(entry.getValue().name());
            }
        }
    }

    static BlueprintListResponsePacket read(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<BlueprintInfo> infos = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = buf.readUtf();
            String displayName = buf.readUtf();
            String description = buf.readUtf();
            int paramCount = buf.readVarInt();
            Map<String, ParamTypeInfo> params = new java.util.LinkedHashMap<>();
            for (int j = 0; j < paramCount; j++) {
                String key = buf.readUtf();
                ParamTypeInfo type = ParamTypeInfo.valueOf(buf.readUtf());
                params.put(key, type);
            }
            infos.add(new BlueprintInfo(id, displayName, description, params));
        }
        return new BlueprintListResponsePacket(infos);
    }
}
