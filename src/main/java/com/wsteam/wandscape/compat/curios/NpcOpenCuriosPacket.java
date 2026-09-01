package com.wsteam.wandscape.compat.curios;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.component.NpcInventory;
import com.wsteam.wandscape.content.task.types.EntityId;

import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import top.theillusivec4.curios.api.CuriosApi;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→server: open the mage's curio container menu (NpcScreen 3D 缩略图左上角的饰品按钮).
 * Server resolves the mage entity and opens {@link NpcCuriosMenu} if it has a curio inventory.
 */
public record NpcOpenCuriosPacket(int entityId) implements CustomPacketPayload {

    private static final String TAG = "NpcOpenCuriosPacket";

    public static final Type<NpcOpenCuriosPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "npc_open_curios"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcOpenCuriosPacket> STREAM_CODEC =
            StreamCodec.of(NpcOpenCuriosPacket::write, NpcOpenCuriosPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void write(RegistryFriendlyByteBuf buf, NpcOpenCuriosPacket pkt) {
        buf.writeInt(pkt.entityId);
    }

    static NpcOpenCuriosPacket read(RegistryFriendlyByteBuf buf) {
        return new NpcOpenCuriosPacket(buf.readInt());
    }

    /** Server handler. */
    public static void handleServer(NpcOpenCuriosPacket pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        var level = sp.serverLevel();
        if (!(level.getEntity(pkt.entityId()) instanceof WandscapeNpc npc) || npc.isRemoved()) {
            Log.warn(TAG, "Curios target entity {} is not a valid WandscapeNpc", pkt.entityId());
            return;
        }
        var handler = CuriosApi.getCuriosInventory(npc).orElse(null);
        if (handler == null) {
            Log.warn(TAG, "Mage {} has no curio inventory", npc.getUUID());
            ScreenFeedbackPacket.send(sp,
                    I18n.name("message.wandscape.curios.no_slots", "This mage has no trinket slots."),
                    true);
            return;
        }
        // 扩展菜单：随 open 包写法师 entityId（客户端菜单据此可重新打开法师装备界面）
        sp.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new NpcCuriosMenu(id, inv, handler, npc),
                I18n.name("gui.wandscape.curios.title", "Mage Trinkets")),
                buf -> buf.writeInt(npc.getId()));
    }
}