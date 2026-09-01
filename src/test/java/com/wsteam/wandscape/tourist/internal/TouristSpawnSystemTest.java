package com.wsteam.wandscape.tourist.internal;

import static org.junit.jupiter.api.Assertions.*;

import com.wsteam.wandscape.content.tourist.internal.TouristSpawnSystem;
import org.junit.jupiter.api.Test;

class TouristSpawnSystemTest {

    @Test
    void personaNeedsLevel1BalancedIsTwentyTwentyTwenty() {
        assertArrayEquals(new int[]{20, 20, 20},
                TouristSpawnSystem.personaNeeds(60, new double[]{1.0, 1.0, 1.0}));
    }

    @Test
    void personaNeedsLevel1FocusedComfortIsThirtyTwoFourteenFourteen() {
        assertArrayEquals(new int[]{32, 14, 14},
                TouristSpawnSystem.personaNeeds(60, new double[]{1.6, 0.7, 0.7}));
    }

    @Test
    void personaNeedsLevel1FocusedMagicIsFourteenThirtyTwoFourteen() {
        assertArrayEquals(new int[]{14, 32, 14},
                TouristSpawnSystem.personaNeeds(60, new double[]{0.7, 1.6, 0.7}));
    }

    @Test
    void personaNeedsLevel1FocusedWonderIsFourteenFourteenThirtyTwo() {
        assertArrayEquals(new int[]{14, 14, 32},
                TouristSpawnSystem.personaNeeds(60, new double[]{0.7, 0.7, 1.6}));
    }

    @Test
    void personaNeedsScalesWithLevel() {
        assertArrayEquals(new int[]{67, 67, 67},
                TouristSpawnSystem.personaNeeds(200, new double[]{1.0, 1.0, 1.0}));
    }
}
