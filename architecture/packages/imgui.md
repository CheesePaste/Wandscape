# imgui/ — ImGui 管理器

ImGui 生命周期管理器 + 渲染调度中枢。通过 F12 键（WandscapeClient.IMGUI_TOGGLE）切换显隐。

## 关键类

- **ImGuiManager** — ImGui 核心管理器。创建 ImGui 上下文、初始化 GLFW/OpenGL 后端、NodeEditor 上下文管理、GUI 显隐切换(toggle)、鼠标捕获/释放、输入事件拦截（鼠标按钮/滚轮）、每帧渲染调度（委托给 BuildingEditorImGui 或 BlueprintEditorImGui）、shutdown 资源清理。含简单 "Wandscape Debug" 调试 GUI

## 调用关系

```
WandscapeClient (按键注册)
  → ImGuiManager.toggle() (控制显隐)
  → 每帧根据编辑模式 dispatch:
    → BuildingEditorImGui.render() (建筑编辑器面板)
    → BlueprintEditorImGui.render() (蓝图节点编辑器面板)
```

## ImGui 面板

- **BuildingEditorImGui** (building/editor/) — 建筑属性编辑面板（双列布局）
- **BlueprintEditorImGui** (blueprint/editor/) — 蓝图节点编辑器面板（画布/引脚/连线和创建检测/右键菜单/搜索/检查器/自动排版）

## 相关控制器

- **BuildingEditorController** (building/editor/) — 建筑编辑器 tick 控制器
- **BlueprintEditorController** (blueprint/editor/) — 蓝图编辑器保存/加载/退出操作

## 命令入口

- **BlueprintEditorCommand** (command/) — `/wandscape blueprinteditor` 命令，调 ImGuiManager.toggleBlueprintEditor()

## 依赖

- ImGui (Dear ImGui) + imgui-java + imgui-node-editor
- GLFW + OpenGL
- building/editor/BuildingEditorImGui + BuildingEditorController
- blueprint/editor/BlueprintEditorImGui + BlueprintEditorController
