package com.wsteam.wandscape.road.client;

import java.util.List;

import com.wsteam.wandscape.road.data.RoadPreset;
import com.wsteam.wandscape.road.data.RoadPresetLoader;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.network.RoadAreaSyncPacket;
import com.wsteam.wandscape.shared.ui.util.BuildingPreviewRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-side ghost of roads still under construction, driven by the server
 * sync in {@link RoadAreaSyncPacket} — the mirror of the building construction
 * ghost. Renders translucent block ghosts for every non-COMPLETE road edge,
 * skipping cells whose target block is already in the world, so the ghost fades
 * cell by cell as the road is built.
 */
public final class RoadConstructionGhost {

    private static final String TAG = "RoadConstructionGhost";
    private static final float GHOST_ALPHA = 0.55f;
    /** Skip roads farther than this from the camera (avoids unloaded-chunk work). */
    private static final double MAX_RENDER_DIST = 128.0;

    private static Level lastLevel = null;
    private static boolean registered = false;

    private RoadConstructionGhost() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, RoadConstructionGhost::onRenderLevelStage);
        Log.info(TAG, "[RoadConstructionGhost] Registered");
    }

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return;

        // The cached sync is for the previous world/dimension — drop it on world change;
        // the next panel-open / placement sync repopulates it.
        if (lastLevel != level) {
            lastLevel = level;
            RoadAreaSyncPacket.clearCache();
        }

        List<RoadAreaSyncPacket.RoadEntry> roads = RoadAreaSyncPacket.getCached();
        if (roads.isEmpty()) return;

        Vec3 camPos = event.getCamera().getPosition();
        var poseStack = event.getPoseStack();
        var bufferSource = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        MultiBufferSource ghostSource = RoadGhostRenderUtil.ghostSource(bufferSource, GHOST_ALPHA);

        for (RoadAreaSyncPacket.RoadEntry entry : roads) {
            RoadPreset preset = RoadPresetLoader.getInstance().get(entry.presetId());
            if (preset == null) continue;

            // Bounding box centre for the distance gate.
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (var tile : entry.tiles()) {
                if (tile.x() < minX) minX = tile.x();
                if (tile.x() > maxX) maxX = tile.x();
                if (tile.z() < minZ) minZ = tile.z();
                if (tile.z() > maxZ) maxZ = tile.z();
            }
            if (minX == Integer.MAX_VALUE) continue;
            double cx = (minX + maxX + 1) / 2.0;
            double cz = (minZ + maxZ + 1) / 2.0;
            double dx = cx - camPos.x;
            double dz = cz - camPos.z;
            if (dx * dx + dz * dz > MAX_RENDER_DIST * MAX_RENDER_DIST) continue;

            for (var tile : entry.tiles()) {
                BlockState target = BuildingPreviewRenderer.resolveBlockState(preset.pickBlock(tile.x(), tile.z()));
                if (target == null) continue;
                if (RoadGhostRenderUtil.isPlaced(level, tile.x(), tile.y(), tile.z(), target)) continue;
                RoadGhostRenderUtil.renderGhostBlock(level, target, poseStack, ghostSource,
                        tile.x(), tile.y(), tile.z());
            }
        }

        // AFTER_TRIPWIRE_BLOCKS fires after the level renderer flushed the buffer source.
        bufferSource.endBatch(RenderType.translucent());

        poseStack.popPose();
    }
}
