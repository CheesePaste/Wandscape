package com.wsteam.wandscape.engine.road;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.shapes.Shapes;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Converts template placements from core/road/ into buildable tiles
 * by parsing NBT templates directly and applying terrain height logic.
 *
 * <p>Maintains the NPC tile-by-tile construction model — each template
 * is decomposed into individual block positions, wrapped as JsonArray
 * tiles for the {@code road:build_segment} blueprint.
 */
public final class RoadTemplatePlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RoadTemplatePlacer() {}

    /**
     * Convert a chain of template placements into road tiles.
     * Parses NBT templates, rotates positions, maps to terrain height,
     * filters passable tiles, and returns tile JSON for NPC construction.
     *
     * @param level          target level
     * @param placements     ordered template placements from core
     * @param pool           template pool for metadata lookups
     * @param buildingBounds bounding boxes to skip
     * @param occupiedTiles  mutable set of claimed XZ positions (updated in-place)
     * @return JsonArray of {pos, block} objects for blueprint injection
     */
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

            // Load NBT from resources
            NbtData nbtData = loadTemplateNbt(meta.templateRef());
            if (nbtData == null) {
                LOGGER.warn("[RoadTemplatePlacer] failed to load NBT: {}", meta.templateRef());
                continue;
            }

            // Generate tiles for this placement
            int rotationSteps = placement.rotation();
            for (NbtBlockEntry entry : nbtData.blocks) {
                // Skip invisible/structural blocks
                if (entry.blockName.contains("structure_void")
                        || entry.blockName.contains("jigsaw")
                        || entry.blockName.contains("air")) {
                    continue;
                }

                // Rotate position
                EntryExit local = new EntryExit(entry.x, entry.z, CardinalFacing.SOUTH);
                EntryExit rotated = local.rotate(rotationSteps);

                int worldX = placement.x() + rotated.dx();
                int worldZ = placement.z() + rotated.dz();

                // Skip tiles inside building bounds
                if (insideAnyBuilding(worldX, worldZ, buildingBounds)) continue;

                // Skip already-claimed positions
                XZPoint tileXz = new XZPoint(worldX, worldZ);
                if (occupiedTiles.contains(tileXz)) continue;

                // Compute terrain height
                int groundY = terrainHeightAt(level, worldX, worldZ);
                BlockPos pos = new BlockPos(worldX, groundY, worldZ);

                // Skip impassable positions
                if (!isPassable(pos, level)) continue;

                occupiedTiles.add(tileXz);

                JsonObject tile = new JsonObject();
                JsonArray posArr = new JsonArray();
                posArr.add(pos.getX());
                posArr.add(pos.getY());
                posArr.add(pos.getZ());
                tile.add("pos", posArr);
                tile.addProperty("block", entry.blockName);
                allTiles.add(tile);
            }
        }

        return allTiles;
    }

    // ---- NBT loading ----

    /** Load and parse a structure NBT file from the resource pack. */
    private static NbtData loadTemplateNbt(String templateRef) {
        try {
            // templateRef like "wandscape:road/straight" →
            // file "data/wandscape/structure/road/straight.nbt"
            String[] parts = templateRef.split(":", 2);
            String namespace = parts.length == 2 ? parts[0] : "wandscape";
            String path = parts.length == 2 ? parts[1] : templateRef;
            ResourceLocation resLoc = ResourceLocation.fromNamespaceAndPath(
                    namespace, path);

            var server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return null;
            var manager = server.getResourceManager();
            var optResource = manager.getResource(
                    ResourceLocation.fromNamespaceAndPath(namespace, "structure/" + path + ".nbt"));
            if (optResource.isEmpty()) {
                // Try vanilla namespace as fallback
                optResource = manager.getResource(
                        ResourceLocation.fromNamespaceAndPath("minecraft", "structure/" + templateRef + ".nbt"));
            }
            if (optResource.isEmpty()) return null;

            var inputStream = optResource.get().open();
            CompoundTag tag = NbtIo.readCompressed(inputStream, NbtAccounter.unlimitedHeap());
            inputStream.close();

            return parseBlocks(tag);
        } catch (Exception e) {
            LOGGER.warn("[RoadTemplatePlacer] NBT load failed for {}: {}",
                    templateRef, e.getMessage());
            return null;
        }
    }

    /** Extract block entries from a structure NBT compound. */
    private static NbtData parseBlocks(CompoundTag root) {
        // Read size
        ListTag sizeTag = root.getList("size", 3); // TAG_Int
        // size is [xSize, ySize, zSize]

        // Read palette
        List<PaletteEntry> palette = new ArrayList<>();
        if (root.contains("palettes", 9)) {
            ListTag palettesTag = root.getList("palettes", 9);
            if (!palettesTag.isEmpty()) {
                ListTag firstPal = palettesTag.getList(0);
                palette = readPalette(firstPal);
            }
        } else if (root.contains("palette", 10)) {
            ListTag palTag = root.getList("palette", 10);
            palette = readPalette(palTag);
        }

        // Read blocks
        List<NbtBlockEntry> blockEntries = new ArrayList<>();
        ListTag blocksTag = root.getList("blocks", 10); // TAG_Compound
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag blockTag = blocksTag.getCompound(i);
            ListTag posTag = blockTag.getList("pos", 3);
            int bx = posTag.getInt(0);
            int by = posTag.getInt(1);
            int bz = posTag.getInt(2);
            int stateIdx = blockTag.getInt("state");

            String blockName = "minecraft:air";
            if (stateIdx >= 0 && stateIdx < palette.size()) {
                blockName = palette.get(stateIdx).name;
            }

            blockEntries.add(new NbtBlockEntry(bx, by, bz, blockName));
        }

        return new NbtData(blockEntries);
    }

    private static List<PaletteEntry> readPalette(ListTag palTag) {
        List<PaletteEntry> palette = new ArrayList<>();
        for (int i = 0; i < palTag.size(); i++) {
            CompoundTag entry = palTag.getCompound(i);
            String name = entry.getString("Name");
            palette.add(new PaletteEntry(name));
        }
        return palette;
    }

    // ---- Terrain ----

    private static int terrainHeightAt(Level level, int x, int z) {
        int maxY = level.getMaxBuildHeight();
        int minY = level.getMinBuildHeight();
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos(x, maxY, z);
        while (mpos.getY() > minY) {
            mpos.setY(mpos.getY() - 1);
            var state = level.getBlockState(mpos);
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                return mpos.getY() + 1;
            }
        }
        return minY + 1;
    }

    private static boolean isPassable(BlockPos pos, Level level) {
        var state = level.getBlockState(pos);
        return (state.isAir() || state.canBeReplaced())
                && state.getFluidState().isEmpty();
    }

    private static boolean insideAnyBuilding(int x, int z,
                                              Collection<BoundingBox> boxes) {
        for (BoundingBox box : boxes) {
            if (x >= box.minX() && x <= box.maxX()
                    && z >= box.minZ() && z <= box.maxZ()) {
                return true;
            }
        }
        return false;
    }

    // ---- Internal types ----

    private record PaletteEntry(String name) {}

    private record NbtBlockEntry(int x, int y, int z, String blockName) {}

    private record NbtData(List<NbtBlockEntry> blocks) {}

    // ---- Pool wrapper ----

    public static class RoadTemplateMetaPool {
        private final Map<String, TemplateMeta> metas;

        public RoadTemplateMetaPool(List<TemplateMeta> metaList) {
            this.metas = new HashMap<>();
            for (TemplateMeta m : metaList) {
                metas.put(m.id(), m);
            }
        }

        public TemplateMeta getMeta(String id) {
            return metas.get(id);
        }

        public int size() {
            return metas.size();
        }
    }
}
