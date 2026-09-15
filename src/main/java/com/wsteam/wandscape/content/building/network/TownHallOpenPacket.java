package com.wsteam.wandscape.content.building.network;
import com.wsteam.wandscape.foundation.util.NameStyle;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;
import java.util.function.Consumer;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→client packet: opens the Town Hall info screen with colony name, level and experience.
 * {@code canUseWarehouse} is true when the colony has no storage building, so the client
 * shows a "warehouse access" button letting the town hall act as a warehouse.
 * {@code aliveNpcCount} / {@code deadNpcCount} / {@code reviveCooldownSeconds} drive the
 * 「复活法师」 bootstrap-revive button (only usable when the colony is wiped out and off cooldown);
 * later changes arrive via {@link TownHallReviveStatePacket}.
 *
 * <p>命名风格与游客生成开关不在这里：它们随殖民地走，由 {@code ColonyStatsSyncPacket} 下发、
 * 在设置中心的「本镇」页修改。
 */
public record TownHallOpenPacket(BlockPos buildingPos, UUID colonyId,
                                 String colonyName, int level, int experience, int expToNext,
                                 String founderName, boolean canUseWarehouse,
                                 String creator,
                                 int aliveNpcCount, int deadNpcCount, int reviveCooldownSeconds)
        implements CustomPacketPayload {

    public static final Type<TownHallOpenPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "town_hall_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TownHallOpenPacket> STREAM_CODEC =
            StreamCodec.of(TownHallOpenPacket::write, TownHallOpenPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static Consumer<TownHallOpenPacket> clientHandler;

    public static void setClientHandler(Consumer<TownHallOpenPacket> handler) { clientHandler = handler; }

    public static void handleClient(TownHallOpenPacket packet) {
        if (clientHandler != null) clientHandler.accept(packet);
    }

    static void write(RegistryFriendlyByteBuf buf, TownHallOpenPacket pkt) {
        buf.writeLong(pkt.buildingPos.asLong());
        buf.writeUUID(pkt.colonyId);
        buf.writeUtf(pkt.colonyName != null ? pkt.colonyName : "");
        buf.writeVarInt(pkt.level);
        buf.writeVarInt(pkt.experience);
        buf.writeVarInt(pkt.expToNext);
        buf.writeUtf(pkt.founderName != null ? pkt.founderName : "");
        buf.writeBoolean(pkt.canUseWarehouse);
        buf.writeUtf(pkt.creator != null ? pkt.creator : "");
        buf.writeVarInt(pkt.aliveNpcCount);
        buf.writeVarInt(pkt.deadNpcCount);
        buf.writeVarInt(pkt.reviveCooldownSeconds);
    }

    static TownHallOpenPacket read(RegistryFriendlyByteBuf buf) {
        // Field order MUST match write(): long → UUID → utf → varint×3 → utf → boolean → utf → varint×3.
        BlockPos buildingPos = BlockPos.of(buf.readLong());
        UUID colonyId = buf.readUUID();
        String colonyName = buf.readUtf();
        int level = buf.readVarInt();
        int experience = buf.readVarInt();
        int expToNext = buf.readVarInt();
        String founderName = buf.readUtf();
        boolean canUseWarehouse = buf.readBoolean();
        String creator = buf.readUtf();
        int aliveNpcCount = buf.readVarInt();
        int deadNpcCount = buf.readVarInt();
        int reviveCooldownSeconds = buf.readVarInt();
        return new TownHallOpenPacket(buildingPos, colonyId, colonyName, level, experience, expToNext,
                founderName.isEmpty() ? null : founderName, canUseWarehouse, creator,
                aliveNpcCount, deadNpcCount, reviveCooldownSeconds);
    }
}
