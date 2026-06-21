# 00 — 核心引擎 (`core/`)

纯 Java 21，零 MC 依赖。ECS 世界 + 任务池 + 调度器 + 原子操作执行器。

## 根包 (4 文件)

| 文件 | 作用 |
|------|------|
| `CoreBootstrap.java` | **引擎入口**：创建 World、注入边界实现、注册所有 ComponentStore / System / OpExecutor / Blueprint。提供 `createNpc()` / `createColony()` 工厂方法 |
| `CoreBootstrapConfig.java` | 引导配置 record：打包 4 个边界接口 + TaskSource 列表 + BlueprintRegistry + SystemBlueprintRegistry |
| `TemplateResolver.java` | `{{variable}}` 模板解析器，用于 EmitEventOp 和 TriggerDeclaration 的参数替换 |
| `Log.java` | 引擎日志工具（`[LEVEL] tag \| msg` 格式） |

## boundary/ — 适配层边界接口 (6 文件)

这 6 个接口由 `engine/` 包实现。

| 接口 | 作用 | MC 实现 |
|------|------|---------|
| `BlockOps.java` | 方块操作：setBlock / getBlockState / isAir / toggle / activate / openGui | WandscapeBlockOps |
| `EntityOps.java` | 实体操作：applyEffect / getPosition | WandscapeEntityOps (stub) |
| `RitualOps.java` | 仪式执行，V2.5 返回 `CompletableFuture<Void>`。channelTicks 和 baseManaCost 由 RitualOp 硬编码（非外部传入） | WandscapeRitualOps |
| `MovementOps.java` | NPC 移动：`navigateTo(npcId, x, y, z)` → CompletableFuture / `cancelNavigation(npcId)` | WandscapeMovementOps (纯传送) |
| `ColonyResourceAccess.java` | 仓库资源 CRUD：hasEnough / reserve / commit / release | stub（阶段 3） |
| `MovementOps.java` | 移动操作：navigateTo → CompletableFuture / cancelNavigation | WandscapeMovementOps（无状态适配器，写入 NavigationState） |
| `EventBus.java` | 领域事件总线：emit / subscribe / unsubscribe，同 tick 内事件批处理 | SimpleEventBus（纯内存，无需 MC 适配） |

## ecs/ — ECS 框架 (4 文件)

| 文件 | 作用 |
|------|------|
| `World.java` | **ECS 核心**：持有所有 ComponentStore + System 列表 + 实体 ID 生成器。提供 `createEntity()` / `addComponent()` / `get()` / `query(A, B, C)` 交集查询 / `tick(delta)` / 异步门控 `startAsyncOp()` → `hasPendingAsyncOps()` |
| `System.java` | `@FunctionalInterface`：`void update(World world, float delta)`，按注册顺序执行 |
| `ComponentStore<T>.java` | 组件存储接口：add / remove / get / has / entities()（排序列表用于交集查询） |
| `HashMapComponentStore<T>.java` | 基于 HashMap 的 ComponentStore 实现，写操作时使实体列表缓存失效 |

## component/ — ECS 组件 (8 文件)

| 组件 | 作用 |
|------|------|
| `Position.java` | 实体世界坐标 (GridPos) |
| `ManaPool.java` | NPC/建筑魔力池：current / max / regenPerTick，方法 regen() / consume() / add() |
| `WandCarrier.java` | NPC 装备法杖能力并集：`level(tag)` / `satisfies(requirements)` / `EMPTY` 哨兵 |
| `NavigationState.java` | NPC 移动状态：mode (IDLE/PATHFINDING/TELEPORT_WAITING/TELEPORT_RITUAL) / target GridPos / CompletableFuture / 卡死/超时追踪字段。NavigationSystem 的唯一数据源 |
| `TaskExecutor.java` | NPC 任务执行状态：私有优先队列 / 当前 GlobalTask ID / stepIndex / taskParams / stance / `pendingFuture` / ExecutorState |
| `Inventory.java` | NPC 背包：列表存储 + 容量限制，add / remove / count / hasEnough |
| `ColonyMember.java` | 标记 NPC 属于哪个殖民地 (UUID) |
| `ColonyMetadata.java` | 殖民地元数据：center(GridPos) / territoryRadius / prosperity / `contains(pos)` |

## op/ — 原子操作 (5 文件)

