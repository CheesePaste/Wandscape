# op/ — 原子操作系统（纯 Java）

`src/main/java/com/wsteam/wandscape/op/`

## 职责

定义 NPC 可执行的**原子操作**（Atom）及执行框架。一个任务步骤就是一个 AtomicOp；OpExecutor 负责执行并返回 CompletableFuture。纯 Java，零 MC 依赖，MC 实现在 `engine/boundary/`。

## AtomicOp（sealed interface，10 种）

| 操作 | 字段 | 说明 |
|---|---|---|
| `TransformOp` | target, from, to, consumeSource, drops, consumable, blockNbtBase64 | 放置/拆除/转换方块；便捷构造 place/remove/convert |
| `BlockInteractOp` | target, action, params, channelTicks | 交互方块（采集/合成/酿造/分解/toggle/activate/open_gui） |
| `EntityInteractOp` | entityId, effect, strength, duration | target=null |
| `RitualOp` | ritual, target, params | `channelTicks()` 硬编码：self_teleport/item_teleport/player_summon=600，warding=200，group_vigor=400，rain_call/clear_weather=1200，portal_gate=1800 |
| `AttackMonsterOp` | attackRange, releaseRange, circleId, color | 守卫攻击，target=null |
| `SelfDefenseOp` | radius, circleId, color | 自卫反击，target=null |
| `ResourceRequestOp` | List\<ResourceStack\> | 资源请求，空列表抛异常，target=null |
| `EmitEventOp` | eventName, templateParams | 发 CustomEvent |
| `IfConditionOp` | conditionName, params, skipCount, elseSkip | 条件分支 |
| `ParallelOp` | List\<AtomicOp\> | 位置无关元操作，全子 op 并发 |

`ConditionEvaluator.evaluate(Map, World, npcId)`：求值蓝图 `IfConditionOp` 里的条件。

## executor/

- `OpExecutor<T>`：`opType()` + `execute(op, world, npcId)` → CompletableFuture（同步 = completedFuture；异步 = 不完成，由 MC 层计时后补全）。
- `OpExecutorRegistry`：executors Map + conditions Map；register/get/registerCondition/getCondition。
- `DefaultOpExecutors.registerAll` 注册 7 个 executor：
  - **TransformExecutor**：消耗品校验 → setBlock + NBT。
  - **BlockInteractExecutor**：仅 toggle/activate/open_gui 同步执行（异步动作由 `engine/boundary/WandscapeBlockInteractExecutor` 替换）。
  - **EntityInteractExecutor**、**RitualExecutor**。
  - **ResourceRequestExecutor**：五阶段 all-or-nothing（校验/保留/发射/完成/失败回滚）。
  - **EmitEventExecutor**：模板解析 `{{}}` + advanceAfterPureOp。
  - **IfConditionExecutor**。
  - 3 个条件：`resource_below` / `inventory_has` / `inventory_full`。
- `ResourceShortageException`：携带 requested items，非致命，被 `TaskExecutionSystem` 转为 `AWAITING_RESOURCES`。抛出时机：TransformExecutor 消耗品不足；ResourceRequestExecutor 任意物品仓库不足/保留失败/背包满。

## 与其他模块关系

- 蓝图 DSL（`task/engine/dsl/BlueprintInterpreter`）把 `StepNode` 展开成 `AtomicOp` 序列。
- `TaskExecutionSystem` 每 tick 推进一个副作用 op 并等待其 future。
- `GuardAttackExecutor`/`SelfDefenseExecutor` 通过 `AttackMonsterOp`/`SelfDefenseOp` 注入守卫/自卫包。
