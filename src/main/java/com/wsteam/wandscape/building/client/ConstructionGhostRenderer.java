package com.wsteam.wandscape.building.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.shared.client.render.BuildingGhostRenderer;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.network.BuildingAreaSyncPacket;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * World-space renderer that shows a semi-transparent ghost footprint for every
 * building that is registered but not yet construction-complete.
 *
 * <p>Visible while the Wandscape panel is open, giving the player a clear
 * footprint to align the next building against. The ghost uses the same visual
 * style as the projection placement preview ({@link BuildingGhostRenderer}).
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
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        for (var entry : buildings) {
            if (entry.completed()) continue;
            BuildingConfig config = BuildingConfigLoader.getInstance().get(entry.buildingTypeId());
            if (config == null) continue;
            BuildingGhostRenderer.renderGhostBlocks(mc, buf, poseStack,
                    entry.anchor(), config, entry.rotationSteps(), true);
        }

        poseStack.popPose();
    }
}
