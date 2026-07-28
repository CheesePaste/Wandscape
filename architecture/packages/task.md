# task/ — 任务系统

整合 `core/task/`（引擎）+ `core/system/`（调度）+ `task/`（网络层）+ `shared/ui/task/`（GUI）。

纯 Java 21，零 MC 依赖（scheduler/ 和 source/ 中的接口同）。MC 实现类在 `engine/source/`、`engine/bootstrap/`。

## 子包

| 子包 | 职责 |
|------|------|
| `engine/dsl/` | 蓝图 DSL AST 定义、编译、解释 |
| `engine/pool/` | 任务池（GlobalPool / BuildingPool） |
| `runtime/` | 运行时状态 |
| `scheduler/` | 调度系统 |
| `source/` | TaskSource 接口 + 纯 core 实现 |
| `client/` | 任务编辑器 GUI + 客户端状态 |
| `network/` | 网络包 |

## 蓝图 DSL

编译链：`BlueprintDefinition (JSON AST)` → `TaskCompiler.compile()` → `CompiledBlueprint` → `BlueprintInterpreter.interpret(TaskRequest)` → `TaskSequence`。

- ExprNode sealed interface：21 种表达式 AST
- StepNode sealed interface：12 种步骤类型
- ParamType sealed interface：6 种参数类型
- BlueprintRegistry + TaskCompiler：TaskRequest → CompiledBlueprint

## 任务池

GlobalTaskPool：TreeSet 排序(PENDING_ASSIGN 优先级 desc → createdAt asc → id asc)。AWAITING_RESOURCES 休眠队列 → ResourceFulfilled 事件唤醒。BuildingTaskPool：建筑→队列映射，仅 head task 进入全局池。

## 调度

SchedulerSystem 每 2tick NPC→task 反向匹配：评分=proximity×0.5 + efficiency×0.3 + attributes×0.2。TaskExecutionSystem 每 tick 驱动 NpcTaskQueue，通过 OpExecutor 执行 atomic op。

## 数据流

```
玩家通过任务编辑器
  → TaskCreatePacket → TaskNetworkHandler
  → PlayerManualSource.publish(TaskRequest)
  → GlobalTaskPool.addTask()
  ─┬→ SchedulerSystem (每2tick) → 分配任务给 NPC
   → TaskExecutionSystem (每tick) → NpcTaskQueue
   → OpExecutor → 边界实现 → MC 世界

建筑自动任务
  → BuildingTaskSource.poll() (20tick)
  → TaskRequest → GlobalTaskPool → ...
```

## 测试覆盖

core/task/ 部分有 10+ 单元测试，覆盖蓝图编译/解释/任务池/调度。
