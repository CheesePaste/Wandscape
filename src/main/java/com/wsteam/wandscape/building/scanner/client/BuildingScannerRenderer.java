package com.wsteam.wandscape.building.scanner.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.building.data.BuildingConfig.BoundaryBox;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.scanner.BuildingScannerBlockEntity;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;

/**
 * Block Entity Renderer for the Building Scanner block.
 * Draws orange boundary box, green interact zones, and red door marker
 * for ALL loaded scanner blocks (like vanilla structure blocks).
 */
public class BuildingScannerRenderer implements BlockEntityRenderer<BuildingScannerBlockEntity> {

    // Boundary: orange
    private static final int BDY_R = 255, BDY_G = 150, BDY_B = 50, BDY_A = 200;
    private static final int BDY_FACE_R = 255, BDY_FACE_G = 150, BDY_FACE_B = 50, BDY_FACE_A = 50;

    // Interact zone: green
    private static final int IZ_R = 50, IZ_G = 220, IZ_B = 80, IZ_A = 200;
    private static final int IZ_FACE_R = 50, IZ_FACE_G = 220, IZ_FACE_B = 80, IZ_FACE_A = 50;

    // Door marker: red
    private static final int DOOR_R = 255, DOOR_G = 50, DOOR_B = 50, DOOR_A = 200;

    public BuildingScannerRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(BuildingScannerBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        // 1. Boundary box (orange)
        BlockOffset bMin = be.getBoundaryMin();
        BlockOffset bMax = be.getBoundaryMax();
        if (bMin != null && bMax != null) {
            renderBox(bufferSource, poseStack.last(),
                    bMin.x(), bMin.y(), bMin.z(),
                    bMax.x() + 1, bMax.y() + 1, bMax.z() + 1,
                    BDY_R, BDY_G, BDY_B, BDY_A,
                    BDY_FACE_R, BDY_FACE_G, BDY_FACE_B, BDY_FACE_A);
        }

        // 2. Interact zones (green) — local offsets from block origin
        for (BoundaryBox zone : be.getTouristInteractZones()) {
            renderBox(bufferSource, poseStack.last(),
                    zone.min().x(), zone.min().y(), zone.min().z(),
                    zone.max().x() + 1, zone.max().y() + 1, zone.max().z() + 1,
                    IZ_R, IZ_G, IZ_B, IZ_A,
                    IZ_FACE_R, IZ_FACE_G, IZ_FACE_B, IZ_FACE_A);
        }

        // 3. Door marker (red)
        BlockOffset door = be.getDoorOffset();
        if (door != null) {
            renderBox(bufferSource, poseStack.last(),
                    door.x(), door.y(), door.z(),
                    door.x() + 1, door.y() + 1, door.z() + 1,
                    DOOR_R, DOOR_G, DOOR_B, DOOR_A,
                    DOOR_R, DOOR_G, DOOR_B, DOOR_A);
        }
    }

    private static void renderBox(MultiBufferSource buf, PoseStack.Pose pose,
                                   float x0, float y0, float z0,
                                   float x1, float y1, float z1,
                                   int lineR, int lineG, int lineB, int lineA,
                                   int faceR, int faceG, int faceB, int faceA) {
        // Semi-transparent faces
        VertexConsumer fvc = buf.getBuffer(RenderType.debugQuads());
        quad(fvc, pose, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, faceR, faceG, faceB, faceA);
        quad(fvc, pose, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, faceR, faceG, faceB, faceA);
        quad(fvc, pose, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, faceR, faceG, faceB, faceA);
        quad(fvc, pose, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, faceR, faceG, faceB, faceA);
        quad(fvc, pose, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, faceR, faceG, faceB, faceA);
        quad(fvc, pose, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, faceR, faceG, faceB, faceA);

        // Edges
        VertexConsumer lvc = buf.getBuffer(RenderType.lines());
        boxEdges(lvc, pose, x0, y0, z0, x1, y1, z1, lineR, lineG, lineB, lineA);
    }

    private static void boxEdges(VertexConsumer vc, PoseStack.Pose pose,
                                  float x0, float y0, float z0, float x1, float y1, float z1,
                                  int r, int g, int b, int a) {
        line(vc, pose, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(vc, pose, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(vc, pose, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(vc, pose, x0, y0, z1, x0, y0, z0, r, g, b, a);
        line(vc, pose, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(vc, pose, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(vc, pose, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(vc, pose, x0, y1, z1, x0, y1, z0, r, g, b, a);
        line(vc, pose, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(vc, pose, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(vc, pose, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(vc, pose, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    private static void line(VertexConsumer vc, PoseStack.Pose pose,
                              float x1, float y1, float z1, float x2, float y2, float z2,
                              int r, int g, int b, int a) {
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a).setNormal(pose, 0, 1, 0);
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose,
                              float x1, float y1, float z1, float x2, float y2, float z2,
                              float x3, float y3, float z3, float x4, float y4, float z4,
                              int r, int g, int b, int a) {
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a);
        vc.addVertex(pose, x3, y3, z3).setColor(r, g, b, a);
        vc.addVertex(pose, x4, y4, z4).setColor(r, g, b, a);
    }
}
