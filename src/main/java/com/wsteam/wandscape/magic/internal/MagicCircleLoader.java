package com.wsteam.wandscape.magic.internal;

import com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.magic.data.MagicCircleSpec;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;

import javax.annotation.Nullable;

/**
 * 注册 {@code data/wandscape/magic_circles/*.json}，客户端/服务端均可按 id 查 spec。
 * 数据契约见 {@code architecture/magic/magic-circles.md}，由 Web 编辑器导出。
 */
public class MagicCircleLoader {
    private static final String CATEGORY = "magic_circles";

    /** 静态实例，供客户端 emitter / 服务端命令按 id 查 spec（由 Wandscape 构造器设置）。 */
    private static MagicCircleLoader INSTANCE;

    private final WandscapeDataRegistry<MagicCircleSpec> registry;

    public MagicCircleLoader(WandscapeDataLoader dataLoader) {
        this.registry = dataLoader.register(CATEGORY, MagicCircleSpec::fromJson);
        INSTANCE = this;
    }

    @Nullable
    public MagicCircleSpec get(String id) {
        return registry.get(id);
    }

    @Nullable
    public static MagicCircleSpec getSpec(String id) {
        return INSTANCE != null ? INSTANCE.get(id) : null;
    }
}
