package com.wsteam.wandscape.content.building.network;

import com.wsteam.wandscape.content.building.internal.MageHutServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→server packet: a Mage Hut panel button (assign / upgrade / rest /
 * train / open equipment / open strategy).
 *
 * <p>{@code action} strings:
 * <ul>
 *   <li>{@code assign:<uuid>} — assign the colony mage with that UUID</li>
 *   <li>{@code upgrade} — level the resident up (cost = each element ×1000)</li>
 *   <li>{@code rest} — make the resident stop work and go rest for 2 min</li>
 *   <li>{@code train:<AttrType>} — train one attribute (cost = each element ×1000)</li>
 *   <li>{@code open_equip} — open the resident's equipment menu</li>
 *   <li>{@code open_strategy} — open the resident's cast-strategy menu</li>
 * </ul>
 */
public record MageHutActionPacket(BlockPos buildingPos, String action)
        implements CustomPacketPayload {

    public static final Type<MageHutActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "mage_hut_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MageHutActionPacket> STREAM_CODEC =
            StreamCodec.of(MageHutActionPacket::write, MageHutActionPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void write(RegistryFriendlyByteBuf buf, MageHutActionPacket pkt) {
        buf.writeBlockPos(pkt.buildingPos);
        buf.writeUtf(pkt.action);
    }

    static MageHutActionPacket read(RegistryFriendlyByteBuf buf) {
        return new MageHutActionPacket(buf.readBlockPos(), buf.readUtf());
    }

    public static void handleServer(MageHutActionPacket pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        sp.getServer().execute(() ->
                MageHutServerHandler.handleAction(sp, sp.serverLevel(), pkt));
    }
}
