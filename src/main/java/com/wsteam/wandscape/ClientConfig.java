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
            .defineInRange("panel.flySpeed", 30.0, 1.0, 200.0);

    public static final ModConfigSpec.IntValue PREVIEW_RESOLUTION = BUILDER
            .comment("建筑预览图烘焙分辨率（像素/边，清晰度），设置中心里是 128/256/512/1024 四档：越高越清晰，"
                    + "但显存按边长²涨——每栋 256 档 256 KB、512 档 1 MB、1024 档 4 MB，全部建筑常驻时分别约 14 / 56 / 224 MB。"
                    + "每栋只有一张固定 3/4 视角的静图；施工屏那格按 108 GUI 像素显示（GUI 缩放 3 时约 324 设备像素），"
                    + "256 已基本贴合，512/1024 只有把图放大看时才有意义。改后需重启游戏重新烘焙。")
            .comment("Building-preview bake resolution (pixels per side), offered as four tiers (128/256/512/1024): higher is sharper, "
                    + "but VRAM grows with the square of the edge — 256 KB / 1 MB / 4 MB per building at 256 / 512 / 1024, "
                    + "i.e. about 14 / 56 / 224 MB with the whole catalog resident. One static 3/4-view image per building; "
                    + "the construction screen shows it in a 108-GUI-pixel square (~324 device pixels at GUI scale 3), so 256 already "
                    + "matches it and 512/1024 only pay off when the image is viewed enlarged. Restart the game after changing to re-bake.")
            .defineInRange("preview.resolution", 256, 128, 1024);

    public static final ModConfigSpec.BooleanValue ROAD_GRID = BUILDER
            .comment("道路放置/样条编辑模式下，相机周围地面是否显示半透明灰色 1×1 方块网格辅助线。"
                    + "默认关闭：网格是叠加在场景上的透明覆盖层，与部分光影包不兼容。")
            .comment("In road-place / spline-edit mode, show a translucent gray 1×1 block grid on the ground around the camera. "
                    + "Default off: the grid is a transparent overlay on the scene and is incompatible with some shader packs.")
            .define("road.showTerrainGrid", false);

    public static final ModConfigSpec.BooleanValue SHOW_SPEECH_BUBBLES = BUILDER
            .comment("法师与游客头顶的随机闲聊气泡是否显示。关闭后不影响消费/服务反馈的瞬时事件气泡"
                    + "（物品图标 × 数量），也不影响头顶名牌与状态文字。")
            .comment("Show the random ambient chatter bubbles above mages and tourists. Turning this off does not affect "
                    + "transient event bubbles (purchase / service feedback: item icon × count), nor the nameplate and status text.")
            .define("ui.speechBubbles", true);

    public static final ModConfigSpec.BooleanValue BUILDING_GHOST = BUILDER
            .comment("是否渲染建筑虚影（放置预览与工地未完工建筑的半透明整栋预览）。关闭后不再画整栋虚影，"
                    + "只保留建筑包围盒的线框——落点照样对得准。百万级方块的大建筑在弱显卡上可能卡顿甚至爆显存，"
                    + "那种情况把它关掉即可；同时也不会再做虚影的烘焙与缓存。")
            .comment("Whether to render the translucent building ghost (placement preview and the footprint of unfinished "
                    + "construction sites). When off, no per-block ghost is drawn at all and only the bounding-box wireframe "
                    + "remains, which is still enough to aim with. A million-block building can stutter or exhaust VRAM on weak "
                    + "GPUs — turn this off for those; the ghost is then neither baked nor cached.")
            .define("render.buildingGhost", true);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
