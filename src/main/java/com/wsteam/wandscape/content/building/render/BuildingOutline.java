package com.wsteam.wandscape.content.building.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 世界空间 AABB 线框（12 条棱）的唯一画法。
 *
 * <p>给建筑相关的渲染器共用：建筑区域边界、放置预览的落点框、以及**虚影关掉后**顶替它的
 * 兜底线框（见 {@code ClientConfig#BUILDING_GHOST}）。原先是每个渲染器各抄一份 12 条棱的展开，
 * 抄一次就多一份走样的风险。
 *
 * <p>调用方自己取 {@code RenderType.lines()} 的 buffer 并负责 {@code endBatch()}；
 * 坐标是**世界绝对坐标**（角的顶点，不是方块格号）。
 */
public final class BuildingOutline {

    private BuildingOutline() {}

    /** 画一个 AABB 线框。{@code (x0,y0,z0)} 是近角、{@code (x1,y1,z1)} 是远角。 */
    public static void box(VertexConsumer vc, PoseStack.Pose pose,
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
}
