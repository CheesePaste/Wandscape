package com.wsteam.wandscape.warehouse.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.ItemKey;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→client packet carrying warehouse item and element data for GUI display.
 * Sent once when the player opens the warehouse screen.
 */
public record WarehouseDataPacket(BlockPos buildingPos, UUID colonyId,
                                   ListTag items, ListTag elements) implements CustomPacketPayload {

    public static final Type<WarehouseDataPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "warehouse_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WarehouseDataPacket> STREAM_CODEC =
            StreamCodec.of(WarehouseDataPacket::write, WarehouseDataPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Build packet from position, colony, and item/element snapshots. */
    public static WarehouseDataPacket from(BlockPos buildingPos, UUID colonyId,
                                            Map<ItemKey, Long> itemSnapshot,
                                            Map<ElementType, Long> elementSnapshot) {
        ListTag itemList = new ListTag();
        for (var entry : itemSnapshot.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("key", entry.getKey().itemId());
            if (entry.getKey().nbt() != null) {
                tag.put("nbt", entry.getKey().nbt());
            }
            tag.putLong("count", entry.getValue());
            itemList.add(tag);
        }

        ListTag elemList = new ListTag();
        for (var entry : elementSnapshot.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("type", entry.getKey().name());
            tag.putLong("amount", entry.getValue());
            elemList.add(tag);
        }

        return new WarehouseDataPacket(buildingPos, colonyId, itemList, elemList);
    }

    /** Decode item list for client rendering. */
    public List<ItemEntry> itemEntries() {
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

    /** Decode element map for client rendering. */
    public Map<ElementType, Long> elementMap() {
        Map<ElementType, Long> result = new LinkedHashMap<>();
        for (ElementType type : ElementType.values()) {
            result.put(type, 0L);
        }
        for (int i = 0; i < elements.size(); i++) {
            CompoundTag tag = elements.getCompound(i);
            try {
                ElementType type = ElementType.valueOf(tag.getString("type"));
                long amount = tag.getLong("amount");
                if (amount > 0) result.put(type, amount);
            } catch (IllegalArgumentException ignored) {
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
        wrapper.putLong("pos", pkt.buildingPos.asLong());
        wrapper.putUUID("colony", pkt.colonyId);
        wrapper.put("itms", pkt.items);
        wrapper.put("elems", pkt.elements);
        buf.writeNbt(wrapper);
    }

    static WarehouseDataPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag wrapper = buf.readNbt();
        if (wrapper == null) {
            return new WarehouseDataPacket(BlockPos.ZERO, new UUID(0, 0),
                    new ListTag(), new ListTag());
        }
        BlockPos buildingPos = BlockPos.of(wrapper.getLong("pos"));
        UUID colonyId = wrapper.contains("colony") ? wrapper.getUUID("colony") : new UUID(0, 0);
        ListTag items = wrapper.getList("itms", Tag.TAG_COMPOUND);
        ListTag elems = wrapper.getList("elems", Tag.TAG_COMPOUND);
        return new WarehouseDataPacket(buildingPos, colonyId, items, elems);
    }
}
