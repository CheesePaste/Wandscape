package com.wsteam.wandscape.building.scanner.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.scanner.CreativeScannerBlockEntity;
import com.wsteam.wandscape.building.scanner.CreativeScannerBlockEntity.ShopGoodData;
import com.wsteam.wandscape.building.scanner.InteractSpotMarkerBlock;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Requests that the server scan the building and export a JSON file.
 * The server reads the scanner BE, scans world blocks, builds a JSON matching
 * the building config format, and writes it to wandscape_buildings/&lt;id&gt;.json.
 */
public record ScannerExportPacket(BlockPos pos) implements CustomPacketPayload {

    private static final String TAG = "ScannerExport";

    /**
     * 装饰实体白名单：实体（非方块）类装饰，方块三重循环扫不到，需单独按 AABB 查询。
     * 三者都是 BlockAttachedEntity（NBT 用 TileX/TileY/TileZ 存挂靠块坐标），机制统一。
     */
    private static final Set<String> DECORATION_TYPES = Set.of(
            "minecraft:item_frame", "minecraft:glow_item_frame", "minecraft:painting");

    public static final Type<ScannerExportPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "scanner_export"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScannerExportPacket> STREAM_CODEC =
            StreamCodec.of(ScannerExportPacket::write, ScannerExportPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(ScannerExportPacket packet, ServerPlayer player) {
        if (player == null) return;
        ServerLevel level = player.serverLevel();
        BlockEntity be = level.getBlockEntity(packet.pos);
        if (!(be instanceof CreativeScannerBlockEntity scanner)) {
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

        // Auto-detect boundary from CORNER blocks if in SAVE mode
        scanner.detectBoundaryFromCorners(level);
        wMin = scanner.getWorldMin();
        wMax = scanner.getWorldMax();

        if (scanner.getTargetMode() == CreativeScannerBlockEntity.TargetMode.ROAD) {
            exportRoad(scanner, packet, player, level, wMin, wMax);
            return;
        }

        // Scan blocks in world
        List<BlockOffset> pattern = new ArrayList<>();
        List<String> palette = new ArrayList<>();          // 唯一方块状态，首次出现序
        java.util.Map<String, Integer> paletteIndex = new HashMap<>(); // blockstate → palette idx
        List<Integer> blockIndices = new ArrayList<>();    // pattern 对齐的 palette 索引
        Map<String, String> blockNbt = new TreeMap<>();    // base64-encoded BlockEntity NBT

        for (int x = wMin.getX(); x <= wMax.getX(); x++) {
            for (int y = wMin.getY(); y <= wMax.getY(); y++) {
                for (int z = wMin.getZ(); z <= wMax.getZ(); z++) {
                    BlockPos bp = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(bp);
                    // Skip all scanner blocks (SAVE or CORNER), interact spot markers, and air
                    if (state.isAir()) continue;
                    if (state.is(com.wsteam.wandscape.Wandscape.BUILDING_SCANNER.get())
                            || state.is(com.wsteam.wandscape.Wandscape.CREATIVE_BUILDING_SCANNER.get())
                            || state.is(com.wsteam.wandscape.Wandscape.INTERACT_SPOT_MARKER.get())) continue;

                    int rx = x - wMin.getX() + scanner.getBoundaryMin().x();
                    int ry = y - wMin.getY() + scanner.getBoundaryMin().y();
                    int rz = z - wMin.getZ() + scanner.getBoundaryMin().z();
                    String key = rx + "," + ry + "," + rz;

                    BlockOffset offset = BlockOffset.of(rx, ry, rz);
                    pattern.add(offset);

                    String blockId = blockId(state);
                    Integer pi = paletteIndex.get(blockId);
                    if (pi == null) {
                        pi = palette.size();
                        palette.add(blockId);
                        paletteIndex.put(blockId, pi);
                    }
                    blockIndices.add(pi);

                    // Save BlockEntity NBT if present
                    BlockEntity blockEntity = level.getBlockEntity(bp);
                    if (blockEntity != null) {
                        try {
                            CompoundTag tag = blockEntity.saveWithFullMetadata(level.registryAccess());
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            NbtIo.writeCompressed(tag, baos);
                            String b64 = Base64.getEncoder().encodeToString(baos.toByteArray());
                            blockNbt.put(key, b64);
                        } catch (IOException e) {
                            Log.warn(TAG, "Failed to serialize BlockEntity NBT at {}: {}", bp, e.toString());
                        }
                    }
                }
            }
        }

        // Scan decoration entities (item frames, paintings) inside the boundary.
        // They are entities, not blocks — the block loop above cannot see them.
        // offset = 实体块坐标 − 扫描器坐标（与 block_mapping 同约定）。
        List<JsonObject> entities = new ArrayList<>();
        List<Entity> decorations = level.getEntities((Entity) null,
                new AABB(wMin.getX(), wMin.getY(), wMin.getZ(),
                        wMax.getX() + 1.0, wMax.getY() + 1.0, wMax.getZ() + 1.0),
                e -> DECORATION_TYPES.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString()));
        for (Entity e : decorations) {
            try {
                BlockPos epos = e.blockPosition();
                int rx = epos.getX() - scanner.getBlockPos().getX();
                int ry = epos.getY() - scanner.getBlockPos().getY();
                int rz = epos.getZ() - scanner.getBlockPos().getZ();
                String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();

                // Trim entity NBT: strip UUID/Pos/Motion, rebase position to the
                // relative offset so the export is independent of absolute coords.
                CompoundTag tag = e.saveWithoutId(new CompoundTag());
                tag.remove("UUID");
                tag.remove("Pos");
                tag.remove("Motion");
                tag.putString("id", typeId);
                tag.putInt("TileX", rx);
                tag.putInt("TileY", ry);
                tag.putInt("TileZ", rz);
                ListTag posList = new ListTag();
                posList.add(DoubleTag.valueOf(rx + 0.5));
                posList.add(DoubleTag.valueOf(ry + 0.5));
                posList.add(DoubleTag.valueOf(rz + 0.5));
                tag.put("Pos", posList);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                NbtIo.writeCompressed(tag, baos);

                JsonObject ent = new JsonObject();
                JsonArray offArr = new JsonArray();
                offArr.add(rx);
                offArr.add(ry);
                offArr.add(rz);
                ent.add("offset", offArr);
                ent.addProperty("type", typeId);
                ent.addProperty("facing", e.getDirection().getName());
                ent.addProperty("nbt", Base64.getEncoder().encodeToString(baos.toByteArray()));
                entities.add(ent);
            } catch (IOException ex) {
                Log.warn(TAG, "Failed to serialize decoration entity at {}: {}",
                        e.blockPosition(), ex.toString());
            }
        }

        scanner.setScanned(true);
        scanner.setChanged();
        level.sendBlockUpdated(packet.pos, scanner.getBlockState(), scanner.getBlockState(), 3);

        // Build JSON
        JsonObject root = new JsonObject();
        root.addProperty("id", id);
        root.addProperty("display_name", scanner.getDisplayName());
        if (scanner.getCreator() != null && !scanner.getCreator().isBlank()) {
            root.addProperty("creator", scanner.getCreator());
        }
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

        // Palette + block_indices (parallel to pattern)
        JsonArray paletteArr = new JsonArray();
        for (String bid : palette) {
            paletteArr.add(bid);
        }
        root.add("palette", paletteArr);
        JsonArray idxArr = new JsonArray();
        for (int i : blockIndices) {
            idxArr.add(i);
        }
        root.add("block_indices", idxArr);

        // Block NBT (base64-encoded BlockEntity data)
        if (!blockNbt.isEmpty()) {
            JsonObject nbtObj = new JsonObject();
            for (var entry : blockNbt.entrySet()) {
                nbtObj.addProperty(entry.getKey(), entry.getValue());
            }
            root.add("block_nbt", nbtObj);
        }

        // Decoration entities (item frames / paintings), rebuilt via spawn_entity step
        if (!entities.isEmpty()) {
            JsonArray entitiesArr = new JsonArray();
            for (JsonObject ent : entities) {
                entitiesArr.add(ent);
            }
            root.add("entities", entitiesArr);
        }

        // Meta
        root.addProperty("comfort", scanner.getComfort());
        root.addProperty("magic", scanner.getMagic());
        root.addProperty("wonder", scanner.getWonder());

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

        // Interact spots: 扫 boundary 内 interact_spot_marker → interact_spots（相对 anchor 偏移 + 动作小写）。
        // marker 占格即 spot 格（创作者自行留空），marker 已从 pattern 跳过。
        JsonArray spotsArr = new JsonArray();
        for (int x = wMin.getX(); x <= wMax.getX(); x++) {
            for (int y = wMin.getY(); y <= wMax.getY(); y++) {
                for (int z = wMin.getZ(); z <= wMax.getZ(); z++) {
                    BlockState st = level.getBlockState(new BlockPos(x, y, z));
                    if (!st.is(com.wsteam.wandscape.Wandscape.INTERACT_SPOT_MARKER.get())) continue;
                    JsonObject sObj = new JsonObject();
                    JsonArray posArr = new JsonArray();
                    posArr.add(x - scanner.getBlockPos().getX());
                    posArr.add(y - scanner.getBlockPos().getY());
                    posArr.add(z - scanner.getBlockPos().getZ());
                    sObj.add("pos", posArr);
                    sObj.addProperty("action", InteractSpotMarkerBlock.spotActionOrBrowse(st).toJsonString());
                    sObj.addProperty("facing", st.getValue(InteractSpotMarkerBlock.FACING).getName());
                    spotsArr.add(sObj);
                }
            }
        }
        if (!spotsArr.isEmpty()) {
            root.add("interact_spots", spotsArr);
        }

        // Category-specific configs
        if ("node".equals(scanner.getCategory())) {
            JsonObject nc = new JsonObject();
            String bp = scanner.getNodeBlueprint();
            nc.addProperty("blueprint", bp.isBlank() ? "node:gather" : bp);
            nc.addProperty("element", scanner.getNodeElement());
            nc.addProperty("amount_per_harvest", scanner.getNodeAmountPerHarvest());
            nc.addProperty("channel_ticks", scanner.getNodeChannelTicks());
            root.add("node_config", nc);
        }

        if ("shop".equals(scanner.getCategory())) {
            JsonObject shop = new JsonObject();
            JsonArray goods = new JsonArray();
            for (ShopGoodData g : scanner.getShopGoods()) {
                JsonObject gt = new JsonObject();
                gt.addProperty("item_id", g.itemId());
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

        if ("relax".equals(scanner.getCategory())) {
            JsonObject rx = new JsonObject();
            rx.addProperty("energy_restore", scanner.getRelaxEnergyRestore());
            rx.addProperty("interaction_duration_ticks", scanner.getRelaxInteractionDurationTicks());
            root.add("relax", rx);
        }

        if ("atm".equals(scanner.getCategory())) {
            JsonObject ax = new JsonObject();
            ax.addProperty("withdraw_amount", scanner.getAtmWithdrawAmount());
            ax.addProperty("interaction_duration_ticks", scanner.getAtmInteractionDurationTicks());
            root.add("atm", ax);
        }

        // Blueprint reference
        JsonObject bp = new JsonObject();
        bp.addProperty("id", "build:clear_and_build");
        JsonObject bind = new JsonObject();
        bind.addProperty("offsets", "$pattern");
        bind.addProperty("blocks", "$block_mapping");
        bind.addProperty("blocks_nbt", "$block_nbt");
        bind.addProperty("entities", "$entities");
        bind.addProperty("name", "$display_name");
        bp.add("bind", bind);
        root.add("blueprint", bp);

        // Write file into the datapack-readable buildings directory so it can be built immediately
        // and ships with the mod jar (dev source resources) or stays readable via /reload (world datapack).
        try {
            Path exportDir = resolveDatapackDir(level, "buildings", "wandscape_builds");
            Files.createDirectories(exportDir);
            Path outFile = exportDir.resolve(sanitizeFileName(id) + ".json");

            String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
            Files.writeString(outFile, json);

            // Register in-memory so the building is buildable right now, no /reload needed.
            com.wsteam.wandscape.building.internal.BuildingConfigLoader.getInstance().registerFromJson(root);

            Log.info(TAG, "Exported building '{}' to {} (runtime-registered)", id, outFile.toAbsolutePath());
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§aExported building '" + id + "' to §e" + outFile.toAbsolutePath()
                    + "§a — 可立即建造，/reload 后依然有效"));
        } catch (IOException e) {
            Log.warn(TAG, "Failed to export building '{}'", id, e);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§cFailed to export: " + e.getMessage()));
        }
    }

    /**
     * Resolve the datapack directory for a config category (e.g. "buildings").
     *
     * <p>Exports always go into a <b>world datapack</b> ({@code <world>/datapacks/<fallbackPack>}):
     * the game loads world datapacks on every restart, so exported buildings/roads survive
     * quitting and re-entering in both dev and production. (Dev serves the mod's data from
     * {@code build/resources/main}, not {@code src/main/resources}, so writing into the source
     * folder was lost on relaunch.) {@code pack.mcmeta} makes the folder a valid, auto-enabled
     * datapack; without it the game ignores the folder entirely.
     */
    private static Path resolveDatapackDir(ServerLevel level, String category, String fallbackPack) throws IOException {
        Path packRoot = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR)
                .resolve(fallbackPack);
        ensurePackMeta(packRoot);
        return packRoot.resolve("data/wandscape/" + category);
    }

