package com.wsteam.wandscape.content.road.engine;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.impl.WandscapeEngine;
import com.wsteam.wandscape.content.road.core.PathPoint;
import com.wsteam.wandscape.content.road.core.RoadEdge;
import com.wsteam.wandscape.content.road.core.RoadNetwork;
import com.wsteam.wandscape.api.RoadApi;
import com.wsteam.wandscape.foundation.util.ItemKey;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.content.road.network.RoadAreaSyncPacket;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.*;

/**
 * Default implementation of {@link RoadApi}.
 * Delegates to {@link RoadSavedData}.
 */
public class RoadApiImpl implements RoadApi {

    private static final String TAG = "RoadApiImpl";

    @Override
    public RoadNetwork getNetwork(UUID colonyId) {
        try {
            return RoadSavedData.getOrCreate(
                    ServerLifecycleHooks.getCurrentServer().overworld())
                    .getNetwork();
        } catch (Exception e) {
            return new RoadNetwork();
        }
    }

    @Override
    public List<RoadEdge> getEdges(UUID colonyId) {
        return new ArrayList<>(getNetwork(colonyId).getEdges().values());
    }

    @Override
    public void removeEdge(UUID colonyId, UUID edgeId) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        RoadSavedData roadData = RoadSavedData.getOrCreate(server.overworld());
        RoadNetwork network = roadData.getNetwork();
        if (network.removeEdge(edgeId)) {
            roadData.markChanged();
        }
    }

    @Override
    public boolean cancelEdge(UUID colonyId, UUID edgeId) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return false;
        ServerLevel level = server.overworld();
        RoadSavedData roadData = RoadSavedData.getOrCreate(level);
        RoadNetwork network = roadData.getNetwork();

        RoadEdge edge = network.getEdge(edgeId);
        if (edge == null) return false;                 // idempotent: already withdrawn
        if (edge.getStatus() == RoadEdge.EdgeStatus.COMPLETE) return false; // completed can't withdraw

        // 1. Cancel the live segment task(s) so an NPC stops building it.
        var world = WandscapeEngine.getWorld();
        if (world != null && world.taskPool != null) {
            for (long taskId : edge.getSegmentTaskIds()) {
                world.taskPool.cancelTask(taskId, world);
            }
        }

        // 2. Clear only tiles that currently hold a road material block — leaves
        //    unbuilt terrain untouched. Placing air directly (no transform executor)
        //    means no salvage drops, so the ONLY return path is the warehouse
        //    refund below → no double refund, no mint.
        Set<String> roadMaterialPure = new HashSet<>();
        for (String key : edge.getMaterialCounts().keySet()) {
            roadMaterialPure.add(stripBrackets(key));
        }
        int placedTiles = 0;
        for (PathPoint p : edge.getPlacedBlocks()) {
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            if (roadMaterialPure.contains(stripBrackets(level.getBlockState(pos).getBlock()
                    .builtInRegistryHolder().key().location().toString()))) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                placedTiles++;
            }
        }

        // 3. Refund the full material demand exactly once, only if construction
        //    actually started (≥1 road tile present). Refunding only when started
        //    avoids minting materials that were never charged.
        if (placedTiles > 0 && colonyId != null) {
            ColonyItemBank bank = ColonyItemBank.get(level);
            if (bank != null) {
                for (var e : edge.getMaterialCounts().entrySet()) {
                    bank.add(colonyId, ItemKey.of(e.getKey(), null), e.getValue());
                }
                Log.info(TAG, "[Cancel] Refunded road edge {} materials ({}) to colony {}",
                        edgeId, edge.getMaterialCounts().size(), colonyId.toString().substring(0, 8));
            }
        }

        // 4. Remove the edge synchronously as the idempotency tombstone.
        if (network.removeEdge(edgeId)) {
            roadData.markChanged();
        }

        // 5. Refresh under-construction road ghosts across all clients.
        RoadAreaSyncPacket.broadcastToServer(server);
        return true;
    }

    private static String stripBrackets(String id) {
        return id.replaceAll("\\[.*?\\]", "").trim();
    }
}
