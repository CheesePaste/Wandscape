# core/ — ECS 引擎

纯 Java 21，零 MC 依赖。ECS 世界 + 任务池 + 调度器 + 蓝图 DSL + 道路规划。

## 入口

`CoreBootstrap.bootstrap(config)` → 返回装配好的 `World` 实例。`BootStrapConfig` record 打包了所有边界实现 + 蓝图注册表 + TaskSource 列表。

## 关键类

### ecs/ — ECS 框架

- **World.java** — 中央容器：持有 ComponentStore Map + System 列表 + 边界服务引用。`createEntity()` / `addComponent()` / `query(A,B,C)` 交集查询 / `tick(delta)` 按序执行所有系统。`clearAllTasks()` 紧急恢复：清空任务池 + 建筑队列 + 重置所有NPC。异步门控通过 `startAsyncOp()` → `hasPendingAsyncOps()` 实现 tick 阻塞
- **System.java** — `@FunctionalInterface`：`void update(World, float delta)`
- **HashMapComponentStore\<T\>.java** — HashMap 存储 + 排序实体列表缓存（交集查询用）

### component/ — 10 个 ECS 组件

| 组件 | 关键字段 |
|------|---------|
| Position | GridPos |
| ManaPool | current/max/regenPerTick + regen()/consume()/add() |
| WandCarrier | mutable class：capabilities并集 + equippedWandIds列表 + equip(wandId,caps,eff,range)/unequip(wandId,knownWands)/recalculateFull()/EMPTY哨兵 |
| TaskExecutor | globalTaskId/currentSequence/stepIndex/params/stance/pendingFuture/ExecutorState + npcQueue(NpcTaskQueue) |
| NpcTaskQueue | pending deque + currentPackage + suspensionStack(max 3) + 包驱动方法(startPackage/enqueueNormal/enqueueUrgent/suspendCurrent/resumeLatest/releaseCurrent) |
| Inventory | 列表存储 + add/remove/count/hasEnough |
| NavigationState | mode(IDLE/PATHFINDING/TELEPORT_WAITING/TELEPORT_RITUAL) + target + CompletableFuture + 卡死追踪 |
| ColonyMember | colonyId(UUID) |
| ColonyMetadata | center/territoryRadius/prosperity/contains(pos) |
| SuspensionContext | 挂起包快照(pkg/stepIndex/suspendedAtTick) — NPC被紧急任务打断时保存上下文 |

### op/ — 原子操作

- **AtomicOp.java** — sealed interface，9 种变体：TransformOp(place/break/convert) / BlockInteractOp(toggle/activate/open_gui/gather/decompose/synthesize) / EntityInteractOp / RitualOp / ResourceRequestOp / EmitEventOp / IfConditionOp / WandEquipOp / WandReturnOp
- **OpExecutor\<T\>** — `CompletableFuture<Void> execute(World, long entityId, T op)`，同步返回 completedFuture，异步返回未完成 future
- **OpExecutorRegistry** — 按 AtomicOp 子类注册 Executor
- **DefaultOpExecutors** — 注册所有默认同步执行器 + 内置条件求值器

### task/ — 任务系统 + 蓝图 DSL

- **GlobalTaskPool** — 中央任务池：TreeSet 排序(PENDING_ASSIGN优先级 desc→createdAt asc→id asc)。创建/审批/分配/推进/完成/资源等待。AWAITING_RESOURCES休眠队列 → ResourceFulfilled事件唤醒。`clearAll()` 清空所有任务并取消事件订阅
- **GlobalTask** — 任务生命周期：PENDING_APPROVAL→PENDING_ASSIGN→IN_PROGRESS→COMPLETED/FAILED。字段：buildingId/isBuildingHead/createdAt/stepIndex
- **NpcTaskPackage** — NPC自包含工作单元：source("global:42"/"system:wand_equip") + TaskSequence + stance(GridPos) + priority + startStepIndex
- **BuildingTaskPool** — 建筑→队列映射：只有head task进入全局池。enqueue/publish→onHeadCompleted/promote。纯数据结构，零MC依赖
- **BuildingTaskQueue** — 单建筑运行时队列：Deque\<WorkItem\> + headTaskId
- **WandLifecycle** — 法杖状态机：IN_WAREHOUSE→RESERVED→IN_TRANSIT_TO_NPC→EQUIPPED→IN_TRANSIT_TO_WAREHOUSE→IN_WAREHOUSE。per-colony追踪，纯逻辑
- **WandLifecycleState** — 法杖5态枚举
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
| SchedulerSystem | 每2tick | NPC→task反向匹配：NPC优先，为每个空闲NPC找最佳任务。评分=proximity×0.5 + efficiency×0.3 + behaviourLevel×0.2。法杖预留通过WandLifecycle |
| TaskExecutionSystem | 每tick | 驱动NpcTaskQueue：检查currentPackage→姿态导航→while循环执行纯操作→异步等待→包完成/释放 |

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

BlockOps(7方法) / EntityOps / RitualOps(beginRitual返回CompletableFuture) / MovementOps(navigateTo返回CompletableFuture) / ColonyResourceAccess(6方法) / WandProvider(findWand——core层接口，engine层实现) / EventBus(emit/subscribe/unsubscribe)

### event/ — 事件定义

| 事件 | 用途 | 订阅者数 |
|------|------|---------|
| `TaskCompleted` | 全局任务完成 | 1（扩展预留） |
| `CustomEvent` | 蓝图 emit 的自定义事件 | 4 |
| `NarrativeEventTriggered` | 叙事事件（游客行为） | 2 |

事件通过 `SimpleEventBus` 在 tick 末批量派发。1:N 场景使用事件，1:1 场景使用 `core/boundary/` 接口注入。

### WandRequirementDeriver

纯函数，扫描 TaskSequence 的所有 AtomicOp 推导 `Map<BehaviourTag, BehaviourLevel>`：
- TransformOp → BUILDING:1
- BlockInteractOp(gather) → GATHERING:1
- BlockInteractOp(decompose/synthesize/craft_wand) → CRAFTING:1
- RitualOp → RITUAL:N（按仪式类型：warding→1, portal_gate→3）
- ResourceRequestOp/EmitEvent/IfCondition/WandEquip/WandReturn → 空（不需法杖）

`GlobalTaskPool.addTask()` 自动调用 derive 填入 task.requirements。WandProvider 为 `@FunctionalInterface`，引擎层实现查 ColonyItemBank 匹配。

引擎层实现在 `engine/boundary/` 和 `engine/system/`。

### 测试覆盖

26 个测试文件，重点：road(6) / BlueprintInterpreter / shared类型 / ElementMapping / BuildingConfig
