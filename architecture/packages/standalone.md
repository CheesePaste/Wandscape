# standalone/ — 独立编辑器启动器

无需 Minecraft 环境的蓝图编辑器启动器。通过 `./gradlew runEditor` 运行。

## 关键类

- **EditorStandalone** — 创建纯 GLFW 窗口，初始化 ImGui + imgui-node-editor，以隔离方式运行蓝图节点编辑器，用于快速 UI 开发调试

## 使用场景

- 蓝图节点编辑器的 UI 开发（无需启动 MC 客户端）
- ImGui 组件布局调试
- NodeEditor 交互测试

## 依赖

- blueprint/editor/BlueprintEditorImGui
- imgui/ImGuiManager（ImGui 上下文）
