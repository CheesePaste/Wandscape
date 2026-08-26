package com.wsteam.wandscape.shared.network;

import java.util.UUID;

import com.wsteam.wandscape.command.ColonyCommand;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.ui.I18n;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
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

        // If this town hall's position is ALREADY linked to an existing colony, link and notify
        UUID existing = colonyApi.getColonyId(packet.townHallAnchor);
        if (existing != null) {
            linkTownHall(colonyApi, packet.townHallAnchor, existing);
            sendMessage(player, I18n.name("message.wandscape.colony.attached",
                    "[Wandscape] 市政厅已关联至现有小镇。"));
            return;
        }

        // Create new colony at townHallAnchor using ColonyCommand.createColonyAt
        ColonyCommand.ColonyCreateOutcome outcome =
                ColonyCommand.createColonyAt(level, packet.townHallAnchor, name, player.getUUID());
        if (outcome == null || !outcome.success()) {
            sendMessage(player, outcome != null ? outcome.message()
                    : I18n.name("message.wandscape.colony.create_failed", "[Wandscape] 创建小镇失败。"));
            return;
        }

        // Link the town hall to the just-created colony
        UUID colonyId = colonyApi.getColonyId(packet.townHallAnchor);
        if (colonyId != null) {
            linkTownHall(colonyApi, packet.townHallAnchor, colonyId);
            Log.info(TAG, "[Colony] Town hall at {} linked to new colony {}",
                    packet.townHallAnchor, colonyId.toString().substring(0, 8));
        }

        // Refresh the client's building-area cache immediately: the just-created
        // colony's town hall must appear on the client so the onboarding guide
        // advances and the panel overlay shows its boundary (no panel reopen needed).
        com.wsteam.wandscape.shared.network.BuildingAreaSyncPacket.sendToPlayer(player, colonyId);

        // Push tutorial progress — the new colony's town hall completes the first step.
        var guideApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getGuideProgressApiSilently();
        if (guideApi != null) {
            guideApi.sendToPlayer(player, colonyId);
        }
    }

    private static void linkTownHall(ColonyApi colonyApi, BlockPos anchor, UUID colonyId) {
        var buildingApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getBuildingApi();
        if (buildingApi == null) return;
        var building = buildingApi.getBuildingAt(anchor);
        if (building instanceof com.wsteam.wandscape.building.internal.BuildingState state) {
            state.setColonyId(colonyId);
            colonyApi.assignColonyIfPossible(building);
            colonyApi.onBuildingIntact(building);
        }
    }

    private static void sendMessage(ServerPlayer player, Component message) {
        if (player != null) {
            player.sendSystemMessage(message);
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
