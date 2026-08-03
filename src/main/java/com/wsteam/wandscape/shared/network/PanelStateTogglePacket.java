package com.wsteam.wandscape.shared.network;

import com.wsteam.wandscape.command.ColonyCommand;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.api.ColonyMetricsApi;
import com.wsteam.wandscape.shared.data.ColonyMetricsSnapshot;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Notifies server that the player opened or closed the Wandscape panel.
 * Server stores this state to gate building right-click interactions.
 *
 * <p>On open, if no colony exists near the player, the server auto-creates one at the
 * player's position (moved earlier from the "first town hall placement with no colony"
 * flow), so the colony exists before the first building is placed — which the per-colony
 * first-free ({@code first_free}) claim requires.
 */
public record PanelStateTogglePacket(boolean open) implements CustomPacketPayload {

    public static final Type<PanelStateTogglePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "panel_state_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PanelStateTogglePacket> STREAM_CODEC =
            StreamCodec.of(PanelStateTogglePacket::write, PanelStateTogglePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleServer(PanelStateTogglePacket packet, ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (packet.open) {
            PanelStateTracker.open(playerId);
            UUID colonyId = null;
            ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
            if (colonyApi != null) {
                colonyId = colonyApi.getColonyId(player.blockPosition());
                if (colonyId == null) {
                    // 首免修复：把「首次放市政厅没殖民地才创建」提前到面板打开时。
                    // 殖民地必须在首座建筑（市政厅）放置前就存在，否则 placeBuilding
                    // 的 first_free 判定拿不到 colonyId，首免永不触发。
                    colonyId = ColonyCommand.ensureColonyNear(
                            player.serverLevel(), player.blockPosition(),
                            player.getGameProfile().getName() + "的殖民地",
                            player.getUUID());
                    if (colonyId != null) {
                        player.sendSystemMessage(Component.literal(
                                "[Wandscape] 殖民地尚未建立，已自动创建「"
                                        + player.getGameProfile().getName() + "的殖民地」——放置市政厅后自动关联。"));
                    }
                }
                if (colonyId != null) {
                    ColonyMetricsApi metricsApi = WandscapeApis.getColonyMetricsApiSilently();
                    if (metricsApi != null) {
                        ColonyMetricsSnapshot snap = metricsApi.getSnapshotSafe(colonyId);
                        if (snap.colonyId() != null) {
                            PacketDistributor.sendToPlayer(player, ColonyStatsSyncPacket.fromSnapshot(snap));
                        }
                    }

                    // Sync building interaction areas + construction ghosts for overlay rendering
                    BuildingAreaSyncPacket.sendToPlayer(player);
                }
            }

            // Seed tutorial progress (recomputed when a colony exists; otherwise
            // only the saved value so a pre-colony dismissal still persists).
            var guideApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getGuideProgressApiSilently();
            if (guideApi != null) {
                guideApi.sendToPlayer(player, colonyId);
            }
        } else {
            PanelStateTracker.close(playerId);
        }
    }

    static void write(RegistryFriendlyByteBuf buf, PanelStateTogglePacket pkt) {
        buf.writeBoolean(pkt.open);
    }

    static PanelStateTogglePacket read(RegistryFriendlyByteBuf buf) {
        return new PanelStateTogglePacket(buf.readBoolean());
    }
}
