package com.wsteam.wandscape.content.building.client;

import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.shared.client.render.BuildingGhostRenderer;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.network.BuildingAreaSyncPacket;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * World-space renderer that shows a semi-transparent ghost footprint for every
 * building that is registered but not yet construction-complete.
 *
 * <p>Visible while the Wandscape panel is open, giving the player a clear
 * footprint to align the next building against. The ghost is drawn from a
 * pre-baked GPU VBO (same path as the projection placement preview,
 * {@link BuildingGhostRenderer#renderGhostVboSkipped}), skipping cells already
 * placed in the world.
 */
public final class ConstructionGhostRenderer {

    private static final String TAG = "ConstructionGhostRenderer";

    private static boolean registered = false;

    private ConstructionGhostRenderer() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, ConstructionGhostRenderer::onRenderLevelStage);
        Log.info(TAG, "[Renderer] Registered");
    }

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        // Only show construction footprints while the panel is open (V mode / placement).
        if (!WandscapePanelState.isPanelOpen()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        var buildings = BuildingAreaSyncPacket.getCached();
        if (buildings.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 camPos = event.getCamera().getPosition();

        for (var entry : buildings) {
            if (entry.completed()) continue;
            BuildingConfig config = BuildingConfigLoader.getInstance().get(entry.buildingTypeId());
            if (config == null) continue;

            // Per-building frustum cull — boundary from the packet is pre-rotated.
            if (entry.hasBoundary()) {
                BlockPos anchor = entry.anchor();
                AABB aabb = new AABB(
                        anchor.getX() + entry.bMinX(),
                        anchor.getY() + entry.bMinY(),
                        anchor.getZ() + entry.bMinZ(),
                        anchor.getX() + entry.bMaxX() + 1,
                        anchor.getY() + entry.bMaxY() + 1,
                        anchor.getZ() + entry.bMaxZ() + 1);
                if (!event.getFrustum().isVisible(aabb)) continue;
            }

            BuildingGhostRenderer.renderGhostVboSkipped(mc, event.getModelViewMatrix(), event.getProjectionMatrix(),
                    camPos, entry.anchor(), config, entry.rotationSteps());

            // Animated blocks (chests etc.) can't bake into the VBO — render them
            // per-frame via their block-entity item renderer, skipping already-placed cells.
            BuildingGhostRenderer.renderGhostAnimated(mc, event.getPoseStack(),
                    mc.renderBuffers().bufferSource(), camPos, entry.anchor(), config,
                    entry.rotationSteps(), true);
        }
    }
}
