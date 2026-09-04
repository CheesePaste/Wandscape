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

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Client→server packet: toggle a per-NPC behavior mode from the NPC info screen.
 *
 * <p>{@code flag} 取值：{@link #FLAG_PEACE}（和平模式，不攻击任何生物）、
 * {@link #FLAG_FOLLOW}（跟随模式，目标玩家距离 >5 格时走向玩家）。服务端应用后
 * 回发 {@link NpcDataPacket}，刷新打开中的面板按钮文字（与改名/换装一致）。
 */
public record NpcTogglePacket(int entityId, String flag, boolean enabled) implements CustomPacketPayload {

    public static final String FLAG_PEACE = "peace";
    public static final String FLAG_FOLLOW = "follow";
    public static final String FLAG_PICKUP = "pickup";
    public static final String FLAG_AUTO_PICKUP = "auto_pickup";

    public static final Type<NpcTogglePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "npc_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcTogglePacket> STREAM_CODEC =
            StreamCodec.of(NpcTogglePacket::write, NpcTogglePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, NpcTogglePacket pkt) {
        buf.writeInt(pkt.entityId);
        buf.writeUtf(pkt.flag != null ? pkt.flag : "");
        buf.writeBoolean(pkt.enabled);
    }

    static NpcTogglePacket read(RegistryFriendlyByteBuf buf) {
        return new NpcTogglePacket(buf.readInt(), buf.readUtf(), buf.readBoolean());
    }

    // ── Server handler ──

    private static final String TAG = "NpcTogglePacket";

    public static void handleServer(NpcTogglePacket packet, ServerPlayer player) {
        if (player == null || player.isRemoved()) return;

        var level = player.serverLevel();
        var entity = level.getEntity(packet.entityId());
        if (!(entity instanceof WandscapeNpc npc)) {
            Log.warn(TAG, "Toggle target entity {} is not a WandscapeNpc", packet.entityId());
            return;
        }
        // 完全平行隔离：只能操作自己小镇的法师。
        if (!com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.isOwn(npc.colonyId, player)) {
            com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.deny(player, "法师");
            return;
        }

        boolean enabled = packet.enabled();
        switch (packet.flag()) {
            case FLAG_PEACE -> {
                npc.setPeaceMode(enabled);
                if (enabled) {
                    // 清除仇恨，避免解除和平后立刻寻仇；战斗执行器下一轮检测到 peace 会停手，
                    // 活跃光束的伤害由 MagicBeamEntity 的 isPeaceMode 门控立即停止。
                    npc.clearHatedAttacker();
                }
            }
            case FLAG_FOLLOW -> {
                if (enabled) {
                    npc.setFollowMode(true);
                    npc.setFollowerUuid(player.getUUID());
                } else {
                    npc.setFollowMode(false);
                    npc.setFollowerUuid(null);
                }
            }
            case FLAG_PICKUP -> {
                var npcApi = com.wsteam.wandscape.api.WandscapeApis.getNpcApiSilently();
                if (npcApi != null) {
                    npcApi.setPickupEnabled(npc.getUUID(), enabled);
                } else {
                    npc.setPickupItems(enabled);
                    if (!enabled) npc.setAutoPickupItems(false);
                }
            }
            case FLAG_AUTO_PICKUP -> {
                var npcApi = com.wsteam.wandscape.api.WandscapeApis.getNpcApiSilently();
                if (npcApi != null) {
                    npcApi.setAutoPickupEnabled(npc.getUUID(), enabled);
                } else {
                    npc.setAutoPickupItems(enabled);
                    if (enabled) npc.setPickupItems(true);
                }
            }
            default -> {
                Log.warn(TAG, "Unknown toggle flag '{}' from {}", packet.flag(), player.getName().getString());
                return;
            }
        }

        // 回发最新数据，刷新打开中的 NPC 面板按钮状态
        PacketDistributor.sendToPlayer(player, NpcDataPacket.from(npc));
        Log.info(TAG, "NPC {} {} → {} (by {})", npc.getUUID().toString().substring(0, 8),
                packet.flag(), enabled, player.getName().getString());
    }
}
