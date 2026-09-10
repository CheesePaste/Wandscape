package com.wsteam.wandscape.content.building.network;

import com.wsteam.wandscape.content.npc.internal.ReviveHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Consumer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→client: live refresh of the town hall 「复活法师」 bootstrap-revive button state.
 *
 * <p>Sent when the colony's population or the revive cooldown can have changed — a wizard
 * dying, or a successful revive — so an already-open {@code TownHallScreen} re-evaluates its
 * button without the player having to close and reopen the panel. The client ignores the
 * packet unless a matching colony's town hall screen is open, so it is safe to broadcast.
 */
public record TownHallReviveStatePacket(UUID colonyId, int aliveNpcCount, int deadNpcCount,
                                        int cooldownSeconds)
        implements CustomPacketPayload {

    public static final Type<TownHallReviveStatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "town_hall_revive_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TownHallReviveStatePacket> STREAM_CODEC =
            StreamCodec.of(TownHallReviveStatePacket::write, TownHallReviveStatePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Snapshot the button-relevant state of one colony. */
    public static TownHallReviveStatePacket from(Level level, UUID colonyId) {
        return new TownHallReviveStatePacket(
                colonyId,
                ReviveHandler.aliveCount(colonyId),
                ReviveHandler.deadCount(level, colonyId),
                ReviveHandler.townHallReviveCooldownSeconds(level, colonyId));
    }

    /** Send the current state to one player (e.g. right after they pressed the button). */
    public static void send(ServerPlayer player, ServerLevel level, UUID colonyId) {
        if (player != null && !player.isRemoved()) {
            PacketDistributor.sendToPlayer(player, from(level, colonyId));
        }
    }

    /** Send the current state to everyone in the level; clients without a matching screen drop it. */
    public static void broadcast(ServerLevel level, UUID colonyId) {
        if (level == null || colonyId == null || level.players().isEmpty()) return;
        TownHallReviveStatePacket pkt = from(level, colonyId);
        for (ServerPlayer player : level.players()) {
            PacketDistributor.sendToPlayer(player, pkt);
        }
    }

    // ── Client handler (injected by WandscapeClient) ──

    private static Consumer<TownHallReviveStatePacket> clientHandler;

    public static void setClientHandler(Consumer<TownHallReviveStatePacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(TownHallReviveStatePacket packet) {
        if (clientHandler != null) clientHandler.accept(packet);
    }

    static void write(RegistryFriendlyByteBuf buf, TownHallReviveStatePacket pkt) {
        buf.writeUUID(pkt.colonyId);
        buf.writeVarInt(pkt.aliveNpcCount);
        buf.writeVarInt(pkt.deadNpcCount);
        buf.writeVarInt(pkt.cooldownSeconds);
    }

    static TownHallReviveStatePacket read(RegistryFriendlyByteBuf buf) {
        return new TownHallReviveStatePacket(buf.readUUID(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt());
    }
}
