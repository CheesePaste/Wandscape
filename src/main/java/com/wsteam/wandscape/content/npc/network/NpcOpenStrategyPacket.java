package com.wsteam.wandscape.content.npc.network;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.EntityId;

import com.wsteam.wandscape.content.npc.NpcStrategyMenu;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→server: open the cast-strategy container menu for an NPC (the NPC screen
 * button; the server resolves the entity and opens {@link NpcStrategyMenu}).
 */
public record NpcOpenStrategyPacket(int entityId) implements CustomPacketPayload {

    private static final String TAG = "NpcOpenStrategyPacket";

    public static final Type<NpcOpenStrategyPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "npc_open_strategy"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcOpenStrategyPacket> STREAM_CODEC =
            StreamCodec.of(NpcOpenStrategyPacket::write, NpcOpenStrategyPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void write(RegistryFriendlyByteBuf buf, NpcOpenStrategyPacket pkt) {
        buf.writeInt(pkt.entityId);
    }

    static NpcOpenStrategyPacket read(RegistryFriendlyByteBuf buf) {
        return new NpcOpenStrategyPacket(buf.readInt());
    }

    /** Server handler. */
    public static void handleServer(NpcOpenStrategyPacket pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        var level = sp.serverLevel();
        if (!(level.getEntity(pkt.entityId()) instanceof WandscapeNpc npc)) {
            Log.warn(TAG, "Strategy target entity {} is not a WandscapeNpc", pkt.entityId());
            return;
        }
        // 完全平行隔离：只能打开自己小镇法师的策略栏。
        if (npc.colonyId != null
                && !com.wsteam.wandscape.content.npc.internal.EntityComponentBridge.PLACEHOLDER_COLONY.equals(npc.colonyId)
                && !com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.isOwn(npc.colonyId, sp)) {
            com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.deny(sp, "法师");
            return;
        }
        sp.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new NpcStrategyMenu(id, inv, npc),
                Component.literal("Cast Strategy")));
        // 下一 tick 补发数据（客户端屏幕就绪后刷新预设等）
        sp.serverLevel().getServer().execute(() -> {
            if (!npc.isRemoved() && sp.containerMenu instanceof NpcStrategyMenu) {
                PacketDistributor.sendToPlayer(sp, NpcDataPacket.from(npc));
            }
        });
    }
}