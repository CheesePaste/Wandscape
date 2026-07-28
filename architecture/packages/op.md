# op/ — 原子操作系统

从 `core/` 独立。定义所有可执行原子操作及执行框架。纯 Java 21，零 MC 依赖。

## AtomicOp 类型（8 种变体）

TransformOp（place/break/convert）/ BlockInteractOp（toggle/activate/open_gui/gather/decompose/synthesize）/ EntityInteractOp / RitualOp / ResourceRequestOp / EmitEventOp / IfConditionOp / ParallelOp

## 执行框架

OpExecutor<AtomicOp>（函数式接口，返回 CompletableFuture<Void>）/ OpExecutorRegistry（按子类注册）/ DefaultOpExecutors（注册所有默认同步执行器）。异步 op（如仪式倒计时）返回未完成 future，同步返回 completedFuture。

## 数据流

```
TaskExecutionSystem (每tick)
  → World.opExecutorRegistry.get(op.class).execute(world, entityId, op)
  → 同步：completedFuture → stepIndex++
  → 异步：await pendingFuture → stepIndex++
```

## 依赖

- `core/ecs/World`
- `core/types/` (GridPos, ResourceId 等)
