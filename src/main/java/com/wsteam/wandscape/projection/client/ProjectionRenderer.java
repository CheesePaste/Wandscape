package com.wsteam.wandscape.projection.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.projection.BuildingRotation;
import com.wsteam.wandscape.projection.data.BuildingSlot;
import com.wsteam.wandscape.shared.ui.util.BuildingPreviewRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import com.wsteam.wandscape.shared.log.Log;

/**
 * World-space rendering for soul projection mode.
 *
 * <p>Renders:
 * <ul>
 *   <li>Ghost building preview — actual textured block models rendered
 *       semi-transparently at the targeted position via
 *       {@link BlockRenderDispatcher#renderSingleBlock}.</li>
 *   <li>Body anchor meditation beam — translucent purple pillar at the
 *       position where the player left their body.</li>
 *   <li>Red wireframe boundary when overlapping an existing building.</li>
 * </ul>
 */
public final class ProjectionRenderer {

    private static final String TAG = "ProjectionRenderer";

    /** Alpha factor for ghost blocks (0.0-1.0). Applied via setColor interception. */
    private static final float GHOST_ALPHA = 0.40f;
    /** Full brightness: block=15, sky=15 (LightTexture.pack(15,15)). */
    private static final int FULL_BRIGHT = 0xF000F0;

    private static boolean registered = false;

    private ProjectionRenderer() {}

    // ── Registration ──

    public static void register() {
        if (registered) return;
        registered = true;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(RenderLevelStageEvent.class, ProjectionRenderer::onRenderLevelStage);
        Log.info(TAG, "[Projection] Renderer registered");
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Render handler ──
    // ═══════════════════════════════════════════════════════════════

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!ProjectionClientState.isProjecting()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        renderGhostPreview(mc, bufferSource, poseStack);

        poseStack.popPose();
    }

    // ── Ghost preview (real block rendering) ──

    private static void renderGhostPreview(Minecraft mc, MultiBufferSource.BufferSource bufferSource,
                                           PoseStack poseStack) {
        BlockPos ghostPos = ProjectionClientState.getGhostPos();
        if (ghostPos == null) return;

        BuildingSlot slot = getSelectedSlot();
        BuildingConfig config = (slot != null) ? BuildingConfigLoader.getInstance().get(slot.id()) : null;
        if (config == null) return;

        boolean overlap = ProjectionClientState.isOverlapDetected();

        Map<BlockOffset, BlockState> blockStates = resolveBlockStates(config);
        if (blockStates.isEmpty()) return;

        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();

        // GhostBufferSource: wraps every VertexConsumer returned by bufferSource
        // to multiply alpha on setColor. All default methods (putBulkData, addVertex
        // with color/light/overlay) transitively call setColor, so alpha is applied
        // uniformly to all rendering paths.
        MultiBufferSource ghostSource = renderType -> {
            VertexConsumer real = bufferSource.getBuffer(renderType);
            return new VertexConsumer() {
                @Override
                public VertexConsumer addVertex(float x, float y, float z) {
                    real.addVertex(x, y, z);
                    return this;
                }

                @Override
                public VertexConsumer setColor(int r, int g, int b, int a) {
                    real.setColor(r, g, b, (int) (a * GHOST_ALPHA));
                    return this;
                }

                @Override
                public VertexConsumer setUv(float u, float v) {
                    real.setUv(u, v);
                    return this;
                }

                @Override
                public VertexConsumer setUv1(int u, int v) {
                    real.setUv1(u, v);
                    return this;
                }

                @Override
                public VertexConsumer setUv2(int u, int v) {
                    real.setUv2(u, v);
                    return this;
                }

                @Override
                public VertexConsumer setNormal(float x, float y, float z) {
                    real.setNormal(x, y, z);
                    return this;
                }
            };
        };

        int rotationSteps = ProjectionClientState.getRotationSteps();

        for (var entry : blockStates.entrySet()) {
            BlockOffset originalOffset = entry.getKey();
            BlockState originalState = entry.getValue();

            BlockOffset rotatedOffset = BuildingRotation.rotateOffset(originalOffset, rotationSteps);
            BlockState rotatedState = originalState;
            for (int i = 0; i < rotationSteps; i++) {
                rotatedState = rotatedState.rotate(Rotation.CLOCKWISE_90);
            }

            poseStack.pushPose();
            poseStack.translate(
                    ghostPos.getX() + rotatedOffset.x(),
                    ghostPos.getY() + rotatedOffset.y(),
                    ghostPos.getZ() + rotatedOffset.z());

            blockRenderer.renderSingleBlock(
                    rotatedState, poseStack, ghostSource,
                    FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    ModelData.EMPTY, null);

            poseStack.popPose();
        }

        // Flush entity-block render types
        bufferSource.endBatch(Sheets.cutoutBlockSheet());
        bufferSource.endBatch(Sheets.translucentCullBlockSheet());
        bufferSource.endBatch(Sheets.translucentItemSheet());

        // Overlap = red wireframe boundary
        if (overlap && config.boundary() != null) {
            VertexConsumer lineVc = bufferSource.getBuffer(RenderType.lines());
            drawAABBOutline(lineVc, poseStack.last(), ghostPos,
                    config.boundary().min(), config.boundary().max(), 255, 40, 40);
            bufferSource.endBatch(RenderType.lines());
        }
    }

