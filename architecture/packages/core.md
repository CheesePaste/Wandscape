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
| EquipmentComponent | 各槽位装备管理(equip/unequip/hasEquipment)。基础属性值 + 装备修饰器 → 有效属性值。内置默认 WAND 修饰器 + NPC 基础属性值 |
| TaskExecutor | globalTaskId/currentSequence/stepIndex/params/stance/pendingFuture/ExecutorState + npcQueue(NpcTaskQueue) |
| NpcTaskQueue | pending deque + currentPackage + suspensionStack(max 3) + 包驱动方法(startPackage/enqueueNormal/enqueueUrgent/suspendCurrent/resumeLatest/releaseCurrent) |
| Inventory | 列表存储 + add/remove/count/hasEnough |
| NavigationState | mode(IDLE/PATHFINDING/TELEPORT_WAITING/TELEPORT_RITUAL) + target + CompletableFuture + 卡死追踪 |
| ColonyMember | colonyId(UUID) |
| ColonyMetadata | center/territoryRadius/prosperity/contains(pos) |
| SuspensionContext | 挂起包快照(pkg/stepIndex/suspendedAtTick) — NPC被紧急任务打断时保存上下文 |

### op/ — 原子操作

- **AtomicOp.java** — sealed interface，8 种变体：TransformOp(place/break/convert) / BlockInteractOp(toggle/activate/open_gui/gather/decompose/synthesize) / EntityInteractOp / RitualOp / ResourceRequestOp / EmitEventOp / IfConditionOp / ParallelOp
- **OpExecutor\<T\>** — `CompletableFuture<Void> execute(World, long entityId, T op)`，同步返回 completedFuture，异步返回未完成 future
- **OpExecutorRegistry** — 按 AtomicOp 子类注册 Executor
- **DefaultOpExecutors** — 注册所有默认同步执行器 + 内置条件求值器
- **ConditionEvaluator** — IfConditionOp 的条件求值器，支持运行时表达式求值
- **ResourceShortageException** — 资源不足时抛出的受检异常，触发任务释放与重新调度

### task/ — 任务系统 + 蓝图 DSL

- **GlobalTaskPool** — 中央任务池：TreeSet 排序(PENDING_ASSIGN优先级 desc→createdAt asc→id asc)。创建/审批/分配/推进/完成/资源等待。AWAITING_RESOURCES休眠队列 → ResourceFulfilled事件唤醒。`clearAll()` 清空所有任务并取消事件订阅
- **GlobalTask** — 任务生命周期：PENDING_APPROVAL→PENDING_ASSIGN→IN_PROGRESS→COMPLETED/FAILED。字段：buildingId/isBuildingHead/createdAt/stepIndex
- **ApprovalInfo** — 审批信息 record：autoApproved/approvedByPlayer/approvedAt
- **NpcTaskPackage** — NPC自包含工作单元：source("global:42"/"system:manual") + TaskSequence + stance(GridPos) + priority + startStepIndex
- **BuildingTaskPool** — 建筑→队列映射：只有head task进入全局池。enqueue/publish→onHeadCompleted/promote。纯数据结构，零MC依赖
- **BuildingTaskQueue** — 单建筑运行时队列：Deque\<WorkItem\> + headTaskId
- **TaskSequence** — 不可变 AtomicOp 列表 + label
- **TaskState** — 任务执行状态枚举
- **ExecutorState** — NPC 执行器运行时状态(IDLE/NAVIGATING/EXECUTING_OP/AWAITING_ASYNC/SUSPENDED)
- **InterruptRecord** — 任务中断记录，含原因和时间戳
- **TriggerDeclaration** — 触发器声明，定义任务触发的条件
- **BlueprintRegistry** — 蓝图注册表 + TaskCompiler 实现（TaskRequest → CompiledBlueprint）
- **TaskCompiler** — TaskRequest → CompiledBlueprint 编译入口
- **Blueprint** (interface) — 蓝图接口，定义编译契约
- **BlueprintSteps** — 蓝图步骤容器
- **CompiledBlueprint** — 编译后的不可变蓝图，可直接生成 TaskSequence
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
| SystemBlueprintRegistry | 注册时 | 注册框架层面系统蓝图的注册表 |
| TaskSource | 接口 | TaskRequest 生产者接口，TaskSourcePoller 轮询 |
| TaskSourcePoller | 按间隔 | 轮询所有 TaskSource → TaskRequest 入 GlobalTaskPool |
| SchedulerSystem | 每2tick | NPC→task反向匹配：NPC优先，为每个空闲NPC找最佳任务。评分=proximity×0.5 + efficiency×0.3 + attributes×0.2。通过EquipmentComponent查询装备 |
| TaskExecutionSystem | 每tick | 驱动NpcTaskQueue：检查currentPackage→姿态导航→while循环执行纯操作→异步等待→包完成/释放 |
| EventDrivenTaskSource | 事件驱动 | 监听事件生成系统级 TaskRequest（如蓝图编译触发） |
| PlayerManualSource | 事件驱动 | 玩家手动提交的任务源（通过任务编辑器） |
| WorkbenchSource | 轮询 | 监视工作台生产队列，发布 Workbench TaskRequest |

