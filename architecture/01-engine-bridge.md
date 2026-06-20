# 01 — 引擎集成层 (`engine/`)

MC 桥梁：实现 core 边界接口，WandscapeEngine 单例持有 World，BuildingTaskSource 是 BE → 引擎的唯一通道。

## 源文件 (11 文件)

| 文件 | 作用 |
|------|------|
| `WandscapeEngine.java` | **单例**持有 World 实例 + AsyncTransformExecutor + BlueprintConfigLoader + WandscapeMovementOps。`ServerStarting` 时 `EngineBootstrap.bootstrap()` 注入 |
| `bootstrap/EngineBootstrap.java` | 组装引导：创建边界 MC 实现 + TaskSource 列表 + 注册 DSL 蓝图 (BlueprintConfigLoader) + 遗留 DataDrivenSteps fallback → `CoreBootstrap.bootstrap(config)` → 注入单例 |
| `boundary/WandscapeBlockOps.java` | BlockOps MC 实现：`Level.setBlock()` / `getBlockState()` / `isAir()`，放置前 `evacuateEntities()` 疏散方块内生物 |
| `boundary/WandscapeMovementOps.java` | MovementOps MC 实现：无状态适配器，`navigateTo()` 写入 NavigationState（mode + target + future），由 NavigationSystem 驱动实际移动（≤32 寻路、>32 仪式传送、魔力不足等待） |
| `boundary/WandscapeEntityOps.java` | EntityOps MC 实现：**阶段 2 stub**，applyEffect / getPosition 为空操作 |
| `boundary/WandscapeRitualOps.java` | RitualOps MC 实现：`self_teleport` 通过 `EntityComponentBridge` 查找 NPC → `teleportTo()`，返回 completedFuture |
| `boundary/AsyncTransformExecutor.java` | **V2.5 异步门控**：TransformOp 异步执行器，N-tick countdown + thenRun → 放置方块。`tickAll()` 递减计数器并完成 Future |
| `system/NavigationSystem.java` | **NPC 移动总控**：ECS System，注册在 TaskExecutionSystem 后。读取 NavigationState → 驱动寻路（moveTo + 卡死检测 + 超时 + 重寻路）/ 仪式传送（扣魔力 + 粒子）/ 魔力等待 |
| `source/BuildingTaskSource.java` | **核心 TaskSource**：每 20 tick 轮询 `BuildingApi.getBuildingsWithPendingWork()` → dequeue WorkItem → `pool.addTask(TaskRequest)`。WorkItem.params 为 `Map<String, JsonElement>` |
| `source/blueprint/DataDrivenSteps.java` | **遗留 fallback**：无 BlueprintRef 的建筑自动注册 `build:<id>` 蓝图（pattern + block_mapping → TransformOp 序列） |
| `source/blueprint/BlueprintConfigLoader.java` | **DSL 蓝图加载器**：从 `data/wandscape/blueprints/*.json` 解析为 BlueprintDefinition AST。`registerWith(WandscapeDataLoader)` + `registerIn(BlueprintRegistry)` |

## 引擎 tick 流程

```
Wandscape.onServerTick (Post):
  ① asyncExec.tickAll()            — 异步 Future 倒计时
  ② bridge.syncPositions(world)    — MC→ECS 位置同步（始终执行）
  ③ if (world.hasPendingAsyncOps()) return  — V2.5 门控
  ④ world.tick(1.0f)               — 引擎逻辑帧：
       ManaRegen → TaskSourcePoller → Scheduler → TaskExecution → Navigation → SystemBlueprint
```

NavigationSystem 注册在 TaskExecutionSystem 之后，同一 tick 内 TaskExecutionSystem 写入 NavigationState 后立即被 NavigationSystem 拾取并驱动移动。

## 依赖

- `core/` — 全部边界接口、World、ECS 组件、任务系统
- `shared/api/BuildingApi` — BuildingTaskSource 通过 WandscapeApis 调用
- `npc/internal/EntityComponentBridge` — WandscapeRitualOps / WandscapeMovementOps 查找 NPC
