package com.wsteam.wandscape.building.network;

import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.building.internal.BuildingInteractHandler;
import com.wsteam.wandscape.building.internal.ShopStockManager;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Client→server packet: adjusts max stock for one good in a shop.
 * Server responds with a refreshed {@link ShopOpenPacket} so the GUI stays in sync.
 */
public record ShopMaxStockPacket(UUID buildingId, BlockPos buildingPos,
                                  UUID colonyId, String itemId, int newMax)
        implements CustomPacketPayload {

    public static final Type<ShopMaxStockPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "shop_max_stock"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopMaxStockPacket> STREAM_CODEC =
            StreamCodec.of(ShopMaxStockPacket::write, ShopMaxStockPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleServer(ShopMaxStockPacket packet, ServerPlayer player) {
        ShopStockManager manager = ShopStockManager.getActive();
        if (manager == null) return;

        manager.setMaxStock(packet.buildingId, packet.itemId, packet.newMax);

        // Send refreshed shop data back to player
        Map<String, Integer> stock = manager.getStock(packet.buildingId);
        Map<String, Integer> maxStocks = manager.getAllMaxStocks(packet.buildingId);
        var refresh = new ShopOpenPacket(packet.buildingPos, packet.colonyId,
                packet.buildingId, stock, maxStocks);
        PacketDistributor.sendToPlayer(player, refresh);
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, ShopMaxStockPacket pkt) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("building", pkt.buildingId);
        tag.putLong("pos", pkt.buildingPos.asLong());
        tag.putUUID("colony", pkt.colonyId);
        tag.putString("item", pkt.itemId);
        tag.putInt("max", pkt.newMax);
        buf.writeNbt(tag);
    }

    static ShopMaxStockPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        if (tag == null) {
            return new ShopMaxStockPacket(new UUID(0, 0), BlockPos.ZERO,
                    new UUID(0, 0), "", 0);
        }
        return new ShopMaxStockPacket(
                tag.getUUID("building"),
                BlockPos.of(tag.getLong("pos")),
                tag.getUUID("colony"),
                tag.getString("item"),
                tag.getInt("max"));
    }
}