| 文件 | 作用 |
|------|------|
| `AtomicOp.java` | **sealed 接口 + 7 种变体**：TransformOp(place/break/convert) / BlockInteractOp(toggle/activate/open_gui) / EntityInteractOp(apply effect) / RitualOp(ritual+target, channelTicks/baseManaCost硬编码) / ResourceRequestOp / EmitEventOp / IfConditionOp |
| `OpExecutor<T>.java` | 执行器接口：`CompletableFuture<Void> execute(World, long entityId, T op)` — 同步返回 completedFuture，异步返回未完成 future |
| `OpExecutorRegistry.java` | 注册表：AtomicOp 子类 → OpExecutor + 条件名 → ConditionEvaluator |
| `DefaultOpExecutors.java` | 注册所有默认同步执行器 + 内置条件求值器 (`resource_below` / `inventory_has` / `inventory_full`) |
| `ConditionEvaluator.java` | `@FunctionalInterface`：条件求值器，按名注册到 OpExecutorRegistry |

## task/ — 任务系统 (19 文件, 含 5 个 Blueprint DSL 类型)

| 文件 | 作用 |
|------|------|
| `TaskState.java` | 枚举：PENDING_APPROVAL → PENDING_ASSIGN → IN_PROGRESS → AWAITING_RESOURCES / INTERRUPTED / COMPLETED |
| `ExecutorState.java` | 枚举：IDLE / ACTIVE / WAITING（NPC 本地状态，区别于 GlobalTask 生命周期） |
| `TaskRequest.java` | record：blueprintId + params(Map\<String, JsonElement\>) + priority，TaskSource → pool 的请求 |
| `TaskSequence.java` | record：不可变 AtomicOp 列表 + label |
| `BlueprintSteps.java` | `@FunctionalInterface`：`TaskSequence generate(Map<String, JsonElement> params)` |
| `TriggerDeclaration.java` | record：事件触发声明 — "事件 X 匹配 → 用 template 参数编译蓝图 Y 为新任务" |
| `Blueprint.java` | record：id + BlueprintSteps 生成器 + TriggerDeclaration 列表 |
| `BlueprintRegistry.java` | 蓝图注册表 + `TaskCompiler` 实现：TaskRequest → CompiledBlueprint |
| `CompiledBlueprint.java` | record：编译产物 = TaskSequence + TriggerDeclaration 列表 |
| `TaskCompiler.java` | `@FunctionalInterface`：TaskRequest → CompiledBlueprint |
| `GlobalTask.java` | 全局任务：lifecycle 状态 / 分配 NPC / stepIndex / TriggerDeclaration / EventBus 订阅 / 中断记录 / 审批信息。`taskParams` 为 `Map<String, JsonElement>` |
| `GlobalTaskPool.java` | **中央任务池**：创建 / 审批 / 分配 / 推进 / 完成 / 资源等待 / 事件→任务翻译 / 去重 |
| `InterruptRecord.java` | record：npcId + timestamp + interruption stepIndex |
| `ApprovalInfo.java` | record：大任务审批元数据 |
| **Blueprint DSL 类型 (5 新增)** | |
| `BlueprintDefinition.java` | record：DSL AST 根对象（id + params + steps + displayName + description） |
| `BlueprintInterpreter.java` | 运行时解释器：解析表达式 AST → 展开 for_each/if/call → 生成 TaskSequence。含递归检测、变量遮蔽检测、隐式类型转换 |
| `ExprNode.java` | sealed interface：21 种表达式 AST 节点（6 字面量 + Var + FieldAccess + 算术 + 比较 + MapGet + Size + Format + KeyOf） |
| `StepNode.java` | sealed interface：12 种 DSL 步骤类型（Place/Remove/Convert/BlockInteract/EntityInteract/Ritual/RequestResource/EmitEvent + ForEach/If/Call/Log） |
| `ParamType.java` | sealed interface：6 种强类型参数声明（string/int/pos/list\<pos\>/list\<string\>/map\<string,string\>），含隐式转换规则 |

## system/ — ECS 系统 + TaskSource (11 文件)

按 World.tick() 执行顺序排列：

