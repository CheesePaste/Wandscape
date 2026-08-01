package com.wsteam.wandscape.magic.internal;

import javax.annotation.Nullable;

import com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.magic.data.MagicCircleSpec;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;

/**
 * 注册 {@code data/wandscape/magic_circles/*.json}，客户端/服务端均可按 id 查 spec。
 * 数据契约见 {@code magicarchitecture/magic-circles.md}，由 Web 编辑器导出。
 */
public class MagicCircleLoader {
    private static final String CATEGORY = "magic_circles";

    private final WandscapeDataRegistry<MagicCircleSpec> registry;

    public MagicCircleLoader(WandscapeDataLoader dataLoader) {
        this.registry = dataLoader.register(CATEGORY, MagicCircleSpec::fromJson);
    }

    @Nullable
    public MagicCircleSpec get(String id) {
        return registry.get(id);
    }
}
