package com.wsteam.wandscape.engine.road;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.road.RoadEdge;
import com.wsteam.wandscape.core.road.RoadNetwork;
import com.wsteam.wandscape.core.road.XZPoint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persists the road network across world sessions via Minecraft {@link SavedData}.
 *
 * <p>Only edges are persisted. Nodes are rebuilt on load from
 * {@code BuildingSavedData} (building nodes) and intersection detection.
 */
public final class RoadSavedData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_NAME = "wandscape_roads";

    private final RoadNetwork network;
    private UUID colonyId;
    private int buildingCount;

    private RoadSavedData() {
        this.network = new RoadNetwork();
    }

    // ---- Factory ----

    public static RoadSavedData getOrCreate(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(RoadSavedData::new, (tag, reg) -> load(tag)),
                DATA_NAME);
    }

    // ---- Accessors ----

    public RoadNetwork getNetwork() { return network; }

    public UUID getColonyId() { return colonyId; }
    public void setColonyId(UUID colonyId) {
        this.colonyId = colonyId;
        setDirty();
    }

    public int getBuildingCount() { return buildingCount; }
    public void setBuildingCount(int count) {
        this.buildingCount = count;
        setDirty();
    }

    /** Signal that data has changed and needs saving. */
    public void markChanged() {
        setDirty();
    }

    // ---- NBT save ----

    @Override
    public CompoundTag save(CompoundTag tag,
                            net.minecraft.core.HolderLookup.Provider registries) {
        if (colonyId != null) {
            tag.putUUID("colonyId", colonyId);
        }
        tag.putInt("buildingCount", buildingCount);

        ListTag edgeList = new ListTag();
        for (RoadEdge edge : network.getEdges().values()) {
            CompoundTag e = new CompoundTag();
            e.putUUID("edgeId", edge.getEdgeId());
            e.putUUID("fromNodeId", edge.getFromNodeId());
            e.putUUID("toNodeId", edge.getToNodeId());
            e.putString("tier", edge.getTier());
            e.putString("status", edge.getStatus().name());

            // Path: list of {x, z} pairs (Y is recomputed on load)
            ListTag pathTag = new ListTag();
            for (XZPoint p : edge.getPath()) {
                CompoundTag pt = new CompoundTag();
                pt.putInt("x", p.x());
                pt.putInt("z", p.z());
                pathTag.add(pt);
            }
            e.put("path", pathTag);

            // Segment task IDs
            List<Long> taskIds = edge.getSegmentTaskIds();
            long[] arr = new long[taskIds.size()];
            for (int i = 0; i < taskIds.size(); i++) {
                arr[i] = taskIds.get(i);
            }
            e.putLongArray("segmentTaskIds", arr);

            edgeList.add(e);
        }
        tag.put("edges", edgeList);

        LOGGER.info("[RoadSavedData] saved colony={} buildingCount={} edges={}",
                colonyId, buildingCount, edgeList.size());
        return tag;
    }

    // ---- NBT load ----

    private static RoadSavedData load(CompoundTag tag) {
        RoadSavedData data = new RoadSavedData();

        if (tag.hasUUID("colonyId")) {
            data.colonyId = tag.getUUID("colonyId");
        }
        data.buildingCount = tag.getInt("buildingCount");

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

            // Path
            List<XZPoint> path = new ArrayList<>();
            ListTag pathTag = e.getList("path", Tag.TAG_COMPOUND);
            for (int j = 0; j < pathTag.size(); j++) {
                CompoundTag pt = pathTag.getCompound(j);
                path.add(new XZPoint(pt.getInt("x"), pt.getInt("z")));
            }

            // Segment task IDs
            long[] taskIdArr = e.getLongArray("segmentTaskIds");
            List<Long> taskIds = new ArrayList<>();
            for (long tid : taskIdArr) {
                taskIds.add(tid);
            }

            RoadEdge edge = new RoadEdge(edgeId, fromNodeId, toNodeId,
                    tier, path, taskIds, status);
            data.network.addEdge(edge);
        }

        LOGGER.info("[RoadSavedData] loaded colony={} buildingCount={} edges={}",
                data.colonyId, data.buildingCount, edgeList.size());
        return data;
    }
}
