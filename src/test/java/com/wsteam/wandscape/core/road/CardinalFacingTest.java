package com.wsteam.wandscape.core.road;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CardinalFacingTest {

    @Test
    void opposite() {
        assertEquals(CardinalFacing.NORTH, CardinalFacing.SOUTH.opposite());
        assertEquals(CardinalFacing.SOUTH, CardinalFacing.NORTH.opposite());
        assertEquals(CardinalFacing.EAST, CardinalFacing.WEST.opposite());
        assertEquals(CardinalFacing.WEST, CardinalFacing.EAST.opposite());
    }

    @Test
    void rotate90ccw() {
        assertEquals(CardinalFacing.EAST, CardinalFacing.SOUTH.rotate(1));
        assertEquals(CardinalFacing.NORTH, CardinalFacing.SOUTH.rotate(2));
        assertEquals(CardinalFacing.WEST, CardinalFacing.SOUTH.rotate(3));
        assertEquals(CardinalFacing.SOUTH, CardinalFacing.SOUTH.rotate(4));
    }

    @Test
    void towardPrefersDominantAxis() {
        assertEquals(CardinalFacing.EAST, CardinalFacing.toward(10, 0));
        assertEquals(CardinalFacing.WEST, CardinalFacing.toward(-10, 0));
        assertEquals(CardinalFacing.SOUTH, CardinalFacing.toward(0, 10));
        assertEquals(CardinalFacing.NORTH, CardinalFacing.toward(0, -10));
    }

    @Test
    void towardTieBreaksToX() {
        // equal dx and dz → prefers X axis
        assertEquals(CardinalFacing.EAST, CardinalFacing.toward(5, 5));
    }

    @Test
    void rotationSteps() {
        assertEquals(0, CardinalFacing.rotationSteps(CardinalFacing.SOUTH, CardinalFacing.SOUTH));
        assertEquals(1, CardinalFacing.rotationSteps(CardinalFacing.SOUTH, CardinalFacing.WEST));
        assertEquals(2, CardinalFacing.rotationSteps(CardinalFacing.SOUTH, CardinalFacing.NORTH));
        assertEquals(3, CardinalFacing.rotationSteps(CardinalFacing.SOUTH, CardinalFacing.EAST));
        // Wrap-around
        assertEquals(1, CardinalFacing.rotationSteps(CardinalFacing.EAST, CardinalFacing.SOUTH));
    }

    @Test
    void entryExitRotate() {
        EntryExit e = new EntryExit(7, 0, CardinalFacing.SOUTH);
        // 90° CCW: (7,0) → (0,-7), SOUTH → EAST
        EntryExit r1 = e.rotate(1);
        assertEquals(0, r1.dx());
        assertEquals(-7, r1.dz());
        assertEquals(CardinalFacing.EAST, r1.facing());
        // 180°
        EntryExit r2 = e.rotate(2);
        assertEquals(-7, r2.dx());
        assertEquals(0, r2.dz());
        assertEquals(CardinalFacing.NORTH, r2.facing());
    }
}
