package com.wsteam.wandscape.magic.internal;

import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.magic.data.MagicDef;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;

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
     * 该魔法 id 的可装备分类名（小写）；不可装备返回 null。SPECIAL（teleport/heal）与
     * UTILITY（revive）为系统固有/祭坛专属、未知 id 无定义——三者均不进
     * {@code EquippedMagicComponent}。服务端权威装桶校验统一走这里，避免各调用方重复判。
     */
    @Nullable
    public static String equippableCategoryOf(String magicId) {
        MagicDef def = getSpec(magicId);
        if (def == null) return null;
        MagicDef.Category c = def.category();
        if (c == MagicDef.Category.UTILITY || c == MagicDef.Category.SPECIAL) return null;
        return c.name().toLowerCase(Locale.ROOT);
    }
}
