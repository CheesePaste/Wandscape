package com.wsteam.wandscape.core.component;

import java.util.ArrayList;
import java.util.List;

/**
 * NPC 会哪些魔法（magicId 列表）。纯 Java 零 MC 依赖，由 {@code WandscapeNpc} 持有并 NBT 持久。
 *
 * <p>P3 起 {@code CastBrain} 的已知魔法表来自这里（+ 玩家策略），替代原先硬编码
 * {@code defaultCombatSpells() = [beam]}。默认种子 = 所有法师开局都会的基础魔法。
 */
public class SpellbookComponent {

    /** 默认魔法（所有法师开局都会）：beam 基础攻击 + heal 治疗 + meteor 陨石 + petrification 石化。 */
    public static final List<String> DEFAULT_SPELLS = List.of("beam", "heal", "meteor", "petrification");

    private final List<String> magicIds = new ArrayList<>();

    public SpellbookComponent() {}

    public SpellbookComponent(List<String> ids) {
        if (ids != null) {
            for (String id : ids) {
                add(id);
            }
        }
    }

    public List<String> ids() {
        return List.copyOf(magicIds);
    }

    public boolean knows(String magicId) {
        return magicIds.contains(magicId);
    }

    public void add(String magicId) {
        if (magicId != null && !magicIds.contains(magicId)) {
            magicIds.add(magicId);
        }
    }

    public void remove(String magicId) {
        magicIds.remove(magicId);
    }

    /** 整体替换（去重、保序）。 */
    public void set(List<String> ids) {
        magicIds.clear();
        if (ids != null) {
            for (String id : ids) {
                add(id);
            }
        }
    }

    /** 是否为空（空 = 尚未种默认魔法）。 */
    public boolean isEmpty() {
        return magicIds.isEmpty();
    }
}
