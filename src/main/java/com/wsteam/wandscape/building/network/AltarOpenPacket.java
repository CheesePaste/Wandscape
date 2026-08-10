package com.wsteam.wandscape.building.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.wsteam.wandscape.shared.data.AltarSpellInfo;

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
 * Server→client packet: opens the Altar GUI with the altar's castable spells
 * (altarOnly magics) and each spell's current per-altar cooldown.
 */
public record AltarOpenPacket(BlockPos buildingPos, UUID colonyId, UUID buildingId,
                              String creator,
                              List<AltarSpellInfo> spells)
        implements CustomPacketPayload {

    public static final Type<AltarOpenPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "altar_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AltarOpenPacket> STREAM_CODEC =
            StreamCodec.of(AltarOpenPacket::write, AltarOpenPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // Client handler
    private static Consumer<AltarOpenPacket> clientHandler;

    public static void setClientHandler(Consumer<AltarOpenPacket> handler) { clientHandler = handler; }

    public static void handleClient(AltarOpenPacket packet) {
        if (clientHandler != null) clientHandler.accept(packet);
    }

    static void write(RegistryFriendlyByteBuf buf, AltarOpenPacket pkt) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("pos", pkt.buildingPos.asLong());
        tag.putUUID("colony", pkt.colonyId);
        tag.putUUID("building", pkt.buildingId);
        tag.putString("creator", pkt.creator);
        ListTag spells = new ListTag();
        for (AltarSpellInfo s : pkt.spells) {
            CompoundTag st = new CompoundTag();
            st.putString("id", s.magicId());
            st.putInt("mana", s.manaCost());
            st.putInt("cd", s.cooldownTicks());
            st.putInt("dur", s.durationTicks());
            st.putInt("cdr", s.cooldownRemaining());
            st.putBoolean("locked", s.locked());
            spells.add(st);
        }
        tag.put("spells", spells);
        buf.writeNbt(tag);
    }

    static AltarOpenPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        if (tag == null) {
            return new AltarOpenPacket(BlockPos.ZERO, new UUID(0, 0), new UUID(0, 0), "", List.of());
        }
        List<AltarSpellInfo> spells = new ArrayList<>();
        ListTag list = tag.getList("spells", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag st = list.getCompound(i);
            spells.add(new AltarSpellInfo(
                    st.getString("id"),
                    st.getInt("mana"),
                    st.getInt("cd"),
                    st.getInt("dur"),
                    st.getInt("cdr"),
                    st.getBoolean("locked")));
        }
        return new AltarOpenPacket(
                BlockPos.of(tag.getLong("pos")),
                tag.getUUID("colony"),
                tag.getUUID("building"),
                tag.getString("creator"),
                spells);
    }
}
