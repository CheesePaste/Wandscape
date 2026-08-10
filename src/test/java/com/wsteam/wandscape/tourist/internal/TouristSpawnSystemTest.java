package com.wsteam.wandscape.tourist.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TouristSpawnSystemTest {

    @Test
    void personaNeedsLevel1BalancedIsFiftyFiftyFifty() {
        assertArrayEquals(new int[]{50, 50, 50},
                TouristSpawnSystem.personaNeeds(150, new double[]{1.0, 1.0, 1.0}));
    }

    @Test
    void personaNeedsLevel1FocusedComfortIsEightyThirtyFive() {
        assertArrayEquals(new int[]{80, 35, 35},
                TouristSpawnSystem.personaNeeds(150, new double[]{1.6, 0.7, 0.7}));
    }

    @Test
    void personaNeedsLevel1FocusedMagicIsThirtyEightyThirtyFive() {
        assertArrayEquals(new int[]{35, 80, 35},
                TouristSpawnSystem.personaNeeds(150, new double[]{0.7, 1.6, 0.7}));
    }

    @Test
    void personaNeedsLevel1FocusedWonderIsThirtyFiveThirtyEighty() {
        assertArrayEquals(new int[]{35, 35, 80},
                TouristSpawnSystem.personaNeeds(150, new double[]{0.7, 0.7, 1.6}));
    }

    @Test
    void personaNeedsScalesWithLevel() {
        assertArrayEquals(new int[]{67, 67, 67},
                TouristSpawnSystem.personaNeeds(200, new double[]{1.0, 1.0, 1.0}));
    }
}
