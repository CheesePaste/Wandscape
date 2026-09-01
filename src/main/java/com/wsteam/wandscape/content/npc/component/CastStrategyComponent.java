package com.wsteam.wandscape.content.npc.component;

import java.util.ArrayList;
import java.util.List;

/**
 * 施法策略（玩家可控层）：预设 + 显式优先级列表。纯 Java 零 MC 依赖，由
 * {@code WandscapeNpc} 持有并 NBT 持久。
 *
 * <p>预设（4 个总体策略）决定**分类级**先后（见 {@code CastBrain.resolvePriority}）；
 * {@code customPriority} 是 magicId 顺序表，**始终生效**（不再仅 CUSTOM）——玩家在某分类内
 * 手动排序/启停后，分类内顺序与启停以它为准。{@code configured} 区分「从未配置（空→按预设
 * 分类排序推导）」与「玩家配置过但全关（空→什么都不施放）」。CUSTOM 保留仅为旧存档兼容。
 */
public class CastStrategyComponent {

    /** 策略预设：分类级排序模板。 */
    public enum Preset { BALANCED, OFFENSIVE, SUPPORT, DEFENSIVE, CUSTOM }

    private Preset preset = Preset.BALANCED;
    private final List<String> customPriority = new ArrayList<>();
    private boolean configured;

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

    /** 玩家是否通过策略屏显式配置过（任何一次显式设置即为已配置；空列表=全部停用）。 */
    public boolean configured() {
        return configured;
    }

    public void setConfigured(boolean value) {
        this.configured = value;
    }

    /** 覆盖显式优先级列表（视为玩家已配置）。 */
    public void setCustomPriority(List<String> priority) {
        configured = true;
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
