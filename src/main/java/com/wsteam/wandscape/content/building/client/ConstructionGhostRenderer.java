package com.wsteam.wandscape.content.building.client;
import com.wsteam.wandscape.content.task.ecs.World;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.ClientConfig;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.content.building.render.BuildingGhostRenderer;
import com.wsteam.wandscape.content.building.render.BuildingGhostVboCache;
import com.wsteam.wandscape.content.building.render.BuildingOutline;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import com.wsteam.wandscape.content.building.network.BuildingAreaSyncPacket;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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
        Log.debug(LogCategory.BUILDING, "render", "ConstructionGhostRenderer Registered");
    }

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // 收掉没人再画的半成品烘焙作业。放在所有早退**之前**：面板关掉后这里仍每帧进来
        // （监听的是全阶段事件），而那时正是最需要收的时候。内部按 tick 限流，重复调用免费。
        BuildingGhostVboCache.sweepIdleJobs(mc);

        // Only show construction footprints while the panel is open (V mode / placement).
        if (!WandscapePanelState.isPanelOpen()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        var buildings = BuildingAreaSyncPacket.getCached();
        if (buildings.isEmpty()) return;

        Vec3 camPos = event.getCamera().getPosition();

        // 本帧所有工地共用一份烘焙时间预算：逐个建筑各给一份的话，场上 N 个工地就是 N 份。
        long bakeDeadline = BuildingGhostVboCache.bakeDeadline();

        // 虚影可关（ClientConfig#BUILDING_GHOST）：关掉后连 VBO 都不烘，每个工地只画一圈包围盒线框。
        boolean ghostOn = ClientConfig.BUILDING_GHOST.get();

        for (var entry : buildings) {
            if (entry.completed()) continue;
            BuildingConfig config = BuildingConfigLoader.getInstance().get(entry.buildingTypeId());
            if (config == null) continue;

            BlockPos anchor = entry.anchor();
            // Per-building frustum cull — boundary from the packet is pre-rotated.
            if (entry.hasBoundary()) {
                AABB aabb = new AABB(
                        anchor.getX() + entry.bMinX(),
                        anchor.getY() + entry.bMinY(),
                        anchor.getZ() + entry.bMinZ(),
                        anchor.getX() + entry.bMaxX() + 1,
                        anchor.getY() + entry.bMaxY() + 1,
                        anchor.getZ() + entry.bMaxZ() + 1);
                if (!event.getFrustum().isVisible(aabb)) continue;
            }

            if (!ghostOn) {
                if (entry.hasBoundary()) {
                    drawOutline(mc, event, camPos, anchor, entry);
                }
                continue;
            }

            BuildingGhostRenderer.renderGhostVboSkipped(mc, event.getModelViewMatrix(), event.getProjectionMatrix(),
                    camPos, anchor, config, entry.rotationSteps(), event.getFrustum(), bakeDeadline);

            // Animated blocks (chests etc.) can't bake into the VBO — render them
            // per-frame via their block-entity item renderer, skipping already-placed cells.
            BuildingGhostRenderer.renderGhostAnimated(mc, event.getPoseStack(),
                    mc.renderBuffers().bufferSource(), camPos, anchor, config,
                    entry.rotationSteps(), true);
        }
    }

    /** 虚影关闭时工地唯一的表现：包围盒线框（与放置预览同一套画法、同一个颜色口径）。 */
    private static void drawOutline(Minecraft mc, RenderLevelStageEvent event, Vec3 camPos,
                                    BlockPos anchor, BuildingAreaSyncPacket.BuildingEntry entry) {
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        BuildingOutline.box(vc, poseStack.last(),
                anchor.getX() + entry.bMinX() + 0.5f,
                anchor.getY() + entry.bMinY() + 0.5f,
                anchor.getZ() + entry.bMinZ() + 0.5f,
                anchor.getX() + entry.bMaxX() + 0.5f,
                anchor.getY() + entry.bMaxY() + 0.5f,
                anchor.getZ() + entry.bMaxZ() + 0.5f,
                255, 255, 255, 255);
        buffers.endBatch(RenderType.lines());
        poseStack.popPose();
    }
}
