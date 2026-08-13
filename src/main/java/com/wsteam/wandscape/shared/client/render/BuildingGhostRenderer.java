package com.wsteam.wandscape.shared.client.render;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wsteam.wandscape.building.data.BuildingConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * World-space semi-transparent building ghost renderer facade.
 *
 * <p>Renders the full textured block model of a {@link BuildingConfig} at an
 * anchor position with alpha applied uniformly, so the target footprint reads
 * as a "ghost". Geometry is pre-baked into GPU vertex buffers by
 * {@link BuildingGhostVboCache} (one static draw call per frame instead of a
 * per-block {@code renderSingleBlock} loop). Shared by the projection placement
 * preview and the under-construction building footprint overlay.
 */
public final class BuildingGhostRenderer {

    private BuildingGhostRenderer() {}

    /**
     * Render {@code config}'s full ghost (all cells) at {@code anchor}.
     * Used by the projection placement preview.
     *
     * @param rotationSteps number of 90° CCW rotations (0-3)
     */
    public static void renderGhostVbo(Minecraft mc, PoseStack poseStack, Matrix4f projection,
                                      BlockPos anchor, BuildingConfig config, int rotationSteps) {
        BuildingGhostVboCache.drawGhost(mc, poseStack, projection, anchor, config, rotationSteps);
    }

    /**
     * Render {@code config}'s ghost at {@code anchor}, hiding cells that already
     * contain the expected block (under-construction footprint). The skip mask is
     * sampled from the world on every call.
     *
     * @param rotationSteps number of 90° CCW rotations (0-3)
     */
    public static void renderGhostVboSkipped(Minecraft mc, PoseStack poseStack, Matrix4f projection,
                                             BlockPos anchor, BuildingConfig config, int rotationSteps) {
        BuildingGhostVboCache.drawGhostSkipped(mc, poseStack, projection, anchor, config, rotationSteps);
    }

    /** Close all cached GPU buffers (resource reload / world logout). */
    public static void closeAll() {
        BuildingGhostVboCache.closeAll();
    }
}
