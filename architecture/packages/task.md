# task/ — 任务编辑器网络层

玩家通过任务编辑器 GUI 手动创建/提交任务。网络通信层在 `task/network/`，UI 实现在 `shared/ui/task/`。

## 网络包 (network/) — 4 个文件

| 包 | 方向 | 用途 |
|----|------|------|
| TaskEditorOpenPacket | C→S | 玩家打开了任务编辑器 |
| BlueprintListResponsePacket | S→C | 响应任务编辑器打开，发送蓝图列表 |
| TaskCreatePacket | C→S | 创建新任务（选蓝图+设参数+优先级） |
| TaskNetworkHandler | Server | 服务端网络处理器，处理所有任务相关包 |

## 客户端 UI (shared/ui/task/)

- **TaskEditorClientState** — 客户端 GUI 状态持有者（单例）：蓝图列表/选中蓝图/草稿参数/优先级
- **TaskEditorScreen** — 继承 MedievalScreen，含蓝图列表+参数编辑框+提交按钮。收发网络包

## 数据流

```
玩家按快捷键打开任务编辑器
  → TaskEditorOpenPacket → 服务端返回 BlueprintListResponsePacket
  → TaskEditorScreen 渲染蓝图列表
  → 玩家选择蓝图+设参数+优先级+提交
  → TaskCreatePacket → TaskNetworkHandler
  → TaskRequest → PlayerManualSource → GlobalTaskPool
  → SchedulerSystem 分配 → NPC 执行
```

## 依赖

- core/task/BlueprintRegistry / TaskCompiler
- core/system/PlayerManualSource
- shared/ui/task/TaskEditorClientState / TaskEditorScreen
- shared/registry/WandscapeApis
