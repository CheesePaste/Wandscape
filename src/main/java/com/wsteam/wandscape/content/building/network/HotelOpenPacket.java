package com.wsteam.wandscape.content.building.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→client packet: opens the Hotel GUI with occupancy info.
 */
public record HotelOpenPacket(BlockPos buildingPos, UUID colonyId, UUID buildingId,
                               String creator,
                               int maxOccupancy, int currentOccupancy,
                               List<String> guestNames)
        implements CustomPacketPayload {

    public static final Type<HotelOpenPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "hotel_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HotelOpenPacket> STREAM_CODEC =
            StreamCodec.of(HotelOpenPacket::write, HotelOpenPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // Client handler
    private static Consumer<HotelOpenPacket> clientHandler;

    public static void setClientHandler(Consumer<HotelOpenPacket> handler) { clientHandler = handler; }

    public static void handleClient(HotelOpenPacket packet) {
        if (clientHandler != null) clientHandler.accept(packet);
    }

    static void write(RegistryFriendlyByteBuf buf, HotelOpenPacket pkt) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("pos", pkt.buildingPos.asLong());
        tag.putUUID("colony", pkt.colonyId);
        tag.putUUID("building", pkt.buildingId);
        tag.putString("creator", pkt.creator);
        tag.putInt("maxOcc", pkt.maxOccupancy);
        tag.putInt("curOcc", pkt.currentOccupancy);
        ListTag names = new ListTag();
        for (String name : pkt.guestNames) {
            names.add(StringTag.valueOf(name));
        }
        tag.put("guests", names);
        buf.writeNbt(tag);
    }

    static HotelOpenPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        if (tag == null) {
            return new HotelOpenPacket(BlockPos.ZERO, new UUID(0, 0),
                    new UUID(0, 0), "", 0, 0, List.of());
        }
        List<String> names = new ArrayList<>();
        ListTag list = tag.getList("guests", ListTag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            names.add(list.getString(i));
        }
        return new HotelOpenPacket(
                BlockPos.of(tag.getLong("pos")),
                tag.getUUID("colony"),
                tag.getUUID("building"),
                tag.getString("creator"),
                tag.getInt("maxOcc"),
                tag.getInt("curOcc"),
                names);
    }
}
