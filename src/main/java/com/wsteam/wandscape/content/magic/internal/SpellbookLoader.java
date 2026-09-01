package com.wsteam.wandscape.content.magic.internal;

import com.wsteam.wandscape.core.component.EquippedMagicComponent;
import com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.content.magic.data.MagicDef;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * 注册 {@code data/wandscape/magic_spells/*.json}，客户端/服务端均可按 id 查魔法定义。
 * 数据契约见 {@code docs/spell-casting.md}（MagicDef）。视觉层照旧走 {@link MagicCircleLoader}。
 */
public class SpellbookLoader {
    private static final String CATEGORY = "magic_spells";

    /** 静态实例，供施法/守卫按 id 查定义（由 Wandscape 构造器设置）。 */
    private static SpellbookLoader INSTANCE;

    private final WandscapeDataRegistry<MagicDef> registry;

    public SpellbookLoader(WandscapeDataLoader dataLoader) {
        this.registry = dataLoader.register(CATEGORY, MagicDef::fromJson);
        INSTANCE = this;
    }

    @Nullable
    public MagicDef get(String id) {
        return registry.get(id);
    }

    /** 全部魔法（id → 定义），供祭坛/策略 UI 列举。 */
    public Map<String, MagicDef> getAll() {
        return registry.getAll();
    }

    @Nullable
    public static MagicDef getSpec(String id) {
        return INSTANCE != null ? INSTANCE.get(id) : null;
    }

    /** 全部魔法（id → 定义）；loader 未初始化时为空 map。 */
    public static Map<String, MagicDef> getAllSpecs() {
        return INSTANCE != null ? INSTANCE.getAll() : Map.of();
    }

    /**
     * 该魔法 id 的默认策略组名（小写）；不可装备返回 null。ALTAR（revive）为祭坛专属、
     * teleport 为导航回退、未知 id 无定义——三者均不进 {@code EquippedMagicComponent}。
     * normal 法术返回 {@link MagicDef#defaultGroup()}（缺省兜底 support）；SPECIAL 的 heal
     * 无 default_group → support。策略组只作默认装桶归属，实际组由玩家在策略页放置决定，
     * 敌数门控与预设排序按实际组（{@code CastBrain}）判。
     * 服务端权威装桶校验统一走这里，避免各调用方重复判。
     */
    @Nullable
    public static String equippableCategoryOf(String magicId) {
        MagicDef def = getSpec(magicId);
        if (def == null) return null;
        if (def.category() == MagicDef.Category.ALTAR) return null;
        if ("teleport".equals(def.id())) return null;
        String g = def.defaultGroup();
        return EquippedMagicComponent.isCategory(g) ? g : "support";
    }
}
