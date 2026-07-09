# 开发路线图

## 当前阶段：阶段 3 — 经济循环 ✅

采集→存储→消耗 完整经济链 + 游客经济系统已实现：
- ✓ ECS 引擎 + 任务池 + 调度器 + 蓝图 DSL
- ✓ 法杖物品 + 元素映射 + 建筑管理(SavedData)
- ✓ NPC 实体 + ECS 桥接 + 渲染
- ✓ 道路系统（MST + 路网生成 + 装饰 + 编辑器 + 宽面渲染 + 路径规划 + 预览 + 拆除）
- ✓ 仓库 GUI + ColonyItemBank + 网络同步
- ✓ 工作站 GUI + 制作站 GUI + 节点自动采集 → 仓库闭环
- ✓ 法杖需求接线 + 失败分析器 + 智能资源调度级联
- ✓ 殖民地三值评估 + 配方解锁
- **✓ 游客经济全系统**：维护费 + 装饰辐射 + 商店 + 奇观 + 游客(生成/移动/交互/离开) + 宾馆 + 酒馆招募

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

## 未实现的 API

ColonyApi / HouseApi / ManaPoolApi / AtomicExecutor（被 core/op 替代）

~~TaskApi~~ — 已实现 (2026-06-22)
~~TavernApi~~ — 部分实现 (2026-06-26)：mage resume 存储/查询/招募已可用；3 个 NPC 招募方法仍为占位

对应的模块：殖民地生命周期、房屋分配、魔力池 — 均为阶段 4 内容。

## 待完成

| 优先级 | 事项 | 涉及 |
|--------|------|------|
| 中 | GlobalTaskPool COMPLETED 任务清理（内存泄漏） | core/task/GlobalTaskPool |
| 中 | 道路拆除改为 NPC 任务执行 | road/server/RoadEditorHandler |
| 中 | 奇观效果影响游客满意度 | WonderEffectApplier → TouristMoveGoal |
| 中 | 游客离开动画（走出边界后移除） | TouristSpawnSystem |
| 中 | 商店 max_stock 调整 GUI | building/client/ShopScreen |
| 低 | 连续执行加成从硬编码移至 TOML | Config + SchedulerSystem |
| 低 | 殖民地系统（创建/删除/边界） | 新模块 |
| 低 | 魔药站 GUI 实现 | production/client/ |
| 低 | 多人游戏同步 | 网络包 |
| 低 | TavernApi NPC 招募方法实现 | tavern/internal/ | |

## 已完成：殖民地三值评估系统 (2026-06-23)

### 评估数据流

```
建筑首次建造完成(structureIntact=true)
  → BuildCompleteListener → BuildingSavedData.addBuildingContribution()
  → BuildingContributionRegistry: intactCount(type) 0→1
  → 广播 ColonyEvaluationChangedEvent → 三值增加

建筑损毁(structureIntact=false) / 拆除(unregister)
  → BuildingBreakHandler / BuildingApiImpl.unregisterBuilding
  → BuildingSavedData.removeBuildingContribution()
  → BuildingContributionRegistry: intactCount(type) 1→0
  → 广播 ColonyEvaluationChangedEvent → 三值扣减
```

### 新增文件

| 文件 | 层 | 用途 |
|------|-----|------|
| `shared/event/ColonyEvaluationChangedEvent.java` | shared | NeoForge 事件：colonyId + old/new 三值 |
| `building/internal/BuildingContributionRegistry.java` | building | per-colony per-type intactCount 缓存 |
| `building/internal/BuildingUnlockChecker.java` | building | 建筑建造解锁校验 |
| `production/data/RecipeUnlockRequirement.java` | production | 配方解锁三门槛 record |
| `production/internal/RecipeUnlockChecker.java` | production | 配方解锁静态工具 |
| `production/data/RecipeUnlockRequirementTest.java` | test | 配方解锁 JSON 解析测试 |

### 修改文件

- `BuildingSavedData` — 新增 contributionRegistry 字段 + add/removeBuildingContribution() 入口 + load() 后 rebuildFrom()
- `BuildingApiImpl` — getColonyComfort/Magic/Wonder 通过 registry.getSnapshot() 查询
- `BuildCompleteListener` — 修复完成调用 addBuildingContribution()
- `BuildingBreakHandler` — 结构损坏调用 removeBuildingContribution()
- `BuildingApiImpl.unregisterBuilding` — 建筑拆除调用 removeBuildingContribution()
- `SynthesizeRecipe/CraftWandRecipe/BrewPotionRecipe` — unlockMagicValue → unlockRequirement 三字段
- `WorkstationDataPacket/CraftingStationPacket` — from() 过滤 + NBT 序列化 unlockRequirement
- `RequestProductionTaskPacket` — 服务端二次验证防篡改
- `BuildingInteractHandler` — 默认右键建筑展示锁因提示
- `production/client/WorkstationScreen` / `CraftingStationScreen` — 客户端渲染配方解锁状态（待实现，目前 only unfiltered recipes shown）

