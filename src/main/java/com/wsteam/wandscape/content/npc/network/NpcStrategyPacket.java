package com.wsteam.wandscape.content.npc.network;
import com.wsteam.wandscape.content.task.types.EntityId;

import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Client→server packet: player edits an NPC's equipped magic loadout + cast preset in the strategy screen.
 *
 * <p>{@code equipped} 为扁平 magicId 列表（分类固定序 × 类内槽位序）；服务端按每个魔法真实分类
 * 装桶校验（未知/ALTAR/SPECIAL 丢、每类 ≤3、去重）——客户端立场不获信任，非法请求就地修正而非拒绝。
 * {@code consumeSlot} ≥ 0 表示本次改动包含「从玩家背包该槽消耗一张卷轴」的装备动作：仅当该卷轴
 * 绑定的魔法在本次改动中**新增装备**成功才实际扣一张（防状态重发反复扣）。服务端改完回发
 * {@link NpcDataPacket} 刷新屏幕为权威状态。
 */
public record NpcStrategyPacket(int entityId, String preset, List<String> equipped, int consumeSlot)
        implements CustomPacketPayload {

    /** 无装备动作（纯弹 preset / 卸载 / 排序）时 consumeSlot = -1。 */
    public static final int NO_CONSUME = -1;

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
        buf.writeVarInt(pkt.equipped.size());
        for (String id : pkt.equipped) {
            buf.writeUtf(id);
        }
        buf.writeInt(pkt.consumeSlot);
    }

    static NpcStrategyPacket read(RegistryFriendlyByteBuf buf) {
        int entityId = buf.readInt();
        String preset = buf.readUtf();
        int size = buf.readVarInt();
        List<String> equipped = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            equipped.add(buf.readUtf());
        }
        int consumeSlot = buf.readInt();
        return new NpcStrategyPacket(entityId, preset, equipped, consumeSlot);
    }

    // ── Server handler ──

    private static final String TAG = "NpcStrategyPacket";

    /** 策略装备由 {@code NpcStrategyMenu}（真实卷轴槽）管理；此包仅切换施法预设。 */
    public static void handleServer(NpcStrategyPacket packet, ServerPlayer player) {
        if (player == null || player.isRemoved()) return;

        var level = player.serverLevel();
        if (!(level.getEntity(packet.entityId()) instanceof WandscapeNpc npc)) {
            Log.warn(TAG, "Strategy target entity {} is not a WandscapeNpc", packet.entityId());
            return;
        }
        // 预设切换经 MagicApi.setEquippedAndStrategy（校验一致的写入，装备态不变但重验）：
        // 取代直写 castStrategy，保证与 API 一致。
        com.wsteam.wandscape.api.WandscapeApis.getMagicApi()
                .setEquippedAndStrategy(npc.getUUID(), packet.preset(), npc.equippedMagic.flattenedQualified());
        Log.info(TAG, "NPC {} preset={}", npc.getUUID().toString().substring(0, 8), packet.preset());

        // 刷新策略屏 / 信息屏（权威状态）
        PacketDistributor.sendToPlayer(player, NpcDataPacket.from(npc));
    }
}