### road/ — 道路系统（纯逻辑）

- **RoadNetwork** — 图网络：RoadNode(建筑/路口/孤儿) + RoadEdge(路段+状态+已放置方块记录)。查询：findNearestNode、findNearestWalkablePathPoint、findEdgeBetween、findNodeAtXZ
- **RoadPlanner** — 编排：MST计算→diff→分段→enqueueEdge。支持 incrementalAdd 增量添加新建筑
- **MstCalculator** — Prim算法，曼哈顿距离，建筑≥阈值触发
- **MstEdge** — 最小生成树边，按点列表索引引用（非 UUID）
- **PathGenerator** — L形路径(先X后Z)，3D Y插值+switchback斜坡，public 方法可被客户端复用做预览计算
- **PathPoint** — 三维路径点，替代 XZPoint 在需要 Y 坐标的路径场景
- **NetworkDiff** — 对比新旧MST→保留/废弃/新建
- **DecorationPlanner** — 路段完成后扫描→灯柱+长椅位置
- **DecorationPoint** — 装饰物放置点纯数据类
- **RoadNode** — 道路节点（路口/端点）
- **RoadRouter** — 道路路由器，负责寻路计算
- **RouteSegment** — 运输路线直线段（from→to）
- **XZPoint** — 二维 XZ 平面点
- **RoadBlobCache** — 建筑区块缓存，道路规划输入
- **RoadBuildingData** — 建筑极简快照，道路规划输入
- **RoadEdge** — 可变 state：status(PLANNED→BUILDING→COMPLETE)、placedBlocks(Set\<PathPoint\>，记录该边所有修改的方块位置)、segmentTaskIds、decorationTaskId

### 核心类型 (types/)

GridPos(x,y,z) / BlockType("mod:id") / ResourceId / ResourceStack / RitualId / EntityId /
EffectId / InteractAction(actionType,target) / 
EquipmentSlot(WAND, 预留 RING/AMULET/ROBE/BOOTS) / EquipmentPreset(id,slot,modifiers,color) /
AttributeType(RANGE,MANA_COST_MULTIPLIER,MAX_MANA,MANA_REGEN,MAX_HP,MOVE_SPEED) /
AttributeModifier(attribute,operation,value) / ModifierOperation(ADD/MULTIPLY)

### 边界接口 (boundary/)

BlockOps(7方法) / EntityOps / RitualOps(beginRitual返回CompletableFuture) / MovementOps(navigateTo返回CompletableFuture) / ColonyResourceAccess(6方法) / EventBus(emit/subscribe/unsubscribe) /
ResourceAddedListener(仓库添加资源通知) / ResourceShortageHandler(资源短缺时回调)

### event/ — 事件定义

| 事件 | 用途 | 订阅者数 |
|------|------|---------|
| `TaskCompleted` | 全局任务完成 | 1（扩展预留） |
| `CustomEvent` | 蓝图 emit 的自定义事件 | 4 |
| `NarrativeEventTriggered` | 叙事事件（游客行为） | 2 |

事件通过 `SimpleEventBus` 在 tick 末批量派发。1:N 场景使用事件，1:1 场景使用 `core/boundary/` 接口注入。

引擎层实现在 `engine/boundary/`、`engine/road/`、`engine/system/` 和 `engine/transport/`。

### 测试覆盖

26+ 个测试文件，重点：road(6) / BlueprintInterpreter / shared类型 / ElementMapping / BuildingConfig / MstCalculator / RoadNetwork / PathGenerator / RoadPlanner
