package com.wsteam.wandscape.foundation.ui.settings.network;

import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server-&gt;Client：把服务端当前**全部**通用配置的权威值一次性下发给刚登录的客户端。
 *
 * <p>为什么需要它：{@code Config} 是 {@code ModConfig.Type.COMMON}，专用服务器上客户端与服务器
 * 各持一份 {@code wandscape-common.toml}。原有的 {@link ConfigSyncPacket} 只在「有人改值」时下发，
 * 所以玩家一进服，设置面板显示的是**自己本地那份**值（改容量基线因此可能算错），且整场会话都在
 * 用旧数字。
 *
 * <p>为什么是批量单包：{@link ConfigSyncPacket} 是单键包，且客户端每收一个就
 * {@code Config.SPEC.save()} 写一次盘；逐个下发几十个键会写几十次盘。
 *
 * <p>客户端只改内存、**不写盘**——这是服务端权威值的会话内镜像，写盘会把服务端值覆盖掉玩家
 * 自己的单机配置（与 {@link ConfigSyncPacket} 的写盘行为刻意不同）。
 */
public record ConfigSnapshotPacket(List<String> paths, List<String> values) implements CustomPacketPayload {

    private static final String TAG = "ConfigSnapshotPacket";

    public static final Type<ConfigSnapshotPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "config_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigSnapshotPacket> STREAM_CODEC =
            StreamCodec.of(ConfigSnapshotPacket::write, ConfigSnapshotPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void write(RegistryFriendlyByteBuf buf, ConfigSnapshotPacket pkt) {
        int n = Math.min(pkt.paths.size(), pkt.values.size());
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeUtf(pkt.paths.get(i));
            buf.writeUtf(pkt.values.get(i));
        }
    }

    static ConfigSnapshotPacket read(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> paths = new ArrayList<>(n);
        List<String> values = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            paths.add(buf.readUtf());
            values.add(buf.readUtf());
        }
        return new ConfigSnapshotPacket(paths, values);
    }

    public static void handleClient(ConfigSnapshotPacket packet) {
        int n = Math.min(packet.paths.size(), packet.values.size());
        int applied = 0;
        for (int i = 0; i < n; i++) {
            if (ConfigUpdatePacket.applyConfig(packet.paths.get(i), packet.values.get(i))) applied++;
        }
        // 刻意不落盘：见类注释。要让本地文件也跟上，得由玩家自己在该项上做一次修改。
        Log.debug(LogCategory.UI, "config_sync",
                "{} 服务端配置快照已应用 {}/{} 项", TAG, applied, n);
    }
}
