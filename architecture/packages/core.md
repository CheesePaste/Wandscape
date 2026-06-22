# core/ — ECS 引擎

纯 Java 21，零 MC 依赖。ECS 世界 + 任务池 + 调度器 + 蓝图 DSL + 道路规划。

## 入口

`CoreBootstrap.bootstrap(config)` → 返回装配好的 `World` 实例。`BootStrapConfig` record 打包了所有边界实现 + 蓝图注册表 + TaskSource 列表。

## 关键类

### ecs/ — ECS 框架

- **World.java** — 中央容器：持有 ComponentStore Map + System 列表 + 边界服务引用。`createEntity()` / `addComponent()` / `query(A,B,C)` 交集查询 / `tick(delta)` 按序执行所有系统。异步门控通过 `startAsyncOp()` → `hasPendingAsyncOps()` 实现 tick 阻塞
- **System.java** — `@FunctionalInterface`：`void update(World, float delta)`
- **HashMapComponentStore\<T\>.java** — HashMap 存储 + 排序实体列表缓存（交集查询用）

### component/ — 8 个 ECS 组件

| 组件 | 关键字段 |
|------|---------|
| Position | GridPos |
| ManaPool | current/max/regenPerTick + regen()/consume()/add() |
| WandCarrier | AbilitySet 并集 + level(tag)/satisfies(requirements)/EMPTY哨兵 |
| TaskExecutor | 私有优先队列/当前task/stepIndex/params/stance/pendingFuture/ExecutorState |
| Inventory | 列表存储 + add/remove/count/hasEnough |
| NavigationState | mode(IDLE/PATHFINDING/TELEPORT_WAITING/TELEPORT_RITUAL) + target + CompletableFuture + 卡死追踪 |
| ColonyMember | colonyId(UUID) |
| ColonyMetadata | center/territoryRadius/prosperity/contains(pos) |

### op/ — 原子操作

- **AtomicOp.java** — sealed interface，7 种变体：TransformOp(place/break/convert) / BlockInteractOp(toggle/activate/open_gui) / EntityInteractOp / RitualOp / ResourceRequestOp / EmitEventOp / IfConditionOp
- **OpExecutor\<T\>** — `CompletableFuture<Void> execute(World, long entityId, T op)`，同步返回 completedFuture，异步返回未完成 future
- **OpExecutorRegistry** — 按 AtomicOp 子类注册 Executor
- **DefaultOpExecutors** — 注册所有默认同步执行器 + 内置条件求值器

### task/ — 任务系统 + 蓝图 DSL

- **GlobalTaskPool** — 中央任务池：创建/审批/分配/推进/完成/资源等待/事件→任务翻译
- **GlobalTask** — 任务生命周期：PENDING_APPROVAL→PENDING_ASSIGN→IN_PROGRESS→COMPLETED
- **TaskSequence** — 不可变 AtomicOp 列表 + label
- **BlueprintRegistry** — 蓝图注册表 + TaskCompiler 实现（TaskRequest → CompiledBlueprint）
- **BlueprintDefinition** — DSL AST 根 record：id + params + steps + displayName
- **BlueprintInterpreter** — 运行时解释器：表达式求值 → for_each/if/call 展开 → TaskSequence 生成
- **ExprNode** — sealed interface，21 种表达式 AST（Var/FieldAccess/算术/比较/MapGet/Size/Format）
- **StepNode** — sealed interface，12 种步骤类型（Place/Remove/Convert/BlockInteract/EntityInteract/Ritual/RequestResource/EmitEvent/ForEach/If/Call/Log）
- **ParamType** — sealed interface，6 种强类型参数（string/int/pos/list\<pos\>/list\<string\>/map\<string,string\>）

### system/ — ECS 系统（按 tick 执行顺序）

| 系统 | 频率 | 职责 |
|------|------|------|
| ManaRegenSystem | 每tick | 恢复所有 ManaPool |
| SystemBlueprintSystem | 每tick | 系统蓝图（基础设施任务），一个副作用op/tick |
| TaskSourcePoller | 按间隔 | 轮询所有 TaskSource → TaskRequest 入 GlobalTaskPool |
| SchedulerSystem | 每2tick | 评分匹配：proximity×0.5 + efficiency×0.3 + behaviourLevel×0.2 |
| TaskExecutionSystem | 每tick | 驱动NPC执行AtomicOp：pendingFuture检查→stance→mana→dispatch→异步等待/同步推进 |

### road/ — 道路系统（纯逻辑）

- **RoadNetwork** — 图网络：RoadNode(建筑/路口/孤儿) + RoadEdge(路段+状态+已放置方块记录)。查询：findNearestNode、findNearestWalkablePathPoint、findEdgeBetween、findNodeAtXZ
- **RoadPlanner** — 编排：MST计算→diff→分段→enqueueEdge。支持 incrementalAdd 增量添加新建筑
- **MstCalculator** — Prim算法，曼哈顿距离，建筑≥阈值触发
- **PathGenerator** — L形路径(先X后Z)，3D Y插值+switchback斜坡，public 方法可被客户端复用做预览计算
- **NetworkDiff** — 对比新旧MST→保留/废弃/新建
- **DecorationPlanner** — 路段完成后扫描→灯柱+长椅位置
- **IntersectionDetector** — 交叉点检测→隐式路口节点
- **RoadEdge** — 可变 state：status(PLANNED→BUILDING→COMPLETE)、placedBlocks(Set\<PathPoint\>，记录该边所有修改的方块位置)、segmentTaskIds、decorationTaskId

### 核心类型 (types/)

GridPos(x,y,z) / BlockType("mod:id") / ResourceId / ResourceStack / RitualId / EntityId / BehaviourTag(8枚举) / BehaviourLevel(1-5)

### 边界接口 (boundary/)

BlockOps(7方法) / EntityOps / RitualOps(beginRitual返回CompletableFuture) / MovementOps(navigateTo返回CompletableFuture) / ColonyResourceAccess(6方法) / EventBus(emit/subscribe/unsubscribe)

引擎层实现在 `engine/boundary/`。

### 测试覆盖

26 个测试文件，重点：road(6) / BlueprintInterpreter / shared类型 / ElementMapping / BuildingConfig
