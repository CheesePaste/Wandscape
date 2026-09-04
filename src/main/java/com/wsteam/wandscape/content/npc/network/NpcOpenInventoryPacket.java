package com.wsteam.wandscape.content.npc.network;

import com.wsteam.wandscape.content.npc.NpcInventoryMenu;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→server: open the mage's inventory container menu (NpcScreen 3D 缩略图左上角的背包按钮).
 * Server resolves the mage entity and opens {@link NpcInventoryMenu}.
 */
public record NpcOpenInventoryPacket(int entityId) implements CustomPacketPayload {

    private static final String TAG = "NpcOpenInventoryPacket";

    public static final Type<NpcOpenInventoryPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "npc_open_inventory"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcOpenInventoryPacket> STREAM_CODEC =
            StreamCodec.of(NpcOpenInventoryPacket::write, NpcOpenInventoryPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void write(RegistryFriendlyByteBuf buf, NpcOpenInventoryPacket pkt) {
        buf.writeInt(pkt.entityId);
    }

    static NpcOpenInventoryPacket read(RegistryFriendlyByteBuf buf) {
        return new NpcOpenInventoryPacket(buf.readInt());
    }

    /** Server handler. */
    public static void handleServer(NpcOpenInventoryPacket pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        var level = sp.serverLevel();
        if (!(level.getEntity(pkt.entityId()) instanceof WandscapeNpc npc) || npc.isRemoved()) {
            Log.warn(TAG, "Inventory target entity {} is not a valid WandscapeNpc", pkt.entityId());
            return;
        }
        sp.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new NpcInventoryMenu(id, inv, npc),
                I18n.name("gui.wandscape.npc.inventory", "Inventory")),
                buf -> buf.writeInt(npc.getId()));
    }
}
