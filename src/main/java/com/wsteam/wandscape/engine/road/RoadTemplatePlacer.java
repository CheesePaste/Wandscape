package com.wsteam.wandscape.engine.road;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.road.CardinalFacing;
import com.wsteam.wandscape.core.road.EntryExit;
import com.wsteam.wandscape.core.road.TemplateMeta;
import com.wsteam.wandscape.core.road.TemplatePlacement;
import com.wsteam.wandscape.core.road.XZPoint;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Generates road tiles from NBT templates using vanilla-style terrain matching.
 *
 * <p>Each template is parsed, rotated, and its blocks are mapped to terrain
 * surface height (equivalent to {@code GravityProcessor(WORLD_SURFACE, -1)}).
 * Block variation rules are applied for a "worn path" aesthetic like vanilla.
 *
 * <p>Output tiles feed into {@code road:build_segment} for NPC construction.
 */
public final class RoadTemplatePlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RoadTemplatePlacer() {}

    public static JsonArray buildTiles(
            ServerLevel level,
            List<TemplatePlacement> placements,
            RoadTemplateMetaPool pool,
            Collection<BoundingBox> buildingBounds,
            Set<XZPoint> occupiedTiles) {

        JsonArray allTiles = new JsonArray();

        for (TemplatePlacement placement : placements) {
            TemplateMeta meta = pool.getMeta(placement.templateId());
            if (meta == null) {
                LOGGER.warn("[RoadTemplatePlacer] unknown template: {}", placement.templateId());
                continue;
            }

            NbtData nbtData = loadTemplateNbt(meta.templateRef());
            if (nbtData == null) continue;

            int rot = placement.rotation();
            int kept = 0;
            for (NbtBlockEntry entry : nbtData.blocks) {
                // Skip void/jigsaw/air
                if (entry.blockName.contains("structure_void")
                        || entry.blockName.contains("jigsaw")
                        || entry.blockName.contains("air")) {
                    continue;
                }

                // Rotate position
                int wx = placement.x();
                int wz = placement.z();
                // Rotation applied to local (dx, dz) relative to template origin
                int rdx = entry.x;
                int rdz = entry.z;
                for (int r = 0; r < (rot & 3); r++) {
                    int tmp = rdx;
                    rdx = rdz;
                    rdz = -tmp;
                }
                wx += rdx;
                wz += rdz;

                if (insideAnyBuilding(wx, wz, buildingBounds)) continue;

                XZPoint tileXz = new XZPoint(wx, wz);
                if (occupiedTiles.contains(tileXz)) continue;

                // Vanilla-style terrain matching: surface height - 1 + templateY
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
                int groundY = surfaceY - 1 + entry.y;
                BlockPos pos = new BlockPos(wx, groundY, wz);

                if (shouldSkip(pos, level)) continue;

                occupiedTiles.add(tileXz);

                // Block variation — "worn path" like vanilla STREET_PLAINS
                String block = applyVariation(level, pos, entry.blockName);

                JsonObject tile = new JsonObject();
                JsonArray arr = new JsonArray();
                arr.add(pos.getX()); arr.add(pos.getY()); arr.add(pos.getZ());
                tile.add("pos", arr);
                tile.addProperty("block", block);
                allTiles.add(tile);
                kept++;
            }

            LOGGER.info("[RoadTemplatePlacer] {} at ({},{}) rot={}: {} tiles",
                    placement.templateId(), placement.x(), placement.z(),
                    placement.rotation(), kept);
        }

        return allTiles;
    }

    // ---- Vanilla-style block variation ----

    /** Apply worn-path block variation matching vanilla STREET_PLAINS rules. */
    static String applyVariation(Level level, BlockPos roadPos, String blockId) {
        if (!"minecraft:dirt_path".equals(blockId)) return blockId;

        BlockPos below = roadPos.below();
        BlockState ground = level.getBlockState(below);

        // Water underneath → wood planks (bridge)
        if (!ground.getFluidState().isEmpty()) {
            return "minecraft:oak_planks";
        }

        String groundName = ground.getBlock().builtInRegistryHolder()
                .key().location().toString();

        // Grass → 85% dirt_path, 15% grass_block (worn patches)
        if ("minecraft:grass_block".equals(groundName)) {
            long h = ((long) roadPos.getX() * 31 + roadPos.getZ()) ^ 0x3A7F;
            if (Math.abs(h % 100) < 15) {
                return "minecraft:grass_block";
            }
        }

        // Stone/cobblestone → cobblestone (rocky path)
        if ("minecraft:stone".equals(groundName)
                || "minecraft:cobblestone".equals(groundName)) {
            return "minecraft:cobblestone";
        }

        return blockId;
    }

    // ---- NBT loading (from classpath) ----

    private static NbtData loadTemplateNbt(String templateRef) {
        try {
            String[] parts = templateRef.split(":", 2);
            String ns = parts.length == 2 ? parts[0] : "wandscape";
            String path = parts.length == 2 ? parts[1] : templateRef;
            String resourcePath = "data/" + ns + "/structure/" + path + ".nbt";

            var cl = RoadTemplatePlacer.class.getClassLoader();
            var stream = cl.getResourceAsStream(resourcePath);
            if (stream == null) {
                LOGGER.warn("[RoadTemplatePlacer] not found: {}", resourcePath);
                return null;
            }

            CompoundTag tag = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
            stream.close();
            return parseBlocks(tag);
        } catch (Exception e) {
            LOGGER.warn("[RoadTemplatePlacer] NBT load failed: {}", e.toString());
            return null;
        }
    }

    private static NbtData parseBlocks(CompoundTag root) {
        // Parse palette
        List<String> palette = new ArrayList<>();
        if (root.contains("palettes", 9)) {
            ListTag pt = root.getList("palettes", 9);
            if (!pt.isEmpty()) {
                ListTag p0 = pt.getList(0);
                for (int i = 0; i < p0.size(); i++) {
                    palette.add(p0.getCompound(i).getString("Name"));
                }
            }
        } else if (root.contains("palette", 9)) {
            ListTag pt = root.getList("palette", 9);
            for (int i = 0; i < pt.size(); i++) {
                palette.add(pt.getCompound(i).getString("Name"));
            }
        }

        // Parse blocks
        List<NbtBlockEntry> entries = new ArrayList<>();
        ListTag blocksTag = root.getList("blocks", 10);
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag bt = blocksTag.getCompound(i);
            ListTag posTag = bt.getList("pos", 3);
            int bx = posTag.getInt(0);
            int by = posTag.getInt(1);
            int bz = posTag.getInt(2);
            int si = bt.getInt("state");
            String name = (si >= 0 && si < palette.size()) ? palette.get(si) : "minecraft:air";
            entries.add(new NbtBlockEntry(bx, by, bz, name));
        }

        return new NbtData(entries);
    }

    // ---- Terrain ----

    private static boolean shouldSkip(BlockPos pos, Level level) {
        var state = level.getBlockState(pos);
        // Only skip if the position has fluid (water/lava). Solid blocks like grass
        // or dirt are REPLACED by the road, just like vanilla structure placement.
        return !state.getFluidState().isEmpty();
    }

    private static boolean insideAnyBuilding(int x, int z, Collection<BoundingBox> boxes) {
        for (BoundingBox box : boxes) {
            if (x >= box.minX() && x <= box.maxX()
                    && z >= box.minZ() && z <= box.maxZ()) return true;
        }
        return false;
    }

    // ---- Types ----

    private record NbtBlockEntry(int x, int y, int z, String blockName) {}
    private record NbtData(List<NbtBlockEntry> blocks) {}

    public static class RoadTemplateMetaPool {
        private final Map<String, TemplateMeta> metas = new HashMap<>();
        public RoadTemplateMetaPool(List<TemplateMeta> list) {
            for (TemplateMeta m : list) metas.put(m.id(), m);
        }
        public TemplateMeta getMeta(String id) { return metas.get(id); }
        public int size() { return metas.size(); }
    }
}
