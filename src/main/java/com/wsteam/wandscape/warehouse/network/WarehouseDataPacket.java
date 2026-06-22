package com.wsteam.wandscape.warehouse.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.wsteam.wandscape.shared.data.ItemKey;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→client packet carrying warehouse item data for GUI display.
 * Sent once when the player opens the warehouse screen.
 */
public record WarehouseDataPacket(ListTag items) implements CustomPacketPayload {

    public static final Type<WarehouseDataPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "warehouse_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WarehouseDataPacket> STREAM_CODEC =
            StreamCodec.of(WarehouseDataPacket::write, WarehouseDataPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Build packet from a snapshot of warehouse items. */
    public static WarehouseDataPacket from(Map<ItemKey, Long> snapshot) {
        ListTag list = new ListTag();
        for (var entry : snapshot.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("key", entry.getKey().itemId());
            if (entry.getKey().nbt() != null) {
                tag.put("nbt", entry.getKey().nbt());
            }
            tag.putLong("count", entry.getValue());
            list.add(tag);
        }
        return new WarehouseDataPacket(list);
    }

    /** Decode back into a list of item entries (used client-side). */
    public List<ItemEntry> entries() {
        List<ItemEntry> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            CompoundTag tag = items.getCompound(i);
            String key = tag.getString("key");
            CompoundTag nbt = tag.contains("nbt") ? tag.getCompound("nbt") : null;
            long count = tag.getLong("count");
            if (!key.isEmpty() && count > 0) {
                result.add(new ItemEntry(key, nbt, count));
            }
        }
        return result;
    }

    /** A single item entry for client rendering. */
    public record ItemEntry(String itemId, @javax.annotation.Nullable CompoundTag nbt, long count) {}

    private static Consumer<WarehouseDataPacket> clientHandler;

    public static void setClientHandler(Consumer<WarehouseDataPacket> handler) {
        clientHandler = handler;
    }

    /** Handle on client: dispatched via injected Consumer from WandscapeClient. */
    public static void handleClient(WarehouseDataPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, WarehouseDataPacket pkt) {
        CompoundTag wrapper = new CompoundTag();
        wrapper.put("items", pkt.items);
        buf.writeNbt(wrapper);
    }

    static WarehouseDataPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag wrapper = buf.readNbt();
        ListTag list = wrapper != null ? wrapper.getList("items", Tag.TAG_COMPOUND) : new ListTag();
        return new WarehouseDataPacket(list);
    }
}
