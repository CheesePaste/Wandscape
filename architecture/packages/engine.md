# engine/ — MC 适配层

实现 core 边界接口，连接 ECS 引擎与 Minecraft 世界。

## 核心流程

`Wandscape.onServerStarting()` → `EngineBootstrap.bootstrap()` → `Wandscape.onServerTick()` → `world.tick(1.0f)`

## 关键类

### 引擎持有者

- **WandscapeEngine.java** — 单例持有：World + AsyncTransformExecutor + WandscapeRitualOps + WandscapeBlockInteractExecutor + WandscapeMovementOps + BlueprintConfigLoader + TaskPoolSavedData + RoadSavedData
- `reset()` 在 `ServerStoppedEvent` 调用，清除所有静态状态。注意：`blueprintConfigLoader` 故意不清空——由 `WandscapeDataLoader` 管理

### 引导

- **EngineBootstrap.bootstrap()** — 一次性装配：
  1. 注册 DSL 蓝图层（BlueprintConfigLoader → BlueprintInterpreter → BlueprintRegistry）
  2. 注册遗留建筑蓝图（无 blueprint ref → DataDrivenSteps fallback）
  3. 注册系统蓝图（EventDrivenTaskSource）
  4. 构建 TaskSource 列表（BuildingTaskSource + WarehouseSource + WorkbenchSource + RoadTaskSource）
  5. 构建边界实现并注入 core
  6. 启动 core → `CoreBootstrap.bootstrap(config)`
  7. 注册默认 OpExecutor + AsyncTransformExecutor(每方块5tick延迟) + WandscapeBlockInteractExecutor
  8. 注册 NavigationSystem

### 边界实现

- **WandscapeBlockOps** — `BlockOps` 实现：setBlock/getBlock/isAir/toggle/activate/openGui。支持 bracket 语法 `"mod:block[prop=val]"`。activate 采用两级策略：useWithoutItem → redstone pulse fallback
- **WandscapeMovementOps** — NPC 移动，无状态适配器写入 NavigationState
- **WandscapeRitualOps** — 异步引导：PendingRitual 队列 + tickAll 倒计时 → thenRun 执行。self_teleport 600tick 引导后传送
- **AsyncTransformExecutor** — 覆盖 TransformOp 执行器，N-tick 延迟（默认5），实现异步方块放置效果
- **WandscapeBlockInteractExecutor** — 处理 BlockInteractOp（同步 toggle/activate + 异步 gather/decompose/synthesize）

### TaskSource

- **BuildingTaskSource** — 每 20tick 轮询：清理已完成任务 → 节点自动供给 → 发布新 WorkItem → TaskRequest 入池。发布后检测到任务落在 PENDING_APPROVAL 时自动 approve（建筑修复是殖民地自治行为，不能卡在玩家审批门后）。这是 BE → 引擎的唯一桥梁
- **RoadTaskSource** — 监听 build_complete 事件 → 触发生成路网
- **WarehouseSource** / **WorkbenchSource** — V1 stub，监视资源/生产队列

### 持久化

- **TaskPoolSavedData** — 跨会话任务持久化（Level SavedData）。保存 blueprintId + taskParams + stepIndex + state → NBT。重载时从蓝图重新编译恢复进度

### 道路 MC 层

- **RoadBuilder** — 执行路径方块放置：挖+填+水面桥+调色板加权随机选取
- **RoadSavedData** — 路网持久化（Level SavedData）。NBT nodes 不显式序列化——加载时从 BuildingSavedData 重建
- **RoadEventListener** — 订阅 build_complete → 触发路网增量更新
- **DecorationBuilder** — 执行装饰放置（灯柱+长椅）

### NavigationSystem

在 `engine/system/`（非 core/system/），因为依赖 MC Pathfinder。NPC 移动总控：≤64格寻路 + 卡死检测(每60tick/3次→传送) + 超时→传送。超距或失败时向私有队列推入 RitualOp(SELF_TELEPORT)，由 TaskExecutionSystem 统一执行而非直接操作 MC 实体。
