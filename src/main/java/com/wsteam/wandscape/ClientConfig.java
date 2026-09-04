package com.wsteam.wandscape;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 客户端专属配置（{@code ModConfig.Type.CLIENT}）：dedicated server 不加载，仅 client 侧读。
 * 服务端也会读的键（如 {@code PARTICLE_LEVEL}）必须留在 {@code Config}（COMMON），不能进这里。
 *
 * Client-only configuration ({@code ModConfig.Type.CLIENT}): not loaded on a dedicated server, read only client-side.
 * Keys the server also reads (e.g. {@code PARTICLE_LEVEL}) must stay in {@code Config} (COMMON) and must not go here.
 */
public final class ClientConfig {
    private ClientConfig() {}

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue FLY_SPEED = BUILDER
            .comment("V 面板相机飞行速度（格/秒）：鸟瞰 / 道路（含样条 3D 与俯视）/ 建造子模式共用。"
                    + "已移除游戏内滚轮/Ctrl 调速，改此值即可整体调整。")
            .comment("V-panel camera flight speed (blocks/second), shared by bird's-eye / road (both spline 3D and top-down) / build sub-modes. "
                    + "In-game scroll/Ctrl speed adjustment has been removed; change this value to adjust globally.")
            .defineInRange("panel.flySpeed", 15.0, 1.0, 200.0);

    public static final ModConfigSpec.IntValue PREVIEW_RESOLUTION = BUILDER
            .comment("建筑预览 GIF 烘焙分辨率（像素/边，清晰度）：越高越清晰但每帧占的内存/显存越多。"
                    + "改后需重启游戏重新烘焙。")
            .comment("Building-preview GIF bake resolution (pixels per side): higher is sharper but each frame uses more memory/VRAM. "
                    + "Restart the game after changing to re-bake.")
            .defineInRange("preview.resolution", 128, 48, 256);

    public static final ModConfigSpec.IntValue PREVIEW_FPS = BUILDER
            .comment("建筑预览 GIF 播放帧率（每秒帧数）：越高旋转越顺滑，但帧数越多、烘焙时间与内存越大"
                    + "（4 秒转一圈 → 帧数 = fps×4，默认 12 = 48 帧）。改后需重启游戏重新烘焙。")
            .comment("Building-preview GIF playback frame rate (fps): higher is smoother rotation but more frames, a longer bake time, and more memory "
                    + "(4 s per rotation → frames = fps×4, default 12 = 48 frames). Restart the game after changing to re-bake.")
            .defineInRange("preview.fps", 12, 4, 60);

    public static final ModConfigSpec.BooleanValue ROAD_GRID = BUILDER
            .comment("道路放置/样条编辑模式下，相机周围地面是否显示半透明灰色 1×1 方块网格辅助线。"
                    + "默认关闭：网格是叠加在场景上的透明覆盖层，与部分光影包不兼容。")
            .comment("In road-place / spline-edit mode, show a translucent gray 1×1 block grid on the ground around the camera. "
                    + "Default off: the grid is a transparent overlay on the scene and is incompatible with some shader packs.")
            .define("road.showTerrainGrid", false);

    static final ModConfigSpec SPEC = BUILDER.build();
}
