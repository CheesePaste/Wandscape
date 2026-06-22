# 开发路线图

## 当前阶段：阶段 3 — 经济循环

采集→存储→消耗 完整经济链已实现：
- ✓ ECS 引擎 + 任务池 + 调度器 + 蓝图 DSL
- ✓ 法杖物品 + 元素映射 + 建筑管理(SavedData)
- ✓ NPC 实体 + ECS 桥接 + 渲染
- ✓ 道路系统（MST + 路网生成 + 装饰）
- ✓ 仓库 GUI + ColonyItemBank + 网络同步
- ✓ 工作站 GUI（decompose/synthesize）+ 制作站 GUI（craft_wand）+ 魔药站骨架
- ✓ 节点自动采集 → 仓库闭环
- ✓ PosIndex chunkIndex fallback（重进游戏建筑可交互）

## 已完成的模块

| 包 | 状态 |
|----|------|
| core/ (ECS引擎+任务+蓝图+道路) | 功能完整 |
| engine/ (MC桥接) | 功能完整 |
| shared/ (API+事件+UI) | API 部分未实现(见下) |
| building/ | 功能完整 |
| wand/ | 功能完整 |
| element/ | 功能完整 |
| npc/ | 功能完整 |
| warehouse/ | 功能完整 |
| production/ | 功能完整 — GUI + 配方 + executeDecompose/Synthesize/CraftWand/BrewPotion |
| dataconfig/ | 功能完整 |
| command/ | 调试命令集 |

## 未实现的 API（在 WandscapeApis 中定义但无实现）

ColonyApi / HouseApi / ManaPoolApi / TavernApi / AtomicExecutor（被 core/op 替代）

对应的模块：殖民地生命周期、房屋分配、魔力池、酒馆招募 — 均为阶段 3-4 内容。

~~TaskApi~~ — 已实现 (2026-06-22)，详见 [GUI 任务编辑器](#gui-任务编辑器)

## 待完成

| 优先级 | 事项 | 涉及 |
|--------|------|------|
| 高 | 结构损坏后自动入队修复 | building/BuildingBreakHandler |
| 中 | GlobalTaskPool COMPLETED 任务清理（内存泄漏） | core/task/GlobalTaskPool |
| 中 | 祭坛多方块检测从 tick() 改为事件驱动 | — (模块未构建) |
| 低 | 连续执行加成从硬编码移至 TOML | Config + SchedulerSystem |
| 低 | 殖民地系统（创建/删除/边界） | 新模块 |
| 低 | 魔药站 GUI 实现 | production/client/ |
| 低 | 多人游戏同步 | 网络包 |

## GUI 任务编辑器

**已完成 (2026-06-22)**。玩家按 `T` 键打开，可视化浏览蓝图、编辑参数、发布任务。

### 数据流

```
TaskEditorScreen (客户端)
  │  T 键打开 → 发送 TaskEditorOpenPacket
  │  服务端回复 BlueprintListResponsePacket → 显示蓝图列表
  │  用户选择蓝图 → 动态生成参数输入框
  │  点 [Publish] → 发送 TaskCreatePacket
  ▼
TaskApiImpl (服务端防腐层)
  │  getAvailableBlueprints() → BlueprintConfigLoader.getAll()
  │  publishTask() → PlayerManualSource.publish(TaskRequest)
  ▼
GlobalTaskPool.addTask() → SchedulerSystem → NPC 执行
```

### 新增文件

| 文件 | 层 | 用途 |
|------|-----|------|
| `shared/data/ParamTypeInfo.java` | shared | core ParamType 的枚举镜像 |
| `shared/data/BlueprintInfo.java` | shared | 蓝图元数据 DTO |
| `shared/ui/task/TaskEditorClientState.java` | client | 线程安全静态状态 |
| `shared/ui/task/TaskEditorScreen.java` | client | MedievalScreen 子类 GUI |
| `task/internal/TaskApiImpl.java` | server | TaskApi 实现，桥接 PlayerManualSource |
| `task/network/TaskEditorOpenPacket.java` | C→S | 打开编辑器，请求蓝图列表 |
| `task/network/BlueprintListResponsePacket.java` | S→C | 携带蓝图列表 |
| `task/network/TaskCreatePacket.java` | C→S | 创建任务 |
| `task/network/TaskNetworkHandler.java` | server | 网络工具类 |

**零改动**: `core/` 下所有文件（PlayerManualSource、GlobalTaskPool、BlueprintRegistry 等）全部不动。

## 后续阶段（概览）

- **阶段 3**：殖民地生命周期 + 房屋 + 魔力池 + 酒馆招募
- **阶段 4**：节点建筑自动供给 + 祭坛 + 管理面板
- **阶段 5**：性能压测 + 多人游戏 + 指南书
