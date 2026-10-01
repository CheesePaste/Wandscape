package com.wsteam.wandscape.content.building.render;
import com.wsteam.wandscape.content.task.ecs.World;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.projection.BuildingRotation;
import com.wsteam.wandscape.content.building.preview.BuildingPreviewRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

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
        List<AnimatedCell> cells = animatedCells(config, steps);
        if (cells.isEmpty()) return;

        poseStack.pushPose();
        // 平移到 anchor，不旋转几何体：几何体绕原点旋转会把每格体积相对构造偏移最多
        // 1 格（90°/270° 偏 1 格、180° 两方向各偏 1 格）。每格平移到旋转后的偏移即可。
        poseStack.translate(anchor.getX() - camPos.x, anchor.getY() - camPos.y, anchor.getZ() - camPos.z);

        MultiBufferSource ghostSource = renderType -> new GhostAlphaConsumer(bufferSource.getBuffer(renderType));

        for (AnimatedCell cell : cells) {
            BlockOffset rotated = cell.offset();
            if (skipBuilt) {
                if (mc.level != null && mc.level.getBlockState(
                        anchor.offset(rotated.x(), rotated.y(), rotated.z())).getBlock() == cell.block()) {
                    continue;
                }
            }
            var customRenderer = IClientItemExtensions.of(cell.stack()).getCustomRenderer();
            if (customRenderer == null) continue;
            poseStack.pushPose();
            poseStack.translate(rotated.x(), rotated.y(), rotated.z());
            // 绕方块自身中心旋转模型，使箱子/告示牌等朝建筑旋转方向，同时保持占据轴对齐单元格。
            if (steps > 0) {
                poseStack.translate(0.5f, 0.5f, 0.5f);
                poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-90f * steps));
                poseStack.translate(-0.5f, -0.5f, -0.5f);
            }
            customRenderer.renderByItem(cell.stack(), ItemDisplayContext.NONE, poseStack,
                    ghostSource, FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }

        poseStack.popPose();
        bufferSource.endBatch();
    }

    /**
     * 一个需要逐帧单独渲染的格子（没有静态模型、焙不进 VBO 的方块）。
     * {@code offset} 已按建筑旋转角旋转过，{@code stack} 预建好以免每帧分配。
     */
    private record AnimatedCell(BlockOffset offset, Block block, ItemStack stack) {}

    /**
     * 按 (config, rotation) 预计算的动画格子表。原先每帧都要遍历整份 pattern
     * （那栋超大建筑 58 万条）做一次 HashMap 查找加一次 {@code rotateOffset}，只为挑出
     * 通常不到一百个箱子/告示牌；现在这份表建一次就一直用。
     *
     * <p>弱键：配置在 {@code /reload} 时会被整体换掉，弱键让旧配置的条目自然回收，
     * 与 {@code BuildingPreviewRenderer.META_CACHE} 同一口径。
     */
    private static final Map<BuildingConfig, List<List<AnimatedCell>>> ANIMATED_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static List<AnimatedCell> animatedCells(BuildingConfig config, int steps) {
        List<List<AnimatedCell>> buckets = ANIMATED_CACHE.computeIfAbsent(config,
                k -> new ArrayList<>(Collections.nCopies(4, null)));
        List<AnimatedCell> cells = buckets.get(steps);
        if (cells == null) {
            cells = collectAnimatedCells(config, steps);
            buckets.set(steps, cells);
        }
        return cells;
    }

    private static List<AnimatedCell> collectAnimatedCells(BuildingConfig config, int steps) {
        List<BlockOffset> pattern = config.pattern();
        List<AnimatedCell> cells = new ArrayList<>();
        Set<BlockOffset> seen = new HashSet<>();
        for (int i = 0; i < pattern.size(); i++) {
            BlockState state = BuildingPreviewRenderer.resolveBlockState(
                    BuildingRotation.rotateBlockStateString(config.blockIdAt(i), steps));
            if (state == null || state.getRenderShape() != RenderShape.ENTITYBLOCK_ANIMATED) continue;
            BlockOffset rotated = BuildingRotation.rotateOffset(pattern.get(i), steps);
            if (!seen.add(rotated)) continue;
            cells.add(new AnimatedCell(rotated, state.getBlock(), new ItemStack(state.getBlock())));
        }
        return List.copyOf(cells);
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
