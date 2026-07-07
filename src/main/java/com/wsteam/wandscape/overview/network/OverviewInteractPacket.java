package com.wsteam.wandscape.overview.network;

import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import com.wsteam.wandscape.shared.log.Log;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Player requests building interaction from overview mode.
 * Server looks up the building at the given position and sends the
 * appropriate GUI data packet back to the client.
 */
public record OverviewInteractPacket(BlockPos buildingBlockPos) implements CustomPacketPayload {

    private static final String TAG = "OverviewInteractPacket";

    public static final Type<OverviewInteractPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "overview_interact"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OverviewInteractPacket> STREAM_CODEC =
            StreamCodec.of(OverviewInteractPacket::write, OverviewInteractPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    public static void handleServer(OverviewInteractPacket packet, ServerPlayer player) {
        Level level = player.serverLevel();
        BlockPos pos = packet.buildingBlockPos();

        // Look up building at position
        BuildingSavedData data = BuildingSavedData.get(level);
        java.util.UUID buildingId = data.getBuildingIdAt(pos);

        // Fallback: interaction zone
        if (buildingId == null) {
            buildingId = data.getBuildingIdInInteractionZone(pos);
        }

        if (buildingId == null) {
            Log.info(TAG, "[Overview] No building found at {}", pos);
            return;
        }

        BuildingState state = data.getBuilding(buildingId);
        if (state == null) {
            Log.warn(TAG, "[Overview] Building state null for {}", buildingId);
            return;
        }

        // Delegate to the same logic used by BuildingInteractHandler
        interactWithBuilding(player, level, pos, state);
    }

    /**
     * Executes the same building interaction logic as
     * {@link com.wsteam.wandscape.building.internal.BuildingInteractHandler#onRightClickBlock}.
     * Categorized by building category, sends the appropriate GUI data packet.
     */
    private static void interactWithBuilding(ServerPlayer player, Level level,
                                             BlockPos pos, BuildingState state) {
        String category = state.getCategory();
        java.util.UUID colonyId = state.getColonyId();
        if (colonyId == null) colonyId = new java.util.UUID(0, 0);

        var bldConfig = com.wsteam.wandscape.building.internal.BuildingConfigLoader.getInstance()
                .get(state.getBuildingTypeId());

        // Hotel (service with maxOccupancy > 0)
        if ("service".equals(category) && bldConfig != null && bldConfig.service() != null
                && bldConfig.service().maxOccupancy() > 0) {
            var hotel = com.wsteam.wandscape.tourist.internal.HotelStayHandler.getActive();
            int occupancy = hotel != null ? hotel.getOccupancy(buildingId(state)) : 0;
            int maxOcc = bldConfig.service().maxOccupancy();
            var guestNames = hotel != null
                    ? hotel.getGuestNames(buildingId(state), level)
                    : java.util.List.<String>of();
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    player, new com.wsteam.wandscape.building.network.HotelOpenPacket(
                            pos, colonyId, buildingId(state), maxOcc, occupancy, guestNames));
            return;
        }

        switch (category) {
            case "storage" -> {
                var bank = com.wsteam.wandscape.warehouse.ColonyItemBank.get(level);
                if (bank == null) return;
                java.util.Map<com.wsteam.wandscape.shared.data.ItemKey, Long> snapshot = bank.getSnapshot(colonyId);
                java.util.Map<com.wsteam.wandscape.shared.data.ElementType, Long> elemSnapshot = bank.getElementSnapshot(colonyId);
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        player, com.wsteam.wandscape.warehouse.network.WarehouseDataPacket.from(
                                pos, colonyId, snapshot, elemSnapshot));
            }
            case "workstation" -> {
                var bank = com.wsteam.wandscape.warehouse.ColonyItemBank.get(level);
                if (bank == null) return;
                var elemLoader = com.wsteam.wandscape.Wandscape.ELEMENT_MAPPING_LOADER;
                java.util.Map<com.wsteam.wandscape.shared.data.ItemKey, Long> decomposable = new java.util.LinkedHashMap<>();
                for (var entry : bank.getSnapshot(colonyId).entrySet()) {
                    if (elemLoader.hasSeedValue(entry.getKey().itemId())) {
                        decomposable.put(entry.getKey(), entry.getValue());
                    }
                }
                var prodLoader = com.wsteam.wandscape.Wandscape.PRODUCTION_RECIPE_LOADER;
                var synthRecipes = prodLoader != null
                        ? prodLoader.getAllSynthesizeRecipes()
                        : java.util.Collections.<com.wsteam.wandscape.production.data.SynthesizeRecipe>emptyList();
                java.util.Map<com.wsteam.wandscape.shared.data.ElementType, Long> elemSnapshot = bank.getElementSnapshot(colonyId);
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        player, com.wsteam.wandscape.production.network.WorkstationDataPacket.from(
                                pos, decomposable, synthRecipes, elemSnapshot, colonyId));
            }
            case "crafting_station" -> {
                var bank = com.wsteam.wandscape.warehouse.ColonyItemBank.get(level);
                if (bank == null) return;
                var prodLoader = com.wsteam.wandscape.Wandscape.PRODUCTION_RECIPE_LOADER;
                var wandRecipes = prodLoader != null
                        ? prodLoader.getCraftWandRecipes().getAll().values()
                        : java.util.Collections.<com.wsteam.wandscape.production.data.CraftWandRecipe>emptyList();
                java.util.Map<com.wsteam.wandscape.shared.data.ElementType, Long> elemSnapshot = bank.getElementSnapshot(colonyId);
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        player, com.wsteam.wandscape.production.network.CraftingStationPacket.from(
                                pos, wandRecipes, elemSnapshot, colonyId));
            }
            case "shop" -> {
                var mgr = com.wsteam.wandscape.building.internal.ShopStockManager.getActive();
                if (mgr != null) {
                    mgr.ensureStockInitialized(buildingId(state));
                }
                var stock = mgr != null ? mgr.getStock(buildingId(state)) : java.util.Map.<String, Integer>of();
                var maxStocks = mgr != null ? mgr.getAllMaxStocks(buildingId(state)) : java.util.Map.<String, Integer>of();
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        player, new com.wsteam.wandscape.building.network.ShopOpenPacket(
                                pos, colonyId, buildingId(state), stock, maxStocks));
            }
            case "tavern" -> {
                java.util.List<com.wsteam.wandscape.shared.data.MageResume> resumes = java.util.List.of();
                try {
                    var tavernApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getTavernApi();
                    resumes = tavernApi.getMageResumes(colonyId);
                } catch (IllegalStateException ignored) {}
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        player, new com.wsteam.wandscape.building.network.TavernOpenPacket(
                                pos, colonyId, resumes));
            }
            default -> {
                String status = "[Overview] " + state.getBuildingTypeId()
                        + " | intact=" + state.isStructureIntact()
                        + " | shutdown=" + state.isShutdown();
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(status), false);
            }
        }
    }

    private static java.util.UUID buildingId(BuildingState state) {
        return state.getBuildingId();
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, OverviewInteractPacket pkt) {
        buf.writeBlockPos(pkt.buildingBlockPos);
    }

    static OverviewInteractPacket read(RegistryFriendlyByteBuf buf) {
        return new OverviewInteractPacket(buf.readBlockPos());
    }
}
