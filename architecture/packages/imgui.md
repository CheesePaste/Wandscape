# imgui/ — ImGui 管理器

ImGui 生命周期管理器 + 渲染调度中枢。F12 切换显隐。

## 调用关系

```
WandscapeClient (按键注册)
  → ImGuiManager.toggle() (控制显隐)
  → 每帧 dispatch:
    → SplineEditorImGui.render() (道路制作工坊面板，收口 Replace / Fill / DestroyFill / Spline 4大模式)
    → BuildingEditorImGui.render() (建筑编辑器面板)
    → BlueprintEditorImGui.render() (蓝图节点编辑器面板)
```

## 依赖

- ImGui (Dear ImGui) + imgui-java + imgui-node-editor
- GLFW + OpenGL
- building/editor/BuildingEditorImGui + BuildingEditorController
- blueprint/editor/BlueprintEditorImGui + BlueprintEditorController
