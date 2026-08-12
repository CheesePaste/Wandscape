package com.wsteam.wandscape.road.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.road.core.RoadBlobCache;
import com.wsteam.wandscape.road.core.RoadEdge;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.road.core.PathPoint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Persists the road network across world sessions via Minecraft {@link SavedData}.
 *
 * <p>Only edges are persisted. Nodes are rebuilt on load from
 * {@code BuildingSavedData} (building nodes) and intersection detection.
 */
public final class RoadSavedData extends SavedData {

    private static final String TAG = "RoadSavedData";
    private static final String DATA_NAME = "wandscape_roads";

    private final RoadNetwork network;
    private final RoadBlobCache blobCache;

    private RoadSavedData() {
        this.network = new RoadNetwork();
        this.blobCache = new RoadBlobCache();
    }

    // ---- Factory ----

    public static RoadSavedData getOrCreate(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(RoadSavedData::new, (tag, reg) -> load(tag)),
                DATA_NAME);
    }

    // ---- Accessors ----

    public RoadNetwork getNetwork() { return network; }
    public RoadBlobCache getBlobCache() { return blobCache; }

    /** Signal that data has changed and needs saving. */
    public void markChanged() {
        setDirty();
    }

    // ---- NBT save ----

    @Override
    public CompoundTag save(CompoundTag tag,
                            net.minecraft.core.HolderLookup.Provider registries) {
        ListTag edgeList = new ListTag();
        for (RoadEdge edge : network.getEdges().values()) {
            CompoundTag e = new CompoundTag();
            e.putUUID("edgeId", edge.getEdgeId());
            e.putUUID("fromNodeId", edge.getFromNodeId());
            e.putUUID("toNodeId", edge.getToNodeId());
            e.putString("tier", edge.getTier());
            e.putString("status", edge.getStatus().name());
            e.putInt("width", edge.getWidth());

            // SplineModel: list of {a: [x,y,z], p: [x,y,z], n: [x,y,z], l: bool}
            ListTag splineTag = new ListTag();
            com.wsteam.wandscape.road.core.SplineModel spline = edge.getSpline();
            if (spline != null) {
                for (com.wsteam.wandscape.road.core.SplinePoint sp : spline.getPoints()) {
                    CompoundTag spt = new CompoundTag();
                    
                    ListTag aTag = new ListTag();
                    aTag.add(net.minecraft.nbt.DoubleTag.valueOf(sp.getAnchor().x()));
                    aTag.add(net.minecraft.nbt.DoubleTag.valueOf(sp.getAnchor().y()));
                    aTag.add(net.minecraft.nbt.DoubleTag.valueOf(sp.getAnchor().z()));
                    spt.put("a", aTag);
                    
                    ListTag pTag = new ListTag();
                    pTag.add(net.minecraft.nbt.DoubleTag.valueOf(sp.getControlPrev().x()));
                    pTag.add(net.minecraft.nbt.DoubleTag.valueOf(sp.getControlPrev().y()));
                    pTag.add(net.minecraft.nbt.DoubleTag.valueOf(sp.getControlPrev().z()));
                    spt.put("p", pTag);
                    
                    ListTag nTag = new ListTag();
                    nTag.add(net.minecraft.nbt.DoubleTag.valueOf(sp.getControlNext().x()));
                    nTag.add(net.minecraft.nbt.DoubleTag.valueOf(sp.getControlNext().y()));
                    nTag.add(net.minecraft.nbt.DoubleTag.valueOf(sp.getControlNext().z()));
                    spt.put("n", nTag);
                    
                    spt.putBoolean("l", sp.isLocked());
                    splineTag.add(spt);
                }
            }
            e.put("spline", splineTag);

            // Segment task IDs
            List<Long> taskIds = edge.getSegmentTaskIds();
            long[] arr = new long[taskIds.size()];
            for (int i = 0; i < taskIds.size(); i++) {
                arr[i] = taskIds.get(i);
            }
            e.putLongArray("segmentTaskIds", arr);

            // Placed block positions (for clean demolition)
            ListTag placedTag = new ListTag();
            for (PathPoint bp : edge.getPlacedBlocks()) {
                CompoundTag bpTag = new CompoundTag();
                bpTag.putInt("x", bp.x());
                bpTag.putInt("y", bp.y());
                bpTag.putInt("z", bp.z());
                placedTag.add(bpTag);
            }
            e.put("placedBlocks", placedTag);

            edgeList.add(e);
        }
        tag.put("edges", edgeList);

        Log.info(TAG, "[RoadSavedData] saved edges={}", edgeList.size());
        return tag;
    }

    // ---- NBT load ----

    private static RoadSavedData load(CompoundTag tag) {
        RoadSavedData data = new RoadSavedData();

        ListTag edgeList = tag.getList("edges", Tag.TAG_COMPOUND);
        for (int i = 0; i < edgeList.size(); i++) {
            CompoundTag e = edgeList.getCompound(i);
            UUID edgeId = e.getUUID("edgeId");
            UUID fromNodeId = e.getUUID("fromNodeId");
            UUID toNodeId = e.getUUID("toNodeId");
            String tier = e.getString("tier");

            RoadEdge.EdgeStatus status;
            try {
                status = RoadEdge.EdgeStatus.valueOf(e.getString("status"));
            } catch (IllegalArgumentException ex) {
                status = RoadEdge.EdgeStatus.PLANNED;
            }

            // SplineModel
            com.wsteam.wandscape.road.core.SplineModel model = new com.wsteam.wandscape.road.core.SplineModel();
            if (e.contains("spline", Tag.TAG_LIST)) {
                ListTag splineTag = e.getList("spline", Tag.TAG_COMPOUND);
                for (int j = 0; j < splineTag.size(); j++) {
                    CompoundTag spt = splineTag.getCompound(j);
                    ListTag aTag = spt.getList("a", Tag.TAG_DOUBLE);
                    ListTag pTag = spt.getList("p", Tag.TAG_DOUBLE);
                    ListTag nTag = spt.getList("n", Tag.TAG_DOUBLE);
                    boolean locked = spt.getBoolean("l");
                    
                    com.wsteam.wandscape.road.core.SplineVec3 a = new com.wsteam.wandscape.road.core.SplineVec3(aTag.getDouble(0), aTag.getDouble(1), aTag.getDouble(2));
                    com.wsteam.wandscape.road.core.SplineVec3 p = new com.wsteam.wandscape.road.core.SplineVec3(pTag.getDouble(0), pTag.getDouble(1), pTag.getDouble(2));
                    com.wsteam.wandscape.road.core.SplineVec3 n = new com.wsteam.wandscape.road.core.SplineVec3(nTag.getDouble(0), nTag.getDouble(1), nTag.getDouble(2));
                    
                    model.getPoints().add(new com.wsteam.wandscape.road.core.SplinePoint(a, p, n, locked));
                }
            } else if (e.contains("path", Tag.TAG_LIST)) {
                // Fallback for legacy worlds (convert PathPoints to a linear spline)
                ListTag pathTag = e.getList("path", Tag.TAG_COMPOUND);
                for (int j = 0; j < pathTag.size(); j++) {
                    CompoundTag pt = pathTag.getCompound(j);
                    double px = pt.getInt("x") + 0.5;
                    double py = pt.getInt("y");
                    double pz = pt.getInt("z") + 0.5;
                    com.wsteam.wandscape.road.core.SplineVec3 v = new com.wsteam.wandscape.road.core.SplineVec3(px, py, pz);
                    model.getPoints().add(new com.wsteam.wandscape.road.core.SplinePoint(v, v, v, true));
                }
            }

            // Segment task IDs
            long[] taskIdArr = e.getLongArray("segmentTaskIds");
            List<Long> taskIds = new ArrayList<>();
            for (long tid : taskIdArr) {
                taskIds.add(tid);
            }

            RoadEdge edge = new RoadEdge(edgeId, fromNodeId, toNodeId,
                    tier, model, taskIds, status);

            if (e.contains("width")) {
                edge.setWidth(e.getInt("width"));
            }

            // Placed block positions (for clean demolition)
            if (e.contains("placedBlocks")) {
                List<PathPoint> placed = new ArrayList<>();
                ListTag placedTag = e.getList("placedBlocks", Tag.TAG_COMPOUND);
                for (int j = 0; j < placedTag.size(); j++) {
                    CompoundTag bpTag = placedTag.getCompound(j);
                    placed.add(new PathPoint(
                            bpTag.getInt("x"), bpTag.getInt("y"), bpTag.getInt("z")));
                }
                edge.setPlacedBlocks(placed);
            }

            data.network.addEdge(edge);
        }

        Log.info(TAG, "[RoadSavedData] loaded edges={}", edgeList.size());
        return data;
    }
}
