package com.wsteam.wandscape.projection;

import static org.junit.jupiter.api.Assertions.*;

import com.wsteam.wandscape.content.building.projection.BuildingRotation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.wsteam.wandscape.content.building.data.BlockOffset;

@DisplayName("BuildingRotation")
class BuildingRotationTest {

    @Test
    @DisplayName("模型旋转（-90°*steps）与 rotateOffset 同向")
    void vboRotationMatchesRotateOffset() {
        // BuildingGhostVboCache.bake 用 pose.mulPose(Axis.YP.rotationDegrees(-90*steps))
        // 动画 pass（BuildingGhostRenderer.renderGhostAnimated）用
        // Axis.YP.rotationDegrees(-90*steps) 绕方块自身中心旋转模型，使朝向跟随建筑旋转；
        // 该矩阵必须与 BuildingRotation.rotateOffset 同向（x'=-z, z'=x），否则箱子等朝向错位。
        int[][] cases = { {1, 0}, {0, 1}, {-1, 0}, {3, -4}, {5, 2}, {-2, -7} };
        for (int steps = 0; steps < 4; steps++) {
            Matrix4f m = new Matrix4f().rotationY((float) Math.toRadians(-90.0 * steps));
            for (int[] c : cases) {
                BlockOffset off = BuildingRotation.rotateOffset(new BlockOffset(c[0], 0, c[1]), steps);
                Vector3f v = new Vector3f(c[0], 0, c[1]).mulProject(m);
                assertEquals(off.x(), Math.round(v.x), "steps=" + steps + " x 不匹配 (" + c[0] + "," + c[1] + ")");
                assertEquals(off.z(), Math.round(v.z), "steps=" + steps + " z 不匹配 (" + c[0] + "," + c[1] + ")");
            }
        }
    }

    @Test
    @DisplayName("rotateFacing: 水平方向按步数顺转（与 rotateOffset 同向）")
    void rotateFacingHorizontal() {
        assertEquals("east", BuildingRotation.rotateFacing("north", 1));
        assertEquals("south", BuildingRotation.rotateFacing("north", 2));
        assertEquals("west", BuildingRotation.rotateFacing("north", 3));
        assertEquals("north", BuildingRotation.rotateFacing("north", 4));
        assertEquals("north", BuildingRotation.rotateFacing("east", 3));
    }

    @Test
    @DisplayName("rotateFacing: up/down 不变，非法字符串原样返回")
    void rotateFacingVerticalAndInvalid() {
        assertEquals("up", BuildingRotation.rotateFacing("up", 1));
        assertEquals("down", BuildingRotation.rotateFacing("down", 2));
        assertEquals("not_a_direction", BuildingRotation.rotateFacing("not_a_direction", 1));
    }
}
