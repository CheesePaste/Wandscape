# 1.9 — 移除 ImGui 依赖（native 前置）计划

> 状态：待实施（2026-08-16）
> 目标版本：`gradle.properties` `mod_version` 1.8.24 → 1.9.0

## 背景与目标

当前 `io.github.spair:imgui-java-*`（binding / lwjgl3 / **natives-windows**）被打包进 mod jar（jarJar 内嵌 native `.dll`），带来两个发布痛点：

1. **CurseForge 人工审核**：jar 内嵌 native 二进制 → 需数天人工审核，拖慢发布。
2. **平台不兼容**：只内嵌 Windows native，非 Windows 系统无法运行道路制作工坊。

**目标**：彻底移除 ImGui 依赖，道路制作工坊改用 **原生 Minecraft UI** 实现，功能与手感尽量 1:1 保留。

## 决策记录（2026-08-16）

- **交互架构 = 非模态 HUD 覆盖层（方案 A，用户已确认）**：在 `RenderGuiEvent.Post` 上渲染右侧面板，复用 V 面板（`WandscapePanelOverlay`）的 overlay 模式与光标提升机制（`liftCursorForUI`/`isCursorLifted`/`syncCursorToState`）。保留「一边看/点世界、一边操作面板」的现有体验。
- **世界交互不动**：`SplineEditorInputHandler`（纯 GLFW 轮询 + 相机射线，屏幕无关）与 `SplineEditorRenderer` 原样保留，仅改判定条件（原 `imguiWantsMouse/Keyboard` → 面板命中矩形测试）。
- **字体**：删除 `fonts/chinese.ttf`(9.3M) 与 `fonts/fa-solid-900.ttf`。原生字体（1.21.1 含 CJK unicode 页）渲染中文；FontAwesome 字形（U+E000–F8FF）改用现有 `WandscapeTheme` PNG 图标。
- **调试控制台（F12）一并删除**：`drawDebugGui` 是 ImGui 集成测试面板，无实际功能。
- **服务端零改动**：全部改动集中在客户端 + 构建配置 + 资源 + 文档，风险面小。

## 现状盘点：ImGui 消费面

| 文件 | 作用 | 处理 |
|---|---|---|
| `imgui/ImGuiManager.java` | 生命周期/字体/GLFW+GL3 后端/输入捕获/渲染循环/F12 | **删除** |
| `imgui/WandscapeImGuiTheme.java` | ImGui 主题助手（drawSectionHeader/textMuted/drawTooltip…） | **删除** |
| `road/client/SplineEditorImGui.java` | 道路制作工坊全部 GUI（941 行，4 模式+3 标签页） | **删除**，功能移植到 `RoadStudioOverlay` |
| `test/.../imgui/ImGuiFontGlyphTest.java` | 依赖 native 的字体测试 | **删除** |
| `test/.../imgui/ImGuiFontEncodingTest.java` | 依赖 native 的字体测试 | **删除** |
| `resources/assets/wandscape/fonts/*.ttf` | ImGui 中文字体 + FontAwesome 图标 | **删除** |
| `build.gradle` | 5 组 imgui 依赖（implementation/jarJar/additionalRuntimeClasspath/testRuntimeOnly） | **删除** |
| `WandscapeClient.java:180` | `ImGuiManager.register()` | 改 `RoadStudioOverlay.register()` |
| `WandscapePanelState.java:466/486/520` | 进出 ROAD_PROJECTION 时 `ImGuiManager.setVisible()` | 改 `RoadStudioOverlay.setVisible()` |
| `road/network/SplineEditorEnterPacket.java:36/40` | 进/出编辑器 `setVisible()` | 同上 |
| `road/client/SplineEditorClientState.java:265` | `exitEditMode()` 隐藏面板 | 同上 |
| `road/client/SplineEditorController.java` | 9 处 `ImGuiManager.isInitialized() && ImGui.getIO().getWantCapture…` | 改面板命中判定 |
| `road/client/RoadPlacementController.java:87` | `imguiWantsMouse` 决定世界点击归属 | 同上 |
| `overview/client/OverviewFlightController.java:494` | `imguiWantsMouse` 暂停俯瞰相机 | 同上 |

