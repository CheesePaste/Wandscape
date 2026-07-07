# engine/ — MC 适配层

实现 core 边界接口，连接 ECS 引擎与 Minecraft 世界。

**重构后**：road/colony 子包已移出（road/ → `road/engine/`，ColonyApiImpl → engine 根包）。system/ 拆分为 system/（ECS System）+ service/（事件订阅者）。

## 核心流程

`Wandscape.onServerStarting()` → `EngineBootstrap.bootstrap()` → `Wandscape.onServerTick()` → `world.tick(1.0f)`

## 关键类

### 引擎持有者

- **`WandscapeEngine.java`** — 单例持有：World + AsyncTransformExecutor + WandscapeRitualOps + WandscapeBlockInteractExecutor + WandscapeMovementOps + WandscapeEntityOps + BlueprintConfigLoader + TaskPoolSavedData + RoadSavedData + ItemTransportManager
- `reset()` 在 `ServerStoppedEvent` 调用，清除所有静态状态。`blueprintConfigLoader` 故意不清空——由 `WandscapeDataLoader` 管理

### 引导

- **`EngineBootstrap.bootstrap()`** — 一次性装配：
  1. 注册 DSL 蓝图层（BlueprintConfigLoader → BlueprintInterpreter → BlueprintRegistry）
  2. 注册遗留建筑蓝图（无 blueprint ref → DataDrivenSteps fallback）
  3. 注册系统蓝图（EventDrivenTaskSource）
  4. 构建 TaskSource 列表（BuildingTaskSource + WarehouseSource + WorkbenchSource + RoadTaskSource + PlayerManualSource）
  5. 构建边界实现并注入 core
  6. 启动 core → `CoreBootstrap.bootstrap(config)`
  7. 注册默认 OpExecutor + AsyncTransformExecutor(每方块5tick延迟) + WandscapeBlockInteractExecutor + WandscapeEntityOps + ResourceRequestExecutor
  8. 注册 NavigationSystem + FailureAnalyzerSystem 到 World
  9. 注册 StatsRecorder + AchievementService 到 EventBus

### 边界实现 (boundary/)

- **`WandscapeBlockOps`** — `BlockOps` 实现：setBlock/getBlock/isAir/toggle/activate/openGui。支持 bracket 语法 `"mod:block[prop=val]"`。activate 采用两级策略：useWithoutItem → redstone pulse fallback
- **`WandscapeMovementOps`** — NPC 移动，无状态适配器写入 NavigationState
- **`WandscapeRitualOps`** — 异步引导：PendingRitual 队列 + tickAll 倒计时 → thenRun 执行。self_teleport 600tick 引导后传送
- **`AsyncTransformExecutor`** — 覆盖 TransformOp 执行器，N-tick 延迟（默认5），实现异步方块放置效果
- **`WandscapeBlockInteractExecutor`** — 处理 BlockInteractOp（同步 toggle/activate + 异步 gather/decompose/synthesize）
- **`WandscapeEntityOps`** — `EntityOps` 实现：NPC 间实体交互/检查
- **`ResourceRequestExecutor`** — 处理 ResourceRequestOp：从 ColonyItemBank 提取/存入资源

### TaskSource 实现 (source/)

- **`BuildingTaskSource`** — 每 20tick 轮询：清理已完成任务 → 节点自动供给 → 发布新 WorkItem → TaskRequest 入池。这是 BE → 引擎的唯一桥梁
- **`BlueprintConfigLoader`** — JSON 蓝图配置加载器（在 source/blueprint/ 子包）
- **`DataDrivenSteps`** — 遗留建筑蓝图 fallback（在 source/blueprint/ 子包）

**注意：** 纯 core 的 TaskSource（TaskSourcePoller、EventDrivenTaskSource、PlayerManualSource、WorkbenchSource）在 `task/source/`，RoadTaskSource 在 `road/engine/`。

### 持久化

- **`TaskPoolSavedData`** — 跨会话任务持久化（Level SavedData）。保存 blueprintId + taskParams + stepIndex + state → NBT。重载时从蓝图重新编译恢复进度

### ECS 系统 (system/) — 注册到 World.tick()

- **`NavigationSystem`** — NPC 移动总控：≤64格寻路 + 卡死检测(每60tick/3次→传送) + 超时→传送。依赖 MC Pathfinder。`implements System`
- **`FailureAnalyzerSystem`** — 每 20tick 心跳分析 FAILED 任务。当前覆盖法杖能力不足 → 自动制作法杖。`implements System`

### 后台服务 (service/) — EventBus 订阅者

非 ECS System，通过 `world.eventBus.subscribe()` 注册到核心 EventBus。

- **`StatsService`** — 原 `StatsSystem`。订阅 `NarrativeEventTriggered`，记录每殖民地统计数据（骨架，未来实现）
- **`AchievementService`** — 原 `AchievementSystem`。订阅 `NarrativeEventTriggered`，评估成就触发条件（骨架，未来实现）

### 物品运输 (transport/)

- **`ItemTransportManager`** — 物品运输管理器，处理 NPC 与仓库之间的物品流转。
  - **单实体合并视觉表现**：在处理大批物品运输时，系统不再为每个物品创建单独的 `ItemEntity`，而是将同类物品合并为一个 `TransportItemEntity`（自定义实体类型 `wandscape:transport_item`）作为视觉效果，有效降低渲染开销。
  - **自定义客户端渲染 (`TransportItemEntityRenderer`)**：针对 `TransportItemEntity` 编写了专属渲染器。当传输的物品数量大于0时，会在物品上方渲染一个定制的**中性金边暗灰底胶囊气泡**。
    - 该气泡使用 `debugQuads` 进行底层多层渲染。气泡外框（金色）、主体（暗灰）与文字（暖白）采用微小 Z 轴偏置（Z-Offset Layering）以防止深度冲突（Z-Fighting）导致画面闪烁。

### ColonyApiImpl（engine 根包）

- **`ColonyApiImpl`** — ColonyApi 实现（原在 engine/colony/）。桥接 BuildingSavedData 查询殖民地信息。
