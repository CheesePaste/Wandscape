package com.wsteam.wandscape.shared.network;

import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Client→Server: Notifies server that the player opened or closed the Wandscape panel.
 * Server stores this state to gate building right-click interactions.
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
            ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
            if (colonyApi != null) {
                UUID colonyId = colonyApi.getColonyId(player.blockPosition());
                if (colonyId != null) {
                    BuildingApi buildingApi = WandscapeApis.getBuildingApi();
                    int c = buildingApi.getColonyComfort(colonyId);
                    int m = buildingApi.getColonyMagic(colonyId);
                    int w = buildingApi.getColonyWonder(colonyId);

                    var levelMgr = com.wsteam.wandscape.engine.WandscapeEngine.getColonyLevelManager();
                    int lvl = levelMgr != null ? levelMgr.getLevel(colonyId) : 1;
                    int exp = levelMgr != null ? levelMgr.getExperience(colonyId) : 0;
                    String name = levelMgr != null ? levelMgr.getColonyName(colonyId) : "";

                    // ── Collect HUD data (try-catch: modules may not be loaded) ──
                    int touristCount = 0;
                    var touristApi = WandscapeApis.getTouristApiSilently();
                    if (touristApi != null) touristCount = touristApi.getTouristCount(colonyId);

                    int overnightStayerCount = 0;
                    if (touristApi != null) overnightStayerCount = touristApi.getOvernightStayerCount(colonyId);

                    int shutdownCount = 0;
                    List<String> shutdownBuildingNames = List.of();
                    try {
                        var buildings = buildingApi.getColonyBuildings(colonyId);
                        var shutdownBuildings = buildings.stream().filter(b -> b.isShutdown()).toList();
                        shutdownCount = shutdownBuildings.size();
                        shutdownBuildingNames = shutdownBuildings.stream().map(b -> b.getBuildingTypeId()).toList();
                    } catch (Exception ignored) {}

                    int npcIdleCount = 0, npcTotalCount = 0;
                    try {
                        var npcApi = WandscapeApis.getNpcApi();
                        npcIdleCount = npcApi.getIdleNpcs(colonyId).size();
                        npcTotalCount = npcApi.getColonyNpcs(colonyId).size();
                    } catch (Exception ignored) {}

                    int earthAmount = 0, woodAmount = 0, waterAmount = 0, fireAmount = 0, windAmount = 0;
                    int metalAmount = 0, darkAmount = 0;
                    try {
                        var warehouseApi = WandscapeApis.getWarehouseApiSilently();
                        if (warehouseApi != null) {
                            var elements = warehouseApi.getAllElements(colonyId);
                            earthAmount = elements.getOrDefault(ElementType.EARTH, 0L).intValue();
                            woodAmount = elements.getOrDefault(ElementType.WOOD, 0L).intValue();
                            waterAmount = elements.getOrDefault(ElementType.WATER, 0L).intValue();
                            fireAmount = elements.getOrDefault(ElementType.FIRE, 0L).intValue();
                            windAmount = elements.getOrDefault(ElementType.WIND, 0L).intValue();
                            metalAmount = elements.getOrDefault(ElementType.METAL, 0L).intValue();
                            darkAmount = elements.getOrDefault(ElementType.DARK, 0L).intValue();
                        }
                    } catch (Exception ignored) {}

                    PacketDistributor.sendToPlayer(player,
                            new ColonyStatsSyncPacket(colonyId, c, m, w, name, lvl, exp,
                                    touristCount, overnightStayerCount, shutdownCount,
                                    npcIdleCount, npcTotalCount,
                                    earthAmount, woodAmount, waterAmount, fireAmount, windAmount,
                                    metalAmount, darkAmount,
                                    shutdownBuildingNames));

                    // Sync building interaction areas for overlay rendering
                    List<BuildingAreaSyncPacket.BuildingEntry> entries =
                            buildingApi.getColonyBuildings(colonyId).stream()
                                    .map(b -> new BuildingAreaSyncPacket.BuildingEntry(
                                            b.getPosition(), b.getBuildingTypeId()))
                                    .toList();
                    PacketDistributor.sendToPlayer(player,
                            new BuildingAreaSyncPacket(entries));
                }
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
