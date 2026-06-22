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

TaskApi / ColonyApi / HouseApi / ManaPoolApi / TavernApi / AtomicExecutor（被 core/op 替代）

对应的模块：殖民地生命周期、房屋分配、魔力池、酒馆招募 — 均为阶段 3-4 内容。

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

## 后续阶段（概览）

- **阶段 3**：殖民地生命周期 + 房屋 + 魔力池 + 酒馆招募
- **阶段 4**：节点建筑自动供给 + 祭坛 + 管理面板
- **阶段 5**：性能压测 + 多人游戏 + 指南书
