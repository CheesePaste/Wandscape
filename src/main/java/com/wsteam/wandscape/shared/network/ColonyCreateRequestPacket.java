package com.wsteam.wandscape.shared.network;

import java.util.UUID;

import com.wsteam.wandscape.command.ColonyCommand;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Client→Server: Create a colony when the player names a town hall that was
 * built before any colony existed.
 *
 * <p>The player right-clicks an intact town hall with no colony nearby, the
 * client shows a naming screen, and upon confirm sends this packet with the
 * town hall's anchor and the chosen name. The server routes to
 * {@link ColonyCommand#createColonyAt} — the same core logic as
 * {@code /wandscape colony create} — then links the town hall to the new
 * colony.
 */
public record ColonyCreateRequestPacket(BlockPos townHallAnchor, String name)
        implements CustomPacketPayload {

    private static final String TAG = "ColonyCreateRequestPacket";

    public static final Type<ColonyCreateRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "colony_create_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ColonyCreateRequestPacket> STREAM_CODEC =
            StreamCodec.of(ColonyCreateRequestPacket::write, ColonyCreateRequestPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleServer(ColonyCreateRequestPacket packet, ServerPlayer player) {
        if (packet.name == null || packet.name.trim().isEmpty()) {
            Log.warn(TAG, "[Colony] Colony create request with empty name ignored");
            return;
        }
        String name = packet.name.trim().length() > 30
                ? packet.name.trim().substring(0, 30) : packet.name.trim();

        ServerLevel level = player.serverLevel();
        ColonyApi colonyApi = com.wsteam.wandscape.engine.ColonyApiImpl.get();

        // Refuse if a colony already exists near the town hall (or anywhere).
        // The V-panel / command flow owns colony creation; this panel only
        // serves the "no colony yet" bootstrap case.
        if (!colonyApi.getAllColonyIds().isEmpty()) {
            // A colony exists somewhere — try to assign the town hall to the
            // nearest one; if none is within range, refuse politely.
            UUID existing = colonyApi.getColonyId(packet.townHallAnchor);
            if (existing != null) {
                linkTownHall(colonyApi, packet.townHallAnchor, existing);
                sendMessage(player, "[Wandscape] Town hall linked to existing colony.");
                return;
            }
            sendMessage(player, "[Wandscape] A colony already exists — use /wandscape colony create to make another.");
            return;
        }

        String result = ColonyCommand.createColonyAt(level, packet.townHallAnchor, name);
        if (result == null || result.startsWith("[Wandscape] no government")
                || result.startsWith("[Wandscape] Failed")) {
            sendMessage(player, result != null ? result : "[Wandscape] Failed to create colony.");
            return;
        }

        // Link the town hall to the just-created colony. The colony origin
        // matches the town hall anchor, so ColonyApi.onBuildingIntact links it.
        UUID colonyId = colonyApi.getColonyId(packet.townHallAnchor);
        if (colonyId != null) {
            linkTownHall(colonyApi, packet.townHallAnchor, colonyId);
            Log.info(TAG, "[Colony] Town hall at {} linked to new colony {}",
                    packet.townHallAnchor, colonyId.toString().substring(0, 8));
        }

        sendMessage(player, result);
    }

    private static void linkTownHall(ColonyApi colonyApi, BlockPos anchor, UUID colonyId) {
        // onBuildingIntact links an intact town hall to an existing colony
        // (origin match). If the building is still under construction it will
        // be linked automatically when it becomes intact later.
        var buildingApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getBuildingApi();
        if (buildingApi == null) return;
        var building = buildingApi.getBuildingAt(anchor);
        if (building != null) {
            colonyApi.onBuildingIntact(building);
        }
    }

    private static void sendMessage(ServerPlayer player, String message) {
        if (player != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
        }
    }

    static void write(RegistryFriendlyByteBuf buf, ColonyCreateRequestPacket pkt) {
        buf.writeLong(pkt.townHallAnchor.asLong());
        buf.writeUtf(pkt.name != null ? pkt.name : "");
    }

    static ColonyCreateRequestPacket read(RegistryFriendlyByteBuf buf) {
        return new ColonyCreateRequestPacket(BlockPos.of(buf.readLong()), buf.readUtf());
    }
}
