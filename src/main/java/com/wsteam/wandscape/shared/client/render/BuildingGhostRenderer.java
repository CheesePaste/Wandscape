package com.wsteam.wandscape.shared.client.render;

import org.joml.Matrix4f;

import com.wsteam.wandscape.building.data.BuildingConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * World-space semi-transparent building ghost renderer facade.
 *
 * <p>Both the full projection ghost and the under-construction footprint are
 * drawn from pre-baked GPU VBOs ({@link BuildingGhostVboCache}) — a single draw
 * call per building with zero per-frame block modeling.
 */
public final class BuildingGhostRenderer {

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
}
