package com.wsteam.wandscape.shared.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.projection.BuildingRotation;
import com.wsteam.wandscape.shared.ui.util.BuildingPreviewRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.joml.Matrix4f;

import java.util.Map;

/**
 * World-space semi-transparent building ghost renderer facade.
 *
 * <p>Both the full projection ghost and the under-construction footprint are
 * drawn from pre-baked GPU VBOs ({@link BuildingGhostVboCache}) — a single draw
 * call per building with zero per-frame block modeling.
 *
 * <p>Blocks with no static block model ({@link RenderShape#ENTITYBLOCK_ANIMATED}:
 * chests, shulker boxes, signs, banners, …) cannot bake into the VBO — they are
 * rendered by a small per-frame pass ({@link #renderGhostAnimated}) through their
 * block-entity item renderer, which binds the correct texture atlas.
 */
public final class BuildingGhostRenderer {

    private static final float GHOST_ALPHA = 0.55f;
    private static final int FULL_BRIGHT = 0xF000F0;

    private BuildingGhostRenderer() {}

    /** Render full building ghost via GPU VBO static cache with camera ModelView (120 FPS). */
    public static void renderGhostVbo(Minecraft mc, Matrix4f cameraModelView, Matrix4f projection,
                                      Vec3 camPos, BlockPos anchor, BuildingConfig config, int rotationSteps) {
        BuildingGhostVboCache.drawGhost(mc, cameraModelView, projection, camPos, anchor, config, rotationSteps);
    }

    /** Render under-construction footprint ghost skipping placed blocks via GPU VBO. */
    public static void renderGhostVboSkipped(Minecraft mc, Matrix4f cameraModelView, Matrix4f projection,
                                             Vec3 camPos, BlockPos anchor, BuildingConfig config, int rotationSteps) {
        BuildingGhostVboCache.drawGhostSkipped(mc, cameraModelView, projection, camPos, anchor, config, rotationSteps);
    }

    /**
     * Per-frame pass for blocks the VBO cannot bake ({@link RenderShape#ENTITYBLOCK_ANIMATED}).
     * Each cell is rendered through its item block-entity renderer, which produces
     * per-render-type buffers with the correct atlas. Call right after the VBO draw
     * with the same pose/rotation so the pieces align. {@code skipBuilt} mirrors the
     * VBO footprint mask (skip cells already occupied by the expected block).
     */
    public static void renderGhostAnimated(Minecraft mc, PoseStack poseStack,
                                           MultiBufferSource.BufferSource bufferSource,
                                           Vec3 camPos, BlockPos anchor, BuildingConfig config,
                                           int rotationSteps, boolean skipBuilt) {
        if (config.pattern().isEmpty()) return;
        int steps = rotationSteps & 3;
        Map<BlockOffset, BlockState> resolved = BuildingPreviewRenderer.resolveBlockStates(config);
        if (resolved.isEmpty()) return;

        poseStack.pushPose();
        // 平移到 anchor，不旋转几何体：几何体绕原点旋转会把每格体积相对构造偏移最多
        // 1 格（90°/270° 偏 1 格、180° 两方向各偏 1 格）。每格平移到旋转后的偏移即可。
        poseStack.translate(anchor.getX() - camPos.x, anchor.getY() - camPos.y, anchor.getZ() - camPos.z);

        MultiBufferSource ghostSource = renderType -> new GhostAlphaConsumer(bufferSource.getBuffer(renderType));

        for (BlockOffset off : config.pattern()) {
            BlockState state = resolved.get(off);
            if (state == null || state.getRenderShape() != RenderShape.ENTITYBLOCK_ANIMATED) continue;
            BlockOffset rotated = BuildingRotation.rotateOffset(off, steps);
            if (skipBuilt) {
                if (mc.level != null && mc.level.getBlockState(
                        anchor.offset(rotated.x(), rotated.y(), rotated.z())).getBlock() == state.getBlock()) {
                    continue;
                }
            }
            ItemStack stack = new ItemStack(state.getBlock());
            var customRenderer = IClientItemExtensions.of(stack).getCustomRenderer();
            if (customRenderer == null) continue;
            poseStack.pushPose();
            poseStack.translate(rotated.x(), rotated.y(), rotated.z());
            // 绕方块自身中心旋转模型，使箱子/告示牌等朝建筑旋转方向，同时保持占据轴对齐单元格。
            if (steps > 0) {
                poseStack.translate(0.5f, 0.5f, 0.5f);
                poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-90f * steps));
                poseStack.translate(-0.5f, -0.5f, -0.5f);
            }
            customRenderer.renderByItem(stack, ItemDisplayContext.NONE, poseStack,
                    ghostSource, FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }

        poseStack.popPose();
        bufferSource.endBatch();
    }

    /** Apply the ghost alpha to a single buffer's vertices. */
    private static final class GhostAlphaConsumer implements VertexConsumer {
        private final VertexConsumer real;

        GhostAlphaConsumer(VertexConsumer real) {
            this.real = real;
        }

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
    }
}
