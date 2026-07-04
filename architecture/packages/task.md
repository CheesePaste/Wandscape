# task/ — 任务系统

整合原 `core/task/`（引擎）+ `core/system/`（调度）+ `task/`（网络层）+ `shared/ui/task/`（GUI）。

纯 Java 21，零 MC 依赖（scheduler/ 和 source/ 中的接口同）。MC 实现类在 `engine/source/`、`engine/bootstrap/`。

## 子包一览

| 子包 | 职责 | 原位置 |
|------|------|--------|
| `engine/dsl/` | 蓝图 DSL AST 定义、编译、解释 | core/task/ |
| `engine/pool/` | 任务池（GlobalPool / BuildingPool） | core/task/ |
| `runtime/` | 运行时状态（ExecutorState / NpcTaskPackage） | core/task/ |
| `scheduler/` | 调度系统（SchedulerSystem / TaskExecutionSystem） | core/system/ |
| `source/` | TaskSource 接口 + 纯 core 实现 | core/system/ |
| `client/` | 任务编辑器 GUI + 客户端状态 | shared/ui/task/ |
| `network/` | 任务编辑器网络包 + 处理器 | task/network/（原位置不变） |

## engine/dsl/ — 蓝图 DSL

蓝图 DSL 是 JSON 数据驱动的任务描述语言。编译链：`BlueprintDefinition (JSON AST)` → `TaskCompiler.compile()` → `CompiledBlueprint` → `BlueprintInterpreter.interpret(TaskRequest)` → `TaskSequence`。

- **`BlueprintDefinition.java`** — DSL AST 根 record：id + params + steps + displayName
- **`BlueprintInterpreter.java`** — 运行时解释器：表达式求值 → for_each/if/call 展开 → TaskSequence 生成
- **`ExprNode.java`** — sealed interface，21 种表达式 AST（Var/FieldAccess/算术/比较/MapGet/Size/Format）
- **`StepNode.java`** — sealed interface，12 种步骤类型（Place/Remove/Convert/BlockInteract/EntityInteract/Ritual/RequestResource/EmitEvent/ForEach/If/Call/Log）
- **`ParamType.java`** — sealed interface，6 种强类型参数（string/int/pos/list\<pos\>/list\<string\>/map\<string,string\>）
- **`Blueprint.java`** (interface) — 蓝图接口，定义编译契约
- **`BlueprintSteps.java`** — 蓝图步骤容器
- **`CompiledBlueprint.java`** — 编译后的不可变蓝图，可直接生成 TaskSequence
- **`BlueprintRegistry.java`** — 蓝图注册表 + TaskCompiler 实现（TaskRequest → CompiledBlueprint）
- **`TaskCompiler.java`** — TaskRequest → CompiledBlueprint 编译入口
- **`TriggerDeclaration.java`** — 触发器声明，定义任务触发的条件

## engine/pool/ — 任务池

- **`GlobalTaskPool.java`** — 中央任务池：TreeSet 排序(PENDING_ASSIGN 优先级 desc → createdAt asc → id asc)。创建/审批/分配/推进/完成/资源等待。`AWAITING_RESOURCES` 休眠队列 → `ResourceFulfilled` 事件唤醒。`clearAll()` 清空所有任务并取消事件订阅
- **`GlobalTask.java`** — 任务生命周期：PENDING_APPROVAL → PENDING_ASSIGN → IN_PROGRESS → COMPLETED/FAILED。字段：buildingId/isBuildingHead/createdAt/stepIndex
- **`BuildingTaskPool.java`** — 建筑 → 队列映射：只有 head task 进入全局池。enqueue/publish → onHeadCompleted/promote。纯数据结构，零 MC 依赖
- **`BuildingTaskQueue.java`** — 单建筑运行时队列：Deque\<WorkItem\> + headTaskId
- **`TaskRequest.java`** — 任务请求 record，包含 blueprintId + params + priority + anchor

## runtime/ — 运行时状态

- **`ExecutorState.java`** — NPC 执行器运行时状态枚举：IDLE / NAVIGATING / EXECUTING_OP / AWAITING_ASYNC / SUSPENDED
- **`NpcTaskPackage.java`** — NPC 自包含工作单元：source("global:42") + TaskSequence + stance(GridPos) + priority + startStepIndex
- **`NpcTaskQueue.java`** — NPC 队列：pending deque + currentPackage + suspensionStack(max 3)。方法：startPackage / enqueueNormal / enqueueUrgent / suspendCurrent / resumeLatest / releaseCurrent
- **`TaskSequence.java`** — 不可变 AtomicOp 列表 + label
- **`TaskState.java`** — 任务执行状态枚举
- **`ApprovalInfo.java`** — 审批信息 record：autoApproved / approvedByPlayer / approvedAt
- **`InterruptRecord.java`** — 任务中断记录，含原因和时间戳
- **`TaskFailureReason.java`** — sealed interface，失败原因变体（WandRequirementUnmet, ResourceUnavailable 等）

## scheduler/ — 调度系统

- **`SchedulerSystem.java`** — 每 2tick NPC → task 反向匹配：为每个空闲 NPC 找最佳任务。评分=proximity×0.5 + efficiency×0.3 + attributes×0.2。通过 EquipmentComponent 查询装备
- **`TaskExecutionSystem.java`** — 每 tick 驱动 NpcTaskQueue：检查 currentPackage → 姿态导航 → while 循环执行纯操作 → 异步等待 → 包完成/释放
- **`SystemBlueprintRegistry.java`** — 注册框架层面系统蓝图的注册表
- **`SystemBlueprintSystem.java`** — 每 tick 执行一个系统蓝图副作用 op

## source/ — TaskSource

- **`TaskSource.java`** — 接口：TaskRequest 生产者
- **`TaskSourcePoller.java`** — 按间隔轮询所有 TaskSource → TaskRequest 入 GlobalTaskPool
- **`EventDrivenTaskSource.java`** — 监听事件生成系统级 TaskRequest（如资源短缺自动创建采集任务）
- **`PlayerManualSource.java`** — 玩家手动提交的任务源（通过任务编辑器 GUI）
- **`WorkbenchSource.java`** — 监视工作台生产队列，发布 Workbench TaskRequest

MC 实现类（BuildingTaskSource、RoadTaskSource）在 `engine/source/`。

## client/ — 任务编辑器 GUI

- **`TaskEditorClientState.java`** — 客户端 GUI 状态持有者（单例）：蓝图列表/选中蓝图/草稿参数/优先级
- **`TaskEditorScreen.java`** — 继承 MedievalScreen（shared/ui/component/），含蓝图列表 + 参数编辑框 + 提交按钮。收发网络包

## network/ — 网络层

- **`TaskEditorOpenPacket.java`** — C→S，玩家打开任务编辑器
- **`BlueprintListResponsePacket.java`** — S→C，响应打开请求，发送蓝图列表
- **`TaskCreatePacket.java`** — C→S，创建新任务（选蓝图 + 设参数 + 优先级）
- **`TaskNetworkHandler.java`** — 服务端网络处理器

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
