package com.wsteam.wandscape.content.building.projection.client;
import com.wsteam.wandscape.content.task.ecs.World;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.ClientConfig;
import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.content.building.projection.BuildingRotation;
import com.wsteam.wandscape.content.building.projection.data.BuildingSlot;
import com.wsteam.wandscape.content.building.render.BuildingGhostRenderer;
import com.wsteam.wandscape.content.building.render.BuildingGhostVboCache;
import com.wsteam.wandscape.content.building.render.BuildingOutline;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * World-space rendering for soul projection mode.
 */
public final class ProjectionRenderer {

    private static final String TAG = "ProjectionRenderer";
    private static boolean registered = false;

    private ProjectionRenderer() {}

    public static void register() {
        if (registered) return;
        registered = true;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(RenderLevelStageEvent.class, ProjectionRenderer::onRenderLevelStage);
        Log.info(TAG, "[Projection] Renderer registered");
    }

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!ProjectionClientState.isProjecting()) return;
        // 只接一个阶段。MC 的 LevelRenderer 是**按 chunk 渲染层逐层**派发这个事件的
        // （AFTER_TRANSLUCENT_BLOCKS 绑 translucent 层、AFTER_TRIPWIRE_BLOCKS 绑 tripwire 层），
        // 两者每帧各触发一次 —— 原先两个都接，等于整栋虚影每帧被画了两遍：顶点、光栅化、
        // 混合全付双份，而且第二遍是在第一遍写好的等深上以 LEQUAL 通过，同一层颜色被混两次
        // （0.55 混两遍 = 有效 0.8）。工地虚影（ConstructionGhostRenderer）一直只接
        // AFTER_TRIPWIRE_BLOCKS，这里与它对齐。
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        BlockPos ghostPos = ProjectionClientState.getGhostPos();
        if (ghostPos == null) return;

        BuildingSlot slot = ProjectionClientState.getSelectedSlot();
        BuildingConfig config = (slot != null) ? BuildingConfigLoader.getInstance().get(slot.id()) : null;
        if (config == null) return;

        Vec3 camPos = event.getCamera().getPosition();
        int rotationSteps = ProjectionClientState.getRotationSteps();

        // 虚影可关（ClientConfig#BUILDING_GHOST）：弱显卡上百万方块的大楼光是烘焙+绘制虚影
        // 就可能卡顿/爆显存。关掉后连 VBO 都不烘（getOrBake 不会被调到），只剩下面那圈包围盒线框。
        boolean ghostOn = ClientConfig.BUILDING_GHOST.get();

        if (ghostOn) {
            // 1. Render GPU VBO ghost with exact event Camera ModelView matrix (120 FPS)
            BuildingGhostRenderer.renderGhostVbo(mc, event.getModelViewMatrix(), event.getProjectionMatrix(),
                    camPos, ghostPos, config, rotationSteps, event.getFrustum(),
                    BuildingGhostVboCache.bakeDeadline());

            // 1b. Render animated blocks (chests etc.) that have no static block model and
            // cannot bake into the VBO — drawn per-frame via their block-entity item renderer.
            BuildingGhostRenderer.renderGhostAnimated(mc, event.getPoseStack(),
                    mc.renderBuffers().bufferSource(), camPos, ghostPos, config, rotationSteps, false);
        }

        // 2. Render Boundary Wireframe (red only when the ghost shares a voxel with an
        // existing building — boundary boxes may now overlap freely)
        if (config.boundary() != null) {
            boolean conflict = ProjectionClientState.isOverlapDetected();
            boolean pinned = ProjectionClientState.isPinned();
            // 虚影关掉时这圈线框是唯一的落点参考 → 无论是否已「钉住」都画。
            boolean drawOutline = conflict || pinned || !ghostOn;

            if (drawOutline) {
                BuildingConfig.BoundaryBox boundary =
                        BuildingRotation.rotateBoundary(config.boundary(), rotationSteps);

                PoseStack poseStack = event.getPoseStack();
                MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
                int g = conflict ? 40 : 255;
                int b = conflict ? 40 : 255;
                int r = 255;

                poseStack.pushPose();
                poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
                VertexConsumer lineVc = bufferSource.getBuffer(RenderType.lines());
                drawAABBOutline(lineVc, poseStack.last(), ghostPos,
                        boundary.min(), boundary.max(), r, g, b);
                bufferSource.endBatch(RenderType.lines());
                poseStack.popPose();
            }
        }
    }

    private static void drawAABBOutline(VertexConsumer vc, PoseStack.Pose poseEntry,
                                         BlockPos anchor, BlockOffset min, BlockOffset max,
                                         int r, int g, int b) {
        BuildingOutline.box(vc, poseEntry,
                anchor.getX() + min.x() + 0.5f, anchor.getY() + min.y() + 0.5f, anchor.getZ() + min.z() + 0.5f,
                anchor.getX() + max.x() + 0.5f, anchor.getY() + max.y() + 0.5f, anchor.getZ() + max.z() + 0.5f,
                r, g, b, 255);
    }
}
