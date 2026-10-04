package com.wsteam.wandscape.content.colony.network;

import com.wsteam.wandscape.content.colony.roster.ColonyRole;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.network.FriendlyByteBuf;
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
 * Server→Client: 「我的小镇」列表——收件人参与的每一座镇（colonyId / 名字 / 等级 / 我在该镇的档位）。
 *
 * <p>一人可同时在多座镇（花名册模型），所以这是**列表**而不是单值。侧边栏用它渲染小镇列表；
 * 点击切换只改客户端选中项（{@link ColonyPanelClientState#setSelectedColony}），列表本身不因此变化，
 * 故不需要来回同步。
 */
public record ColonyListSyncPacket(List<Entry> colonies) implements CustomPacketPayload {

    /** 列表一行。 */
    public record Entry(UUID colonyId, String name, int level, ColonyRole myRole) {}

    private static final String TAG = "ColonyListSyncPacket";

    public static final Type<ColonyListSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "colony_list_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ColonyListSyncPacket> STREAM_CODEC =
            StreamCodec.of(ColonyListSyncPacket::write, ColonyListSyncPacket::read);

    public ColonyListSyncPacket {
        List<Entry> sanitized = new ArrayList<>();
        if (colonies != null) {
            for (Entry entry : colonies) {
                if (entry != null && entry.colonyId() != null) sanitized.add(entry);
            }
        }
        colonies = List.copyOf(sanitized);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleClient(ColonyListSyncPacket packet) {
        try {
            ColonyPanelClientState.applyList(packet);
        } catch (Throwable t) {
            Log.warn(TAG, "Failed to apply colony list sync: {}", t.toString());
        }
    }

    static void write(RegistryFriendlyByteBuf buf, ColonyListSyncPacket pkt) {
        buf.writeCollection(pkt.colonies, (b, entry) -> writeEntry(b, entry));
    }

    static ColonyListSyncPacket read(RegistryFriendlyByteBuf buf) {
        return new ColonyListSyncPacket(buf.readList(ColonyListSyncPacket::readEntry));
    }

    private static void writeEntry(FriendlyByteBuf buf, Entry entry) {
        buf.writeUUID(entry.colonyId());
        buf.writeUtf(entry.name() != null ? entry.name() : "");
        buf.writeVarInt(entry.level());
        ColonyWireCodecs.writeRole(buf, entry.myRole());
    }

    private static Entry readEntry(FriendlyByteBuf buf) {
        UUID colonyId = buf.readUUID();
        String name = buf.readUtf();
        int level = buf.readVarInt();
        return new Entry(colonyId, name, level, ColonyWireCodecs.readRole(buf));
    }
}
