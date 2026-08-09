package com.wsteam.wandscape.projection;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BuildingRotation")
class BuildingRotationTest {

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
