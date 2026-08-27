# task/ — 任务系统（纯 Java）

`src/main/java/com/wsteam/wandscape/task/`

## 职责

任务分发与执行引擎：**蓝图 DSL**（JSON → 可执行步骤）→ **任务池** → **调度器**（评分分配给 NPC）→ **执行器**（驱动 AtomicOp）。纯 Java，零 MC 依赖。

## 蓝图 DSL（engine/dsl/）

- **AST**：
  - `BlueprintDefinition(id, params:Map<String,ParamType>, steps:List<StepNode>, displayName, description)`
  - `StepNode`（sealed，12 种）：PlaceStep/RemoveStep/ConvertStep/BlockInteractStep/EntityInteractStep/RitualStep/RequestResourceStep(含 ResourceEntry，必填 items 或 dynamicItems)/EmitEventStep/ForEachStep/IfStep(elseInvert)/CallStep/ParallelStep/LogStep
  - `ExprNode`（sealed）：Literal/Var/FieldAccess/算术/比较/MapGet/Size/Format/KeyOf/MapItems
  - `ParamType`（sealed）：StringType/IntType/PosType/ListPosType/ListStringType/MapStringStringType
- **BlueprintInterpreter**：`interpret(def, params)` 校验必填参数 → 建 context → `expandSteps` → 产出 `TaskSequence`；`expandIf` 输出 then + IfConditionOp + else；`expandCall` 递归检测；`evaluate` 自底向上求值；隐式转换 int→string、pos→"x,y,z"；`parseEntityId` UUID→long→FNV-1a hash 兜底。解释异常 → task FAILED。
- **TaskCompiler**：`compile(TaskRequest, World)` → `CompiledBlueprint`。
- **BlueprintRegistry**（实现 TaskCompiler）：查表 → `steps.generate(params)` → `new CompiledBlueprint(sequence, triggers)`。
- `Blueprint(id, steps, triggers, definition)`；`TriggerDeclaration(eventName, paramFilter, sourceBlueprintId, priority, paramMapping, dedupKey)`；`TemplateResolver` 解析 `{{key}}`，未匹配保留原文。
- `BlueprintConfigLoader`（engine 侧）：解析 `data/wandscape/blueprints/*.json`，注册 `blueprints` 类别。

## 任务池（engine/pool/）

- `TaskRequest(blueprintId, params, priority, colonyId)` record——**colonyId 必填**（无殖民地上下文显式传 null = 无主任务）：任务属于哪个殖民地，就由哪个殖民地的 NPC 执行、从其仓库供料。`GlobalTaskPool.addTaskWithId` 是 `params["colony_id"]` 的**唯一写入点**（colonyId 非空 → 归一化为规范化 UUID 字符串；null → 保留 params 里已透传的 colony_id，对建筑/生产/道路/地形型蓝图无殖民地打告警）。
- `GlobalTaskPool`：可分配集 TreeSet 排序（priority desc → createdAt asc → id asc）；`addTask` 审批规则：`!autoApprove && priority>=50` → PENDING_APPROVAL；`assignLight` 订阅 triggers；`completeTask` 退订 + emit TaskCompleted；`markAwaitingResources` → AWAITING_RESOURCES + 调 ResourceShortageHandler；`onResourceAdded` 唤醒等待任务；`releaseTaskForReassign` 保留 stepIndex。
- `GlobalTask`：id/sequence/priority/blueprintId/buildingId/isBuildingHead/triggers/taskParams/state/stepIndex/assignedNpcId/awaitingResource/interruptHistory/approval。
- `BuildingTaskPool`：每建筑一个 `BuildingTaskQueue`，仅 head 入全局池，head 完成自动晋升下一 WorkItem；`enqueue`/`onHeadCompleted` 透传建筑殖民地到 `TaskRequest.colonyId`。
- `BuildingTaskQueue`：pending Deque + headTaskId。

## 调度器（scheduler/）

