package com.wsteam.wandscape.core.component;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class CastStrategyComponentTest {

    @Test
    void defaultsToBalanced() {
        CastStrategyComponent s = new CastStrategyComponent();
        assertEquals(CastStrategyComponent.Preset.BALANCED, s.preset());
        assertEquals(List.of(), s.customPriority());
    }

    @Test
    void setPresetByEnumAndName() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setPreset(CastStrategyComponent.Preset.DEFENSIVE);
        assertEquals(CastStrategyComponent.Preset.DEFENSIVE, s.preset());
        s.setPreset("offensive");
        assertEquals(CastStrategyComponent.Preset.OFFENSIVE, s.preset());
        s.setPreset("CUSTOM");
        assertEquals(CastStrategyComponent.Preset.CUSTOM, s.preset());
    }

    @Test
    void unknownPresetNameFallsBackToBalanced() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setPreset("not_a_preset");
        assertEquals(CastStrategyComponent.Preset.BALANCED, s.preset());
        s.setPreset((String) null);
        assertEquals(CastStrategyComponent.Preset.BALANCED, s.preset());
    }

    @Test
    void nullPresetEnumFallsBackToBalanced() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setPreset((CastStrategyComponent.Preset) null);
        assertEquals(CastStrategyComponent.Preset.BALANCED, s.preset());
    }

    @Test
    void customPriorityDeduplicatesAndCopies() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setCustomPriority(List.of("beam", "heal", "beam", "shield"));
        assertEquals(List.of("beam", "heal", "shield"), s.customPriority());
        List<String> view = s.customPriority();
        s.setCustomPriority(List.of("a"));
        assertEquals(List.of("beam", "heal", "shield"), view, "返回副本");
    }

    @Test
    void customPriorityNullClears() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setCustomPriority(List.of("beam"));
        s.setCustomPriority(null);
        assertEquals(List.of(), s.customPriority());
    }
}