| 文件 | 作用 |
|------|------|
| `ManaRegenSystem.java` | ① 每 tick 恢复所有 ManaPool 实体的魔力 |
| `TaskSourcePoller.java` | ② 按间隔轮询所有 TaskSource，将 TaskRequest 送入 GlobalTaskPool |
| `SchedulerSystem.java` | ③ 每 2 tick 为可分配任务匹配最佳空闲 NPC（评分 = range×0.5 + efficiency×0.3 + behaviourLevel×0.2） |
| `TaskExecutionSystem.java` | ④ 每 tick 驱动 NPC 执行 AtomicOp：检查 pendingFuture → stance 计算 → mana 检查 → dispatch → 异步等待 / 同步推进 |
| `NavigationSystem.java` | ⑤ (engine/system/) NPC 移动总控：≤32 寻路（moveTo + 卡死检测 + 超时），>32 或失败 → 向私有队列推入 RitualOp(SELF_TELEPORT)，由 TaskExecutionSystem 统一执行 |
| `SystemBlueprintSystem.java` | ⑥ 每 tick 驱动系统蓝图（非全局任务池的基础设施任务），批量纯 Op，一个副作用 Op/tick |
| `TaskSource.java` | 接口：`pollIntervalTicks()` + `poll(World, GlobalTaskPool)` |
| `BuildingTaskSource.java` | 在 `engine/source/` — 轮询建筑 BE 队列（每 20 tick）。params 为 JsonElement，通过 EnqueueHelper 构造 |
| `WarehouseSource.java` | V1 stub：监视仓库资源低于阈值时 emit ResourceLow 事件 |
| `WorkbenchSource.java` | V1 stub：监视工作站生产队列 |
| `PlayerManualSource.java` | 玩家手动发布任务（poll 为空，外部 push） |
| `EventDrivenTaskSource.java` | 订阅 ResourceLow / TaskAwaitingResources / MobNearby / TaskCompleted → 翻译为 gather/defense 任务 |
| `SystemBlueprintRegistry.java` | 系统蓝图注册表：永久事件订阅 → 事件→任务规则 + 去重 |

## event/ — 领域事件 (7 文件)

| 事件 | 触发时机 |
|------|---------|
| `CustomEvent.java` | 蓝图 EmitEventOp 发出，携带 name + 字符串 params |
| `ResourceLow.java` | 殖民地资源低于阈值 |
| `ResourceFulfilled.java` | 资源补足 |
| `TaskCompleted.java` | 全局任务完成 |
| `TaskAwaitingResources.java` | 全局任务进入资源等待 |
| `MobNearby.java` | 殖民地附近检测到敌对生物 |
| `SimpleEventBus.java` | EventBus 接口的内存实现：tick 内入队 → tick 末批处理 → 延迟取消订阅 |

## types/ — 基础值类型 (10 文件)

纯 record/enum，零内部依赖。

| 文件 | 作用 |
|------|------|
| `GridPos.java` | 方块坐标 (x,y,z)，提供 manhattanTo / distSq / add |
| `BlockType.java` | 方块类型标识符 (如 `minecraft:stone`)，含常用常量 |
| `ResourceId.java` | 资源标识符 (如 `stone_bricks`) |
| `ResourceStack.java` | ResourceId + 数量 |
| `RitualId.java` | 仪式标识符 (如 `self_teleport`) |
| `EntityId.java` | 实体 ID long 包装，含 NONE 哨兵 |
| `EffectId.java` | 效果标识符 (如 `damage`) |
| `InteractAction.java` | 方块交互动作 (toggle / activate / open_gui) |
| `BehaviourTag.java` | 枚举：BUILDING / FARMING / MINING / LOGGING / CRAFTING / GATHERING / RITUAL / ENTITY_INTERACTION |
| `BehaviourLevel.java` | 行为等级 int 包装 (1-5)，含边界校验 |

## demo/ (1 文件)

| 文件 | 作用 |
|------|------|
| `MockBoundary.java` | 全部 4 个边界接口的 headless mock，用于测试和演示 |

## 测试

```
src/test/java/com/wsteam/wandscape/core/
├── ecs/          WorldEcsTest (5)
├── task/          GlobalTaskPoolTest, BlueprintRegistryTest, BlueprintInterpreterTest (26), ...
├── op/            DefaultOpExecutorsTest, ...
├── system/        SchedulerSystemTest, TaskExecutionSystemTest, ...
├── event/         SimpleEventBusTest, ...
├── boundary/      AsyncTickGatingTest (17)
└── ...
```

`./gradlew test` 运行全部。纯 JUnit 5，零 MC 依赖。
