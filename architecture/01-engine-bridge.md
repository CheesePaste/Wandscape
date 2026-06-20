# 01 — 引擎集成层 (`engine/`)

MC 桥梁：实现 core 边界接口，WandscapeEngine 单例持有 World，BuildingTaskSource 是 BE → 引擎的唯一通道。

## 源文件 (10 文件)

| 文件 | 作用 |
|------|------|
| `WandscapeEngine.java` | **单例**持有 World 实例 + AsyncTransformExecutor + BlueprintConfigLoader + WandscapeMovementOps。`ServerStarting` 时 `EngineBootstrap.bootstrap()` 注入 |
| `bootstrap/EngineBootstrap.java` | 组装引导：创建边界 MC 实现 + TaskSource 列表 + 注册 DSL 蓝图 (BlueprintConfigLoader) + 遗留 DataDrivenSteps fallback → `CoreBootstrap.bootstrap(config)` → 注入单例 |
| `boundary/WandscapeBlockOps.java` | BlockOps MC 实现：`Level.setBlock()` 支持**方块状态括号记法**（`minecraft:lever[facing=north,face=floor]`）；**实体疏散**（`setBlock` 前踢出目标 AABB 内活体→相邻空气位）；**方块交互**：`toggle`→`useWithoutItem(null)` / `activate`→useWithoutItem→红石脉冲回退 / `openGui`→`blockEvent`开/关动画+Container 接口 |
| `boundary/WandscapeEntityOps.java` | EntityOps MC 实现：**阶段 2 stub**，applyEffect / getPosition 为空操作 |
| `boundary/WandscapeRitualOps.java` | RitualOps MC 实现：`self_teleport` 通过 `EntityComponentBridge` 查找 NPC → `teleportTo()`，返回 completedFuture |
| `boundary/WandscapeMovementOps.java` | MovementOps MC 实现：**纯传送**（非寻路），5² 水平内已就位→跳过，否则 `npc.setPos(target+0.5)`。`cancelNavigation` 为空（传送无进行中状态） |
| `boundary/AsyncTransformExecutor.java` | **V2.5 异步门控**：TransformOp 异步执行器，N-tick countdown + thenRun → 放置方块 + `npc.doWorkAnimation()`（挥臂+粒子）。`tickAll()` 递减计数器并完成 Future |
| `source/BuildingTaskSource.java` | **核心 TaskSource**：每 20 tick 轮询 `BuildingApi.getBuildingsWithPendingWork()` → dequeue WorkItem → `pool.addTask(TaskRequest)`。WorkItem.params 为 `Map<String, JsonElement>` |
| `source/blueprint/DataDrivenSteps.java` | **遗留 fallback**：无 BlueprintRef 的建筑自动注册 `build:<id>` 蓝图（pattern + block_mapping → TransformOp 序列） |
| `source/blueprint/BlueprintConfigLoader.java` | **DSL 蓝图加载器**：从 `data/wandscape/blueprints/*.json` 解析为 BlueprintDefinition AST。`registerWith(WandscapeDataLoader)` + `registerIn(BlueprintRegistry)` |

## 引擎 tick 流程

```
Wandscape.onServerTick (Post):
  ① asyncExec.tickAll()            — 异步 Future 倒计时
  ② bridge.syncPositions(world)    — MC→ECS 位置同步（始终执行）
  ③ world.tick(1.0f)               — 引擎逻辑帧：
       ManaRegen → TaskSourcePoller → Scheduler → TaskExecutor → SystemBlueprint
```

## 方块交互协议

三 action 均由 WandscapeBlockOps 统一实现，BlockInteractOp 通过 `state.useWithoutItem(level, null, hit)` 驱动（null Player 全图广播音效）：

| action | 一级路径 | 二级回退 | 覆盖方块 |
|--------|---------|---------|---------|
| `toggle` | `state.useWithoutItem(null)` | 无 | 拉杆/门/活版门/栅栏门/音符盒/钟 |
| `activate` | `state.useWithoutItem(null)` | 红石脉冲（放→删红石块）→neighborChanged | 按钮→TNT/发射器/活塞/命令方块 |
| `open_gui` | `level.blockEvent(pos, block, 1, 0)` | Container 接口 | 箱子/木桶/熔炉/漏斗 |

## 依赖

- `core/` — 全部边界接口、World、ECS 组件、任务系统
- `shared/api/BuildingApi` — BuildingTaskSource 通过 WandscapeApis 调用
- `npc/internal/EntityComponentBridge` — WandscapeRitualOps / WandscapeMovementOps 查找 NPC