### 配方解锁规则

配方 JSON 使用 `unlock_requirement` 三字段，无 legacy 兼容分支：

```json
{
  "unlock_requirement": { "min_comfort": 0, "min_magic": 10, "min_wonder": 0 }
}
```

三值同时满足才解锁；填 0 表示该维度无门槛。

## 已完成：失败分析器 (2026-06-23)

### 当前覆盖

**法杖能力不足 → 自动制作法杖**。当任务因 `WandRequirementUnmet` 失败时，FailureAnalyzerSystem 自动：
1. 从任务 requirements（如 `{GATHERING:1}`）反查匹配的法杖预设
2. 通过任务 anchor 定位殖民地
3. 在殖民地 crafting_station 入队 `craft_wand` 任务
4. 去重：同一任务不重复处理，同一法杖已在制作中不重复入队

法杖制作完成后存入仓库 → 下一轮节点采集/合成任务正常分配。

### 数据流

```
SchedulerSystem
  │  无 NPC 满足 + 仓库无法杖
  │  → taskPool.failTask(id, new WandRequirementUnmet(reqs))
  ▼
GlobalTaskPool  →  task.state = FAILED, task.failureReason = reason
  │
  ▼
FailureAnalyzerSystem (20tick 心跳)
  │  1. taskPool.getByState(FAILED)
  │  2. 匹配 failureReason instanceof WandRequirementUnmet
  │  3. 遍历 WandPresetLoader → 找覆盖所有 reqs 的预设
  │  4. taskParams.anchor → BuildingSavedData → colonyId
  │  5. BuildingApi.getBuildingsByCategory(colonyId, "crafting_station")
  │  6. api.enqueueWork(stationId, craft_wand WorkItem)
  ▼
BuildingTaskSource.poll() → TaskRequest → GlobalTaskPool → SchedulerSystem → NPC 制作法杖
```

### 规划：建造/维护元素不足 → 自动节点采集

**`ResourceUnavailable`** 失败原因（待实现）：

```
建造建筑
  │  ColonyItemBank 检查元素不足
  │  → failTask(id, new ResourceUnavailable(missingElements: Map<ElementType, Long>))
  ▼
FailureAnalyzerSystem
  │  1. 匹配 failureReason instanceof ResourceUnavailable
  │  2. missingElements 映射到对应 node 类型（earth→earth节点, wood→wood节点...）
  │  3. 在殖民地查找匹配的 node 建筑（BuildingApi.getBuildingsByCategory(colonyId, "node")）
  │  4. 对每个缺失元素，入队 gather WorkItem（如有对应 node 建筑）
  ▼
节点自动采集 → 元素入仓 → 建造重试
```

**设施维护**（待实现）同理：维护消耗后若元素池枯竭，触发的 `ResourceUnavailable` 由同一个 FailureAnalyzerSystem 处理。

### 涉及的新增/修改

| 文件 | 变更 |
|------|------|
| `core/task/TaskFailureReason.java` | + `ResourceUnavailable(Map<ElementType, Long> missingElements)` |
| `engine/system/FailureAnalyzerSystem.java` | + `handleResourceUnavailable()` 分支 |
| `building/internal/BuildCompleteListener.java` 或建造执行路径 | 建造前检查元素 → 不足则 failTask |
| 设施维护系统（待构建） | 维护后检查元素池 → 不足则 failTask |

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

## 已完成：道路样条线物流与客户端插值 (2026-07-09)

### 当前覆盖

- **物资丝滑运输**：原 `ItemTransportManager` 的服务端实时计算已被重构。现在在运输开始时通过 `TransportStartPacket` 发送完整的路径（包含 `SplineLeg`），由客户端实体 `TransportItemEntity` 自主负责按照 60 FPS 进行高平滑度插值计算。
- **真弧长采样计算**：替换了之前基于欧几里得距离计算耗时的粗糙算法。现在的 `SplineLeg` 能够对路径点曲线进行细致的微小线段距离累加（Tessellation approximation），有效防止了弯道“瞬移”和物资飞行动画超速的问题，严格遵循道路加成设定。
- **资源分配扣除修复**：修复了 `ResourceRequestExecutor` 深层的重复扣除 (double-subtraction) Bug，消除了由于中断或者多次分配引发的僵尸建造节点导致的物资阻塞情况。

## 后续阶段（概览）

- **阶段 4**：殖民地生命周期 + 房屋 + 魔力池 + 祭坛 + 管理面板
- **阶段 5**：性能压测 + 多人游戏 + 指南书
