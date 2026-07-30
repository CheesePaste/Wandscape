package com.wsteam.wandscape.building.scanner.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig.BoundaryBox;
import com.wsteam.wandscape.building.scanner.BuildingScannerBlockEntity;
import com.wsteam.wandscape.building.scanner.BuildingScannerBlockEntity.ShopGoodData;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Requests that the server scan the building and export a JSON file.
 * The server reads the scanner BE, scans world blocks, builds a JSON matching
 * the building config format, and writes it to wandscape_buildings/&lt;id&gt;.json.
 */
public record BuildingScannerExportPacket(BlockPos pos) implements CustomPacketPayload {

    private static final String TAG = "ScannerExport";

    public static final Type<BuildingScannerExportPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "scanner_export"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingScannerExportPacket> STREAM_CODEC =
            StreamCodec.of(BuildingScannerExportPacket::write, BuildingScannerExportPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(BuildingScannerExportPacket packet, ServerPlayer player) {
        if (player == null) return;
        ServerLevel level = player.serverLevel();
        BlockEntity be = level.getBlockEntity(packet.pos);
        if (!(be instanceof BuildingScannerBlockEntity scanner)) {
            Log.warn(TAG, "No scanner BE at {}", packet.pos);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§cNo scanner found at " + packet.pos));
            return;
        }

        String id = scanner.getBuildingId();
        if (id.isBlank()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§cSet a building ID before exporting"));
            return;
        }

        BlockPos wMin = scanner.getWorldMin();
        BlockPos wMax = scanner.getWorldMax();
        if (wMin == null || wMax == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§cNo boundary defined"));
            return;
        }

        // Scan blocks in world
        List<BlockOffset> pattern = new ArrayList<>();
        Map<String, String> blockMapping = new TreeMap<>(); // sorted for visual order

        for (int x = wMin.getX(); x <= wMax.getX(); x++) {
            for (int y = wMin.getY(); y <= wMax.getY(); y++) {
                for (int z = wMin.getZ(); z <= wMax.getZ(); z++) {
                    BlockPos bp = new BlockPos(x, y, z);
                    // Skip the scanner block itself
                    if (bp.equals(packet.pos)) continue;
                    BlockState state = level.getBlockState(bp);
                    if (state.isAir()) continue;

                    int rx = x - wMin.getX() + scanner.getBoundaryMin().x();
                    int ry = y - wMin.getY() + scanner.getBoundaryMin().y();
                    int rz = z - wMin.getZ() + scanner.getBoundaryMin().z();

                    BlockOffset offset = BlockOffset.of(rx, ry, rz);
                    pattern.add(offset);

                    String blockId = blockId(state);
                    blockMapping.put(rx + "," + ry + "," + rz, blockId);
                }
            }
        }

        scanner.setScanned(true);
        scanner.setChanged();
        level.sendBlockUpdated(packet.pos, scanner.getBlockState(), scanner.getBlockState(), 3);

        // Build JSON
        JsonObject root = new JsonObject();
        root.addProperty("id", id);
        root.addProperty("display_name", scanner.getDisplayName());
        root.addProperty("category", scanner.getCategory());

        // Pattern
        JsonArray patternArr = new JsonArray();
        for (BlockOffset off : pattern) {
            JsonArray arr = new JsonArray();
            arr.add(off.x());
            arr.add(off.y());
            arr.add(off.z());
            patternArr.add(arr);
        }
        root.add("pattern", patternArr);

        // Block mapping
        JsonObject bmObj = new JsonObject();
        for (var entry : blockMapping.entrySet()) {
            bmObj.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("block_mapping", bmObj);

        // Meta
        root.addProperty("comfort", scanner.getComfort());
        root.addProperty("magic", scanner.getMagic());
        root.addProperty("wonder", scanner.getWonder());

        // Maintenance cost
        if (!scanner.getMaintenanceCost().isEmpty()) {
            JsonObject mcObj = new JsonObject();
            JsonObject costs = new JsonObject();
            for (var entry : scanner.getMaintenanceCost().entrySet()) {
                costs.addProperty(entry.getKey(), entry.getValue());
            }
            mcObj.add("costs", costs);
            root.add("maintenance_cost", mcObj);
        }

        // Queue
        JsonObject queue = new JsonObject();
        queue.addProperty("capacity", 5);
        JsonArray taskTypes = new JsonArray();
        taskTypes.add("building");
        queue.add("task_types", taskTypes);
        root.add("queue", queue);

        // Unlock requirement
        JsonObject unlock = new JsonObject();
        unlock.addProperty("min_colony_level", scanner.getUnlockMinLevel());
        root.add("unlock_requirement", unlock);

        // Boundary
        JsonObject boundary = new JsonObject();
        JsonArray bMin = new JsonArray();
        bMin.add(scanner.getBoundaryMin().x());
        bMin.add(scanner.getBoundaryMin().y());
        bMin.add(scanner.getBoundaryMin().z());
        boundary.add("min", bMin);
        JsonArray bMax = new JsonArray();
        bMax.add(scanner.getBoundaryMax().x());
        bMax.add(scanner.getBoundaryMax().y());
        bMax.add(scanner.getBoundaryMax().z());
        boundary.add("max", bMax);
        root.add("boundary", boundary);

        // Door offset
        BlockOffset door = scanner.getDoorOffset();
        if (door != null) {
            JsonArray dArr = new JsonArray();
            dArr.add(door.x());
            dArr.add(door.y());
            dArr.add(door.z());
            root.add("door_offset", dArr);
        }

        // Interact AABB
        if (!scanner.getInteractZones().isEmpty()) {
            JsonArray zonesArr = new JsonArray();
            for (BoundaryBox zone : scanner.getInteractZones()) {
                JsonObject zObj = new JsonObject();
                JsonArray zMin = new JsonArray();
                zMin.add(zone.min().x());
                zMin.add(zone.min().y());
                zMin.add(zone.min().z());
                zObj.add("min", zMin);
                JsonArray zMax = new JsonArray();
                zMax.add(zone.max().x());
                zMax.add(zone.max().y());
                zMax.add(zone.max().z());
                zObj.add("max", zMax);
                zonesArr.add(zObj);
            }
            root.add("interact_aabb", zonesArr);
        }

        // Category-specific configs
        if ("node".equals(scanner.getCategory())) {
            JsonObject nc = new JsonObject();
            nc.addProperty("blueprint", scanner.getNodeBlueprint());
            nc.addProperty("element", scanner.getNodeElement());
            nc.addProperty("amount_per_harvest", scanner.getNodeAmountPerHarvest());
            nc.addProperty("channel_ticks", scanner.getNodeChannelTicks());
            nc.addProperty("mana_cost", scanner.getNodeManaCost());
            root.add("node_config", nc);
        }

        if ("shop".equals(scanner.getCategory())) {
            JsonObject shop = new JsonObject();
            JsonArray goods = new JsonArray();
            for (ShopGoodData g : scanner.getShopGoods()) {
                JsonObject gt = new JsonObject();
                gt.addProperty("item_id", g.itemId());
                JsonObject rcObj = new JsonObject();
                for (var entry : g.restockCost().entrySet()) {
                    rcObj.addProperty(entry.getKey(), entry.getValue());
                }
                gt.add("restock_cost", rcObj);
                gt.addProperty("comfort", g.comfort());
                gt.addProperty("magic", g.magic());
                gt.addProperty("wonder", g.wonder());
                goods.add(gt);
            }
            shop.add("goods", goods);
            shop.addProperty("profit_rate", scanner.getShopProfitRate());
            shop.addProperty("interaction_duration_ticks", scanner.getShopInteractionDurationTicks());
            root.add("shop", shop);
        }

        if ("service".equals(scanner.getCategory())) {
            JsonObject svc = new JsonObject();
            svc.addProperty("energy_per_use", scanner.getServiceEnergyPerUse());
            if (!scanner.getServiceElementOutput().isEmpty()) {
                JsonObject eo = new JsonObject();
                for (var entry : scanner.getServiceElementOutput().entrySet()) {
                    eo.addProperty(entry.getKey(), entry.getValue());
                }
                svc.add("element_output", eo);
            }
            svc.addProperty("max_occupancy", scanner.getServiceMaxOccupancy());
            svc.addProperty("interaction_duration_ticks", scanner.getServiceInteractionDurationTicks());
            root.add("service", svc);
        }

        // Blueprint reference
        JsonObject bp = new JsonObject();
        bp.addProperty("id", "build:clear_and_build");
        JsonObject bind = new JsonObject();
        bind.addProperty("offsets", "$pattern");
        bind.addProperty("blocks", "$block_mapping");
        bind.addProperty("name", "$display_name");
        bp.add("bind", bind);
        root.add("blueprint", bp);

        // Write file
        try {
            Path exportDir = level.getServer().getServerDirectory()
                    .resolve("wandscape_buildings");
            Files.createDirectories(exportDir);
            Path outFile = exportDir.resolve(id + ".json");

            String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
            Files.writeString(outFile, json);

            Log.info(TAG, "Exported building '{}' to {}", id, outFile.toAbsolutePath());
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§aExported building '" + id + "' to §e" + outFile.toAbsolutePath()));
        } catch (IOException e) {
            Log.warn(TAG, "Failed to export building '{}'", id, e);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§cFailed to export: " + e.getMessage()));
        }
    }

    /** Get the registry name of a block, with non-default blockstate properties. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String blockId(BlockState state) {
        Block block = state.getBlock();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        BlockState defaultState = block.defaultBlockState();

        StringBuilder props = new StringBuilder();
        for (Property prop : state.getProperties()) {
            Comparable current = state.getValue(prop);
            Comparable def = defaultState.getValue(prop);
            if (!current.equals(def)) {
                if (props.length() > 0) props.append(",");
                props.append(prop.getName()).append("=");
                props.append(prop.getName(current));
            }
        }
        if (props.length() > 0) {
            return id + "[" + props + "]";
        }
        return id.toString();
    }

    private static void write(RegistryFriendlyByteBuf buf, BuildingScannerExportPacket pkt) {
        buf.writeBlockPos(pkt.pos);
    }

    private static BuildingScannerExportPacket read(RegistryFriendlyByteBuf buf) {
        return new BuildingScannerExportPacket(buf.readBlockPos());
    }
}
