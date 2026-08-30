package com.wsteam.wandscape.building.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→client packet: opens the Shop GUI with current stock, max stock per good,
 * and building context.
 */
public record ShopOpenPacket(BlockPos buildingPos, UUID colonyId, UUID buildingId,
                              String creator,
                              Map<String, Integer> stock, Map<String, Integer> maxStocks)
        implements CustomPacketPayload {

    public static final Type<ShopOpenPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "shop_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShopOpenPacket> STREAM_CODEC =
            StreamCodec.of(ShopOpenPacket::write, ShopOpenPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // Client handler
    private static Consumer<ShopOpenPacket> clientHandler;

    public static void setClientHandler(Consumer<ShopOpenPacket> handler) { clientHandler = handler; }

    public static void handleClient(ShopOpenPacket packet) {
        if (clientHandler != null) clientHandler.accept(packet);
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, ShopOpenPacket pkt) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("pos", pkt.buildingPos.asLong());
        tag.putUUID("colony", pkt.colonyId);
        tag.putUUID("building", pkt.buildingId);
        tag.putString("creator", pkt.creator);
        CompoundTag stockTag = new CompoundTag();
        for (var entry : pkt.stock.entrySet()) {
            stockTag.putInt(entry.getKey(), entry.getValue());
        }
        tag.put("stock", stockTag);
        CompoundTag maxTag = new CompoundTag();
        for (var entry : pkt.maxStocks.entrySet()) {
            maxTag.putInt(entry.getKey(), entry.getValue());
        }
        tag.put("max", maxTag);
        buf.writeNbt(tag);
    }

    static ShopOpenPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        if (tag == null) {
            return new ShopOpenPacket(BlockPos.ZERO, new UUID(0, 0),
                    new UUID(0, 0), "", Map.of(), Map.of());
        }
        Map<String, Integer> stock = new HashMap<>();
        CompoundTag stockTag = tag.getCompound("stock");
        for (String key : stockTag.getAllKeys()) {
            stock.put(key, stockTag.getInt(key));
        }
        Map<String, Integer> maxStocks = new HashMap<>();
        CompoundTag maxTag = tag.getCompound("max");
        for (String key : maxTag.getAllKeys()) {
            maxStocks.put(key, maxTag.getInt(key));
        }
        return new ShopOpenPacket(
                BlockPos.of(tag.getLong("pos")),
                tag.getUUID("colony"),
                tag.getUUID("building"),
                tag.getString("creator"),
                stock,
                maxStocks);
    }
}
