package com.wsteam.wandscape.building.network;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.editor.BuildingEditorClientState;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→Client: Response to {@link BuildingEditorEnterPacket}.
 * Carries success/failure, existing building JSON (if editing), and body anchor.
 */
public record BuildingEditorEnterResponsePacket(
        boolean success,
        String errorMessage,
        String buildingId,
        String buildingJson,
        BlockPos bodyAnchor
) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<BuildingEditorEnterResponsePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "build_editor_enter_response"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingEditorEnterResponsePacket> STREAM_CODEC =
            StreamCodec.of(BuildingEditorEnterResponsePacket::write, BuildingEditorEnterResponsePacket::read);

    /** Create a denial response. */
    public static BuildingEditorEnterResponsePacket deny(String error) {
        return new BuildingEditorEnterResponsePacket(false, error, null, null, null);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Client handler ──

    public static void handleClient(BuildingEditorEnterResponsePacket packet) {
        if (!packet.success) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "[BuildEditor] §c" + (packet.errorMessage != null ? packet.errorMessage : "Unknown error")),
                        false);
            }
            return;
        }

        // Parse existing building JSON if provided
        if (packet.buildingJson != null && !packet.buildingJson.isEmpty()) {
            loadFromExistingJson(packet);
        } else {
            // New building mode: start with empty defaults
            BlockPos anchor = packet.bodyAnchor != null ? packet.bodyAnchor : BlockPos.ZERO;
            BuildingEditorClientState.enterEditMode(
                    anchor, anchor,
                    BlockOffset.of(0, 0, 0),  // anchor offset at origin
                    null, null,                 // no AABB yet
                    "", "", "basic"             // empty metadata
            );
        }

        LOGGER.info("[BuildEditor] Client entered editor. buildingId={} bodyAt={}",
                packet.buildingId, packet.bodyAnchor);
    }

    private static void loadFromExistingJson(BuildingEditorEnterResponsePacket packet) {
        try {
            var gson = new com.google.gson.GsonBuilder()
                    .registerTypeAdapter(BlockOffset.class, new BlockOffset.Deserializer())
                    .registerTypeAdapter(BuildingConfig.class, new BuildingConfig.Deserializer())
                    .create();
            BuildingConfig config = gson.fromJson(packet.buildingJson, BuildingConfig.class);

            BlockPos worldAnchor = packet.bodyAnchor != null ? packet.bodyAnchor : BlockPos.ZERO;
            BlockOffset anchorOff = config.boundary() != null
                    ? config.boundary().min()
                    : BlockOffset.of(0, 0, 0);
            BlockOffset editMin = config.boundary() != null
                    ? config.boundary().min()
                    : null;
            BlockOffset editMax = config.boundary() != null
                    ? config.boundary().max()
                    : null;

            BuildingEditorClientState.enterEditMode(
                    worldAnchor, worldAnchor,
                    anchorOff, editMin, editMax,
                    config.id(), config.displayName(), config.category()
            );

            // Populate all fields from the config
            BuildingEditorClientState.setPattern(config.pattern());
            BuildingEditorClientState.setBlockMapping(config.blockMapping());
            BuildingEditorClientState.setComfort(config.comfort());
            BuildingEditorClientState.setMagic(config.magic());
            BuildingEditorClientState.setWonder(config.wonder());
            BuildingEditorClientState.setQueueCapacity(config.queue().capacity());
            BuildingEditorClientState.setTaskTypes(config.queue().taskTypes());
            BuildingEditorClientState.setUnlockMinComfort(config.unlockRequirement().minComfort());
            BuildingEditorClientState.setUnlockMinMagic(config.unlockRequirement().minMagic());
            BuildingEditorClientState.setUnlockMinWonder(config.unlockRequirement().minWonder());

            if (config.maintenanceCost() != null) {
                BuildingEditorClientState.setMaintenanceIntervalTicks(config.maintenanceCost().intervalTicks());
                if (config.maintenanceCost().costs() != null) {
                    BuildingEditorClientState.setMaintenanceCosts(
                            config.maintenanceCost().costs().entrySet().stream()
                                    .collect(java.util.stream.Collectors.toMap(
                                            java.util.Map.Entry::getKey,
                                            e -> (long) e.getValue())));
                }
            }

            if (config.blueprint() != null) {
                BuildingEditorClientState.setBlueprintId(config.blueprint().id());
                if (config.blueprint().bind() != null) {
                    BuildingEditorClientState.setBlueprintBind(config.blueprint().bind());
                }
            }

            BuildingEditorClientState.setInteractionRadius(config.interactionRadius());

            // Category-specific
            if (config.shop() != null && !config.shop().equals(
                    com.wsteam.wandscape.shared.data.ShopConfig.NONE)) {
                BuildingEditorClientState.setShopGoods(config.shop().goods());
                BuildingEditorClientState.setShopProfitRate(config.shop().profitRate());
            }
            if (config.service() != null && !config.service().equals(
                    com.wsteam.wandscape.shared.data.ServiceConfig.NONE)) {
                BuildingEditorClientState.setServiceEnergyPerUse(config.service().energyPerUse());
                BuildingEditorClientState.setServiceSatisfactionPerUse(config.service().satisfactionPerUse());
                BuildingEditorClientState.setServiceElementOutput(
                        config.service().elementOutput().entrySet().stream()
                                .collect(java.util.stream.Collectors.toMap(
                                        e -> com.wsteam.wandscape.shared.data.ElementType.fromId(e.getKey()),
                                        e -> (long) e.getValue())));
                BuildingEditorClientState.setServiceMaxOccupancy(config.service().maxOccupancy());
            }
            if (config.decoration() != null) {
                BuildingEditorClientState.setDecorationRadius(config.decoration().radius());
            }
            if (config.wonderConfig() != null && !config.wonderConfig().equals(
                    com.wsteam.wandscape.shared.data.WonderConfig.NONE)) {
                BuildingEditorClientState.setWonderEffects(config.wonderConfig().effects());
            }
            if (config.nodeConfig() != null) {
                BuildingEditorClientState.setNodeElement(config.nodeConfig().element());
                BuildingEditorClientState.setNodeAmountPerHarvest(config.nodeConfig().amountPerHarvest());
                BuildingEditorClientState.setNodeChannelTicks(config.nodeConfig().channelTicks());
                BuildingEditorClientState.setNodeManaCost(config.nodeConfig().manaCost());
                BuildingEditorClientState.setNodeBlueprint(config.nodeConfig().blueprint());
            }

            LOGGER.info("[BuildEditor] Loaded existing config: id={} category={} patternSize={}",
                    config.id(), config.category(), config.pattern().size());
        } catch (Exception e) {
            LOGGER.error("[BuildEditor] Failed to parse existing building JSON", e);
            // Fall back to new mode
            BlockPos anchor = packet.bodyAnchor != null ? packet.bodyAnchor : BlockPos.ZERO;
            BuildingEditorClientState.enterEditMode(
                    anchor, anchor,
                    BlockOffset.of(0, 0, 0),
                    null, null,
                    "", "", "basic"
            );
        }
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, BuildingEditorEnterResponsePacket pkt) {
        buf.writeBoolean(pkt.success);
        buf.writeUtf(pkt.errorMessage != null ? pkt.errorMessage : "");
        buf.writeUtf(pkt.buildingId != null ? pkt.buildingId : "");
        buf.writeUtf(pkt.buildingJson != null ? pkt.buildingJson : "");
        buf.writeBlockPos(pkt.bodyAnchor != null ? pkt.bodyAnchor : BlockPos.ZERO);
    }

    static BuildingEditorEnterResponsePacket read(RegistryFriendlyByteBuf buf) {
        boolean success = buf.readBoolean();
        String error = buf.readUtf();
        String id = buf.readUtf();
        String json = buf.readUtf();
        BlockPos anchor = buf.readBlockPos();
        // Return empty strings as null
        return new BuildingEditorEnterResponsePacket(
                success,
                error.isEmpty() ? null : error,
                id.isEmpty() ? null : id,
                json.isEmpty() ? null : json,
                anchor);
    }
}
