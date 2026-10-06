package com.wsteam.wandscape.content.building.network;

import com.wsteam.wandscape.content.building.internal.BuildingDelegation;
import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.foundation.networking.ClientPayloadDispatcher;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→Client：某建筑当前的委派法师 + 可委派法师列表，供「委派」对话打开。
 *
 * <p>行文案（空闲/执行中/已委派某建筑）在客户端按 {@link Row#state()} 与
 * {@link Row#delegatedBuildingName()} 本地化拼装——服务端不下发成品句子。
 */
public record BuildingDelegateDataPacket(
        UUID buildingId,
        @Nullable UUID currentMageUuid,
        String currentMageName,
        List<Row> candidates
) implements CustomPacketPayload {

    public static final Type<BuildingDelegateDataPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "building_delegate_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingDelegateDataPacket> STREAM_CODEC =
            StreamCodec.of(BuildingDelegateDataPacket::write, BuildingDelegateDataPacket::read);

    /**
     * 一名可委派法师。
     *
     * @param state {@code "IDLE"} / {@code "BUSY"} / {@code "FOLLOWING"}
     */
    public record Row(UUID mageUuid, String name, String state,
                      @Nullable UUID delegatedBuilding, String delegatedBuildingName) {}

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(BuildingDelegateDataPacket packet) {
        ClientPayloadDispatcher.dispatch(packet);
    }

    /** 由服务端建筑状态组装一份快照。 */
    public static BuildingDelegateDataPacket of(BuildingSavedData sd, BuildingState state) {
        UUID current = sd.getDelegatedMage(state.getBuildingId());
        String currentName = BuildingDelegation.delegatedMageName(sd, state.getBuildingId());
        List<Row> rows = new ArrayList<>();
        for (BuildingDelegation.Candidate c : BuildingDelegation.candidates(sd, state)) {
            rows.add(new Row(c.mageUuid(), c.name(), c.state(), c.delegatedBuilding(), c.delegatedBuildingName()));
        }
        return new BuildingDelegateDataPacket(state.getBuildingId(), current,
                currentName != null ? currentName : "", List.copyOf(rows));
    }

    static void write(RegistryFriendlyByteBuf buf, BuildingDelegateDataPacket pkt) {
        buf.writeUUID(pkt.buildingId);
        if (pkt.currentMageUuid != null) {
            buf.writeBoolean(true);
            buf.writeUUID(pkt.currentMageUuid);
        } else {
            buf.writeBoolean(false);
        }
        buf.writeUtf(pkt.currentMageName != null ? pkt.currentMageName : "", 64);
        int n = pkt.candidates != null ? pkt.candidates.size() : 0;
        buf.writeVarInt(n);
        for (Row row : pkt.candidates) {
            buf.writeUUID(row.mageUuid());
            buf.writeUtf(row.name() != null ? row.name() : "", 64);
            buf.writeUtf(row.state() != null ? row.state() : "IDLE", 16);
            if (row.delegatedBuilding() != null) {
                buf.writeBoolean(true);
                buf.writeUUID(row.delegatedBuilding());
            } else {
                buf.writeBoolean(false);
            }
            buf.writeUtf(row.delegatedBuildingName() != null ? row.delegatedBuildingName() : "", 128);
        }
    }

    static BuildingDelegateDataPacket read(RegistryFriendlyByteBuf buf) {
        UUID buildingId = buf.readUUID();
        UUID currentMageUuid = buf.readBoolean() ? buf.readUUID() : null;
        String currentMageName = buf.readUtf(64);
        int n = buf.readVarInt();
        List<Row> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UUID mageUuid = buf.readUUID();
            String name = buf.readUtf(64);
            String state = buf.readUtf(16);
            UUID delegatedBuilding = buf.readBoolean() ? buf.readUUID() : null;
            String delegatedBuildingName = buf.readUtf(128);
            rows.add(new Row(mageUuid, name, state, delegatedBuilding, delegatedBuildingName));
        }
        return new BuildingDelegateDataPacket(buildingId, currentMageUuid, currentMageName, List.copyOf(rows));
    }
}
