package com.wsteam.wandscape.npc.network;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Client→server packet: player sets an NPC's cast strategy in the strategy screen.
 *
 * <p>{@code preset} 为 {@code CastStrategyComponent.Preset} 大写名；非 {@code CUSTOM} 时服务端
 * 忽略 {@code priority}（按预设重算），{@code CUSTOM} 时用 {@code priority} 作显式优先级。
 * 服务端改完回发 {@link NpcDataPacket} 刷新屏幕。
 */
public record NpcStrategyPacket(int entityId, String preset, List<String> priority)
        implements CustomPacketPayload {

    public static final Type<NpcStrategyPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "npc_strategy"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcStrategyPacket> STREAM_CODEC =
            StreamCodec.of(NpcStrategyPacket::write, NpcStrategyPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, NpcStrategyPacket pkt) {
        buf.writeInt(pkt.entityId);
        buf.writeUtf(pkt.preset);
        buf.writeVarInt(pkt.priority.size());
        for (String id : pkt.priority) {
            buf.writeUtf(id);
        }
    }

    static NpcStrategyPacket read(RegistryFriendlyByteBuf buf) {
        int entityId = buf.readInt();
        String preset = buf.readUtf();
        int size = buf.readVarInt();
        List<String> priority = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            priority.add(buf.readUtf());
        }
        return new NpcStrategyPacket(entityId, preset, priority);
    }

    // ── Server handler ──

    private static final String TAG = "NpcStrategyPacket";

    public static void handleServer(NpcStrategyPacket packet, ServerPlayer player) {
        if (player == null || player.isRemoved()) return;

        var level = player.serverLevel();
        var entity = level.getEntity(packet.entityId());
        if (!(entity instanceof WandscapeNpc npc)) {
            Log.warn(TAG, "Strategy target entity {} is not a WandscapeNpc", packet.entityId());
            return;
        }

        var api = WandscapeApis.getSpellcastingApiSilently();
        if (api == null) {
            Log.warn(TAG, "SpellcastingApi not loaded — strategy change dropped");
            return;
        }
        api.setStrategy(npc.getUUID(), packet.preset(), packet.priority());
        Log.info(TAG, "NPC {} strategy preset={} priority={}",
                npc.getUUID().toString().substring(0, 8), packet.preset(), packet.priority());

        // 刷新策略屏 / 信息屏
        PacketDistributor.sendToPlayer(player, NpcDataPacket.from(npc));
    }
}