    /** Write a pack.mcmeta so the folder is recognized as a loadable world datapack. */
    private static void ensurePackMeta(Path packRoot) throws IOException {
        Path metaFile = packRoot.resolve("pack.mcmeta");
        if (Files.exists(metaFile)) return;
        Files.createDirectories(packRoot);
        int format = net.minecraft.SharedConstants.getCurrentVersion()
                .getPackVersion(net.minecraft.server.packs.PackType.SERVER_DATA);
        String meta = "{\n  \"pack\": {\n    \"pack_format\": " + format
                + ",\n    \"description\": \"Wandscape exported buildings & road presets\"\n  }\n}";
        Files.writeString(metaFile, meta);
    }

    private static void exportRoad(CreativeScannerBlockEntity scanner,
                                   ScannerExportPacket packet,
                                   ServerPlayer player,
                                   ServerLevel level,
                                   BlockPos wMin,
                                   BlockPos wMax) {
        String id = scanner.getBuildingId();
        if (id.isBlank()) id = "custom_road_" + (System.currentTimeMillis() % 1000);
        String name = scanner.getDisplayName();
        if (name.isBlank()) name = "自定义道路";

        java.util.Map<String, Integer> blockCounts = new java.util.HashMap<>();
        for (int x = wMin.getX(); x <= wMax.getX(); x++) {
            for (int y = wMin.getY(); y <= wMax.getY(); y++) {
                for (int z = wMin.getZ(); z <= wMax.getZ(); z++) {
                    BlockPos bp = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(bp);
                    if (state.isAir()) continue;
                    if (state.is(com.wsteam.wandscape.Wandscape.BUILDING_SCANNER.get())
                            || state.is(com.wsteam.wandscape.Wandscape.CREATIVE_BUILDING_SCANNER.get())) continue;
                    String bId = blockId(state);
                    blockCounts.put(bId, blockCounts.getOrDefault(bId, 0) + 1);
                }
            }
        }

        if (blockCounts.isEmpty()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§cNo road blocks found inside boundary box"));
            return;
        }

        JsonObject root = new JsonObject();
        root.addProperty("id", id);
        root.addProperty("display_name", name);
        root.addProperty("category", "road_preset");

        JsonArray blocksArr = new JsonArray();
        for (var entry : blockCounts.entrySet()) {
            JsonObject bObj = new JsonObject();
            bObj.addProperty("blockId", entry.getKey());
            bObj.addProperty("weight", entry.getValue());
            blocksArr.add(bObj);
        }
        root.add("blocks", blocksArr);

        try {
            Path exportDir = resolveDatapackDir(level, "road_presets", "wandscape_roads");
            Files.createDirectories(exportDir);
            Path outFile = exportDir.resolve(sanitizeFileName(id) + ".json");
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
            Files.writeString(outFile, json);

            // Register in-memory so the preset is usable immediately, no /reload needed.
            com.wsteam.wandscape.road.data.RoadPresetLoader.getInstance().registerFromJson(root);

            Log.info(TAG, "Exported road preset '{}' to {} (runtime-registered)", id, outFile.toAbsolutePath());
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§aExported road preset '" + id + "' to §e" + outFile.toAbsolutePath()
                    + "§a — 可立即使用，/reload 后依然有效"));
        } catch (IOException e) {
            Log.warn(TAG, "Failed to export road preset '{}'", id, e);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§cFailed to export road preset: " + e.getMessage()));
        }
    }

    private static String sanitizeFileName(String id) {
        if (id == null || id.isBlank()) return "export_" + (System.currentTimeMillis() % 1000);
        return id.replaceAll("[^a-zA-Z0-9_\\-]", "_");
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

    private static void write(RegistryFriendlyByteBuf buf, ScannerExportPacket pkt) {
        buf.writeBlockPos(pkt.pos);
    }

    private static ScannerExportPacket read(RegistryFriendlyByteBuf buf) {
        return new ScannerExportPacket(buf.readBlockPos());
    }
}