    // ── Block mapping resolution ──

    private static Map<BlockOffset, BlockState> resolveBlockStates(BuildingConfig config) {
        Map<String, String> blockMapping = config.blockMapping();
        if (blockMapping == null || blockMapping.isEmpty()) return Map.of();

        Map<BlockOffset, BlockState> result = new HashMap<>();
        for (BlockOffset offset : config.pattern()) {
            String key = offset.toKey();
            String blockId = blockMapping.get(key);
            if (blockId == null) continue;
            BlockState state = BuildingPreviewRenderer.resolveBlockState(blockId);
            if (state != null) {
                result.put(offset, state);
            }
        }
        return result;
    }

    private static BuildingSlot getSelectedSlot() {
        List<BuildingSlot> slots = ProjectionClientState.getBuildingSlots();
        int index = ProjectionClientState.getSelectedSlotIndex();
        if (slots.isEmpty() || index < 0 || index >= slots.size()) return null;
        return slots.get(index);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Boundary wireframe ──
    // ═══════════════════════════════════════════════════════════════

    private static void drawAABBOutline(VertexConsumer vc, PoseStack.Pose poseEntry,
                                         BlockPos anchor, BlockOffset min, BlockOffset max,
                                         int r, int g, int b) {
        float x0 = anchor.getX() + min.x() + 0.5f;
        float y0 = anchor.getY() + min.y() + 0.5f;
        float z0 = anchor.getZ() + min.z() + 0.5f;
        float x1 = anchor.getX() + max.x() + 0.5f;
        float y1 = anchor.getY() + max.y() + 0.5f;
        float z1 = anchor.getZ() + max.z() + 0.5f;

        seg(vc, poseEntry, x0, y0, z0, x1, y0, z0, r, g, b);
        seg(vc, poseEntry, x1, y0, z0, x1, y0, z1, r, g, b);
        seg(vc, poseEntry, x1, y0, z1, x0, y0, z1, r, g, b);
        seg(vc, poseEntry, x0, y0, z1, x0, y0, z0, r, g, b);
        seg(vc, poseEntry, x0, y1, z0, x1, y1, z0, r, g, b);
        seg(vc, poseEntry, x1, y1, z0, x1, y1, z1, r, g, b);
        seg(vc, poseEntry, x1, y1, z1, x0, y1, z1, r, g, b);
        seg(vc, poseEntry, x0, y1, z1, x0, y1, z0, r, g, b);
        seg(vc, poseEntry, x0, y0, z0, x0, y1, z0, r, g, b);
        seg(vc, poseEntry, x1, y0, z0, x1, y1, z0, r, g, b);
        seg(vc, poseEntry, x1, y0, z1, x1, y1, z1, r, g, b);
        seg(vc, poseEntry, x0, y0, z1, x0, y1, z1, r, g, b);
    }

    private static void seg(VertexConsumer vc, PoseStack.Pose poseEntry,
                            float x1, float y1, float z1, float x2, float y2, float z2,
                            int r, int g, int b) {
        vc.addVertex(poseEntry, x1, y1, z1).setColor(r, g, b, 255).setNormal(poseEntry, 0, 1, 0);
        vc.addVertex(poseEntry, x2, y2, z2).setColor(r, g, b, 255).setNormal(poseEntry, 0, 1, 0);
    }
}
