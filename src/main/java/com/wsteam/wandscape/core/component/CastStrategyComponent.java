package com.wsteam.wandscape.core.component;

import java.util.ArrayList;
import java.util.List;

/**
 * 施法策略（玩家可控层）：预设 + 自定义优先级列表。纯 Java 零 MC 依赖，由
 * {@code WandscapeNpc} 持有并 NBT 持久。
 *
 * <p>预设决定分类级默认排序（见 {@code CastBrain.resolvePriority}），玩家可整体换预设，
 * 也可逐魔法启停（自动切 {@link Preset#CUSTOM}）。{@code customPriority} 是 magicId 顺序表，
 * 仅在 CUSTOM 预设下生效；空则 CastBrain 回退 balanced。
 */
public class CastStrategyComponent {

    /** 策略预设：分类级排序模板。 */
    public enum Preset { BALANCED, OFFENSIVE, SUPPORT, DEFENSIVE, CUSTOM }

    private Preset preset = Preset.BALANCED;
    private final List<String> customPriority = new ArrayList<>();

    public Preset preset() {
        return preset;
    }

    public void setPreset(Preset value) {
        this.preset = value != null ? value : Preset.BALANCED;
    }

    /** 按名称设置预设；未知名称回退 balanced。 */
    public void setPreset(String name) {
        if (name == null) {
            this.preset = Preset.BALANCED;
            return;
        }
        try {
            this.preset = Preset.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException ex) {
            this.preset = Preset.BALANCED;
        }
    }

    public List<String> customPriority() {
        return List.copyOf(customPriority);
    }

    public void setCustomPriority(List<String> priority) {
        customPriority.clear();
        if (priority != null) {
            for (String id : priority) {
                if (id != null && !customPriority.contains(id)) {
                    customPriority.add(id);
                }
            }
        }
    }
}
