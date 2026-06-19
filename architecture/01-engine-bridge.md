# 01 — 引擎集成层 (`engine/`)

MC 桥梁：实现 core 边界接口，WandscapeEngine 单例持有 World，BuildingTaskSource 是 BE → 引擎的唯一通道。

## 源文件 (8 文件)

| 文件 | 作用 |
|------|------|
| `WandscapeEngine.java` | **单例**持有 World 实例 + AsyncTransformExecutor。`ServerStarting` 时 `EngineBootstrap.bootstrap()` 注入 |
| `bootstrap/EngineBootstrap.java` | 组装引导：创建边界 MC 实现 + TaskSource 列表 + Blueprint → `CoreBootstrap.bootstrap(config)` → 注入单例 |
| `boundary/WandscapeBlockOps.java` | BlockOps MC 实现：`Level.setBlock()` / `getBlockState()` / `isAir()`，ConcurrentHashMap 缓存字符串→Block 查找 |
| `boundary/WandscapeEntityOps.java` | EntityOps MC 实现：**阶段 2 stub**，applyEffect / getPosition 为空操作 |
| `boundary/WandscapeRitualOps.java` | RitualOps MC 实现：`self_teleport` 通过 `EntityComponentBridge` 查找 NPC → `teleportTo()`，返回 completedFuture |
| `boundary/AsyncTransformExecutor.java` | **V2.5 异步门控**：TransformOp 异步执行器，N-tick countdown + thenRun → 放置方块。`tickAll()` 递减计数器并完成 Future |
| `source/BuildingTaskSource.java` | **核心 TaskSource**：每 20 tick 轮询 `BuildingApi.getBuildingsWithPendingWork()` → dequeue WorkItem → `pool.addTask(TaskRequest)` |
| `source/blueprint/DataDrivenSteps.java` | 从 JSON BuildingConfig 自动生成 `build:<id>` 蓝图（pattern + block_mapping → TransformOp 序列） |

## 引擎 tick 流程

```
Wandscape.onServerTick (Post):
  ① asyncExec.tickAll()            — 异步 Future 倒计时
  ② bridge.syncPositions(world)    — MC→ECS 位置同步（始终执行）
  ③ if (world.hasPendingAsyncOps()) return  — V2.5 门控
  ④ world.tick(1.0f)               — 引擎逻辑帧：
       ManaRegen → TaskSourcePoller → Scheduler → TaskExecutor → SystemBlueprint
```

## 依赖

- `core/` — 全部边界接口、World、ECS 组件、任务系统
- `shared/api/BuildingApi` — BuildingTaskSource 通过 WandscapeApis 调用
- `npc/internal/EntityComponentBridge` — WandscapeRitualOps 查找 NPC
