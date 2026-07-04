# op/ — 原子操作系统

从 `core/` 独立。定义所有可执行原子操作及执行框架。纯 Java 21，零 MC 依赖。

## 入口

无独立入口。由 `core/ecs/World` 引用 `OpExecutorRegistry`，执行器注册在 `EngineBootstrap` 时完成。

## api/ — 原子操作定义

- **`AtomicOp.java`** — sealed interface，8 种变体：
  - `TransformOp` — place(放置方块) / break(破坏) / convert(转换类型)
  - `BlockInteractOp` — toggle(切换状态) / activate(右键激活) / open_gui(打开GUI) / gather(采集) / decompose(分解) / synthesize(合成)
  - `EntityInteractOp` — 实体交互
  - `RitualOp` — 仪式（传送、召唤）
  - `ResourceRequestOp` — 资源提取/存入
  - `EmitEventOp` — 发射事件
  - `IfConditionOp` — 条件分支
  - `ParallelOp` — 并行执行
- **`ConditionEvaluator.java`** — `IfConditionOp` 的条件求值器，支持运行时表达式求值（放在 api/ 因为与 AtomicOp 紧耦合）

## executor/ — 执行框架

- **`OpExecutor<T>`** — `@FunctionalInterface`：`CompletableFuture<Void> execute(World, long entityId, T op)`。同步返回 `completedFuture`，异步返回未完成 future（如仪式倒计时）
- **`OpExecutorRegistry`** — 按 `AtomicOp` 子类注册 Executor
- **`DefaultOpExecutors`** — 注册所有默认同步执行器
- **`ResourceShortageException`** — 资源不足时抛出的受检异常，触发任务释放与重新调度

## 数据流

```
task/scheduler/TaskExecutionSystem (每tick)
  → 遍历 NPC TaskPackage.currentSequence.steps[]
  → World.opExecutorRegistry.get(op.class).execute(world, entityId, op)
  → 同步：返回 completedFuture → stepIndex++
  → 异步：await pendingFuture → stepIndex++
  → 完成后继续下一个 step
```

## 依赖

- `core/ecs/World`
- `core/types/` (GridPos, ResourceId 等)
