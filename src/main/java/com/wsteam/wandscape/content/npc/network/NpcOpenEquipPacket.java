package com.wsteam.wandscape.content.npc.network;

import com.wsteam.wandscape.content.npc.NpcMenu;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;
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
 * Client→server: re-open the mage equipment container (NpcScreen) for an NPC. Used as the
 * "back" button from the Curios mage trinket screen; server resolves the entity and opens
 * {@link NpcMenu} exactly like right-clicking the mage.
 */
public record NpcOpenEquipPacket(int entityId) implements CustomPacketPayload {

    private static final String TAG = "NpcOpenEquipPacket";

    public static final Type<NpcOpenEquipPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "npc_open_equip"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcOpenEquipPacket> STREAM_CODEC =
            StreamCodec.of(NpcOpenEquipPacket::write, NpcOpenEquipPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void write(RegistryFriendlyByteBuf buf, NpcOpenEquipPacket pkt) {
        buf.writeInt(pkt.entityId);
    }

    static NpcOpenEquipPacket read(RegistryFriendlyByteBuf buf) {
        return new NpcOpenEquipPacket(buf.readInt());
    }

    /** Server handler. */
    public static void handleServer(NpcOpenEquipPacket pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        var level = sp.serverLevel();
        if (!(level.getEntity(pkt.entityId()) instanceof WandscapeNpc npc) || npc.isRemoved()) {
            Log.warn(TAG, "Equip target entity {} is not a valid WandscapeNpc", pkt.entityId());
            return;
        }
        sp.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new NpcMenu(id, inv, npc),
                Component.literal("NPC Info")));
        // 下一 tick 补发数据（客户端屏幕就绪后刷新名字/属性等），与 mobInteract 打开路径一致
        sp.serverLevel().getServer().execute(() -> {
            if (!npc.isRemoved() && sp.containerMenu instanceof NpcMenu) {
                PacketDistributor.sendToPlayer(sp, NpcDataPacket.from(npc));
            }
        });
    }
}