## 改造步骤（分阶段提交，大重构逐步留回滚点）

### 阶段 1：摘除依赖 + 死代码（保持可编译）

1. `build.gradle` 删除全部 imgui 依赖（implementation×3 / jarJar×3 / additionalRuntimeClasspath×3 / testRuntimeOnly×1）。
2. 删除 `imgui/` 包、`SplineEditorImGui`、两个字体测试、`fonts/` 目录。
3. 新建 `road/client/RoadStudioOverlay.java` **桩类**：`register()/setVisible(boolean)/isVisible()/isMouseOverPanel()`(恒 false)/`render()`(空)，替换上述全部 `ImGuiManager` 引用点。
   - 桩期结果：编译通过、`./gradlew test` 全绿；道路编辑器「无面板」但自由视角/世界点击/快捷键仍工作。
4. 提交：`refactor: 移除 ImGui native 依赖与字体/调试控制台，道路工坊以空面板桩占位（为原生覆盖层铺路）`

### 阶段 2：原生覆盖层面板框架 + 内容移植

**RoadStudioOverlay 框架**
- 布局：右侧贴缘固定宽度面板（~440px，去掉原 ImGui 左缘拖拽调宽），顶部 header banner + 4 工具模式切换 + 内容区 + 底部操作条。
- 组件容器：持有 `List<AbstractWidget>` 子组件；`RenderGuiEvent.Post` 渲染；`InputEvent.MouseButton/Scrolling/Key` 手动命中测试路由（仅当：编辑器激活 && 面板未隐藏 && `mc.screen == null`）。
- 复用现有组件：`MedievalButton`/`TabBar`/`Slider`(int)/`ScrollableList`/vanilla `EditBox`。
- 需新增组件（ImGui 有而工具库没有）：
  - `MedievalCheckbox`（闭合环/对称锁/路肩边/3D 预览 4 个开关）
  - `FloatSlider`（roll/pitch/yaw 角度 ±180°）
  - 数字输入框（XYZ 坐标/平移偏移/步距：`EditBox` + 解析 + Enter 提交）
  - 下拉选择器（方块预设 / JSON 模板）
- 工具提示：组件 hover → `g.renderTooltip`。

**内容 1:1 移植 `SplineEditorImGui`**
- REPLACE / FILL / DESTROY_FILL：预设下拉 + 起终点 XYZ 输入 + 清除/捕捉脚下 + 覆盖跨度/体积/面积评估 + 下发任务按钮（沿用 `RoadPlacePacket`/`FillBoxPacket`/`DestroyFillPacket`）。
- 曲线编辑（样条 tab）：编辑模式切换（加点/选择拖动）+ 闭合环 + 整体平移 XYZ + 控制点列表（可点选）+ 节点检查器（锚点/前手柄/后手柄单选 + 坐标输入 + 对称锁 + 聚焦 + 删除）。
- 阵列生成（tab）：模板源（V 面板预设/JSON）+ 宽/深/路肩边 + 3D 实时预览开关 + 采样步距 + roll/pitch/yaw + 下发建造（沿用 `SplineEditorController.doBuildArray()`）。
- 模板与工具（tab）：模板名输入 + 保存/读取 JSON（沿用 `SplineEditorClientState.save/loadTemplate`）+ 俯视切换 + 操作指南 + 清空画布 + 关闭工坊。
- 底部操作条。

**提交分段**（每段可编译、可回滚）：
- 2a：覆盖层框架 + 模式切换 + REPLACE/FILL/DESTROY_FILL 三个非样条模式
- 2b：曲线编辑 tab
- 2c：阵列生成 + 模板与工具 tab + 底部操作条

### 阶段 3：输入路由重构（面板 vs 世界判定）