- **SchedulerSystem**：心跳 `HEARTBEAT_INTERVAL=2` tick；找空闲 NPC（Position+TaskExecutor+EquipmentComponent+Inventory+ColonyMember，IDLE 且队列空且无全局任务）；按 colony 分组；**跳过未注册殖民地组**（`EntityOps.isColonyRegistered`——占位全零/殖民地删除后遗留的陈旧 colonyId 都不是真实殖民地，其 NPC 不是任何小镇的工人，绝不派活）；跳过已占用 target 的任务；任务带 `colony_id`（建筑/祭坛/道路等）只派给同殖民地 NPC，无主任务可派任意真实殖民地 NPC。**评分公式**：
  ```
  proximity = 10 / (10 + distance)
  workEff   = min(WORK_SPEED, 4f)
  score     = proximity*0.6 + (workEff-1)*0.4
  ```
  分配时 `NpcTaskPackage.resumeFrom("global:"+id, ...)` + `assignLight`。
- **TaskExecutionSystem**：`NAV_RANGE_SQ=25.0`；流程：空闲检测 → future 等待/推进 → 站位导航（超出 NAV_RANGE_SQ）→ op 循环。TransformOp 目标已相同则跳过；ParallelOp 全子 op 并发 `allOf`；纯 op（EmitEvent/IfCondition）批量，副作用 op 每 tick 一个；future 异常时 ResourceShortageException → markAwaitingResources + releaseGlobalTask；`releaseToGlobalPool` 会 returnAndReset 把已领资源退回仓库并重置 stepIndex 到首个 ResourceRequestOp；`computeTaskStance` 从 target 包围盒计算站位 `(minX-2, maxY+1, (minZ+maxZ)/2)`。守卫顺序：0 跟随/休息释放全局任务（保留自防御）→ 0.5 冻结殖民地冻结 → 0.6 幽灵 NPC（`isNpcAlive` false → releaseForPhantom）→ **0.7 未注册殖民地 NPC**（`isColonyRegistered` false 且持有全局任务 → 复用 releaseBoundGlobalTask 释放、保留自防御）。
- **SystemBlueprintSystem**：逐 system blueprint 每 tick 重编译，局部 stepIndex 跟踪，纯 op 连续执行/副作用每 tick 一个，npcId=0，WAITING 不支持。
- **SystemBlueprintRegistry**：system blueprint 注册表；`subscribePermanentTriggers` 一次性订阅永不退订；onSystemTrigger filter→resolve→mapping→dedup→addTask。

## 运行时状态（runtime/）

- `ExecutorState`：IDLE/ACTIVE/WAITING。
- `TaskState`：PENDING_APPROVAL/PENDING_ASSIGN/IN_PROGRESS/AWAITING_RESOURCES/COMPLETED。
- `TaskSequence(steps, label)`：isComplete/get/of。
- `NpcTaskPackage(source, sequence, stance, priority, startStepIndex)`：of/resumeFrom/system 工厂。
- `ApprovalInfo(suggestedPosition, deadline, autoApproved)`；`InterruptRecord(npcId, timestamp, atStepIndex)`。

## 任务源（source/）

- `TaskSource`：`pollIntervalTicks()` + `poll(GlobalTaskPool, World)`。
- `TaskSourcePoller`（System）：每 tick 对 interval 取模轮询所有 source。
- `EventDrivenTaskSource`：`NO_POLL=Integer.MAX_VALUE`；订阅 TaskCompleted；`registerDefaultBlueprints` 注册 6 个 `gather:xxx`（ResourceRequestOp → TransformOp）。
- `PlayerManualSource`：poll 空实现；`publish(TaskRequest)` 直通 addTask（道路/建筑玩家手动操作经此）。
- `WorkbenchSource`：POLL_INTERVAL=30 的 V1 stub。

## JSON 蓝图样例

`data/wandscape/blueprints/build_place_structure.json` 顶层：`id / display_name / description / params / steps`。params 用字符串类型声明（`"list<pos>"`/`"map<string,string>"`/`"string"`/`"pos"`/`"list<string>"`）；steps 含 `type:"request_resource"`（items 用 `map_to_items`）、`type:"for_each"`、`type:"place"`（at 用 `{"+":[..]}`、block/nbt 用 `{"get":[..,"keyof"]}`）、`type:"emit_event"`。

完整 JSON 字段树见 [data/blueprints.md](../data/blueprints.md)。
