package com.wsteam.wandscape;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 客户端专属配置（{@code ModConfig.Type.CLIENT}）：dedicated server 不加载，仅 client 侧读。
 * 服务端也会读的键（如 {@code PARTICLE_LEVEL}）必须留在 {@code Config}（COMMON），不能进这里。
 */
public final class ClientConfig {
    private ClientConfig() {}

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue FLY_SPEED = BUILDER
            .comment("V 面板相机飞行速度（格/秒）：鸟瞰 / 道路（含样条 3D 与俯视）/ 建造子模式共用。"
                    + "已移除游戏内滚轮/Ctrl 调速，改此值即可整体调整。")
            .defineInRange("panel.flySpeed", 15.0, 1.0, 200.0);

    public static final ModConfigSpec.IntValue PREVIEW_RESOLUTION = BUILDER
            .comment("建筑预览 GIF 烘焙分辨率（像素/边，清晰度）：越高越清晰但每帧占的内存/显存越多。"
                    + "改后需重启游戏重新烘焙。")
            .defineInRange("preview.resolution", 128, 48, 256);

    public static final ModConfigSpec.IntValue PREVIEW_FPS = BUILDER
            .comment("建筑预览 GIF 播放帧率（每秒帧数）：越高旋转越顺滑，但帧数越多、烘焙时间与内存越大"
                    + "（4 秒转一圈 → 帧数 = fps×4，默认 12 = 48 帧）。改后需重启游戏重新烘焙。")
            .defineInRange("preview.fps", 12, 4, 60);

    static final ModConfigSpec SPEC = BUILDER.build();
}