统一判定：鼠标在 `isMouseOverPanel()` 矩形内 → 面板消费；否则 → 世界交互放行。

- `SplineEditorController`：移除 `imguiWantsMouse/imguiWantsKb`；右键拖视角在面板内不触发；WASD 飞行在「正在输入框打字」时暂停；世界点击（`SplineEditorInputHandler.handleClicks`）仅当光标在世界区且 `mc.screen == null`。
- `RoadPlacementController`：`imguiWantsMouse` → `isMouseOverPanel()`。
- `OverviewFlightController`：同上（面板覆盖时暂停俯瞰相机取点）。
- 快捷键（ESC 退出 / G 俯视 / H 指南 / Delete 删点）确保在输入框打字时不误触发。
- 光标提升沿用 `WandscapePanelState` 现有机制，不新造。

### 阶段 4：文档 + 清理 + 版本

- `docs/modules/road.md`：去除 ImGui 引用（预设选择、提交按钮描述改为原生覆盖层）。
- `architecture/packages/road.md`：同步；**删除** `architecture/packages/imgui.md`（内含已不存在的 BuildingEditorImGui/BlueprintEditorImGui，本就过时）。
- `docs/decisions.md`：新增 2026-08-16 决策（ImGui → 原生覆盖层的动机与取舍），旧 ImGui 决策条目标注已作废。
- lang：删除 `gui.wandscape.imgui.*` 死键（en/zh），保留 `gui.wandscape.roadstudio.*`。
- `docs/bugs/imgui-font-cjk-glyph-ranges.md`：标注已过时（该 bug 随 ImGui 一并移除）。
- `gradle.properties`：`mod_version` 1.8.24 → 1.9.0（用户命名 1.9，功能重构改次版本号、第三位归零）。
- 提交：`feat: 道路工坊迁移原生覆盖层并移除 ImGui 依赖，版本 1.9.0`

## 测试计划

- `./gradlew build` 编译通过（无任何 imgui/native 残留）。
- `./gradlew test` 全绿（删除两个依赖 native 的字体测试后）。
- **手动验证清单**（用户运行客户端；`runClient` 禁用，CLAUDE.md）：
  1. V 面板 → 道路 tab → 工坊打开，面板渲染正常（中文无乱码、图标正常）。
  2. 4 模式切换 + 预设下拉 + 起终点输入 + 下发任务（直线替换/立方体填充/铲平垫平）。
  3. 样条：世界点方块加点 / 控制柄拖拽 / 闭合环 / 整体平移 / 节点检查器 / 删除 / 聚焦。
  4. 阵列：宽/深/路肩边调节 + 3D 预览 + roll/pitch/yaw + 下发建造。
  5. 模板：保存 / 读取 JSON 模板。
  6. 自由视角：WASD 飞行 + 右键拖转 + 滚轮缩放 + G 俯视 + H 指南 + ESC 退出。
  7. 面板/世界点击边界：点面板按钮与点世界方块互不误触。
  8. 存档重载后无崩溃（无 native 残留）。

## 风险与注意

- **面板/世界输入边界是最大风险点**：阶段 3 单独提交，误触可单独回滚。
- **中文字体**：原版字体含 CJK unicode 页；若个别生僻字缺失再考虑局部补字，不默认自带大字体。
- **图标**：FontAwesome → `WandscapeTheme` PNG 图标，样式需逐一对齐（本就有等宽图标集）。
- **历史文档**：`docs/decisions.md` 旧 ImGui 条目与 `docs/bugs/imgui-font-*` 保留为历史，标注作废即可，不物理删除。
- 不触碰服务端与道路数据/网络格式，风险面小。

## 范围外（不做）

- 不重构 `SplineEditorRenderer`/`SplineEditorInputHandler` 核心算法。
- 不改道路数据格式与网络包（`SplineBuildPacket`/`RoadPlacePacket`/`FillBoxPacket`/`DestroyFillPacket` 不动）。
- 不调整 C 键相位切换、Ghost 渲染、V 面板/俯瞰既有机制。
