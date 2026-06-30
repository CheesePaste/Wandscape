# Event 系统与 Task 系统参考

> **写代码前必读**。避免创建无效事件、重复机制、或破坏自动恢复链。

## Event 的核心目的：1:N 广播

Event 存在只有一个理由：**一个事件触发多个响应**。

| 场景 | 方案 | 例子 |
|------|------|------|
| 1 个发射者 → **多个**订阅者 | ✅ Event | `CustomEvent("build_complete")` → `BuildCompleteListener`（建筑验证）+ `RoadEventListener`（道路规划） |
| 1 个发射者 → **1 个**订阅者 | ❌ 别用 Event | `TaskAwaitingResources` 已删，改为 `GlobalTaskPool` 直接调 `ResourceShortageHandler` |

**规则**：新增事件前，数清楚会有几个订阅者。只有 1 个就别建事件，用接口注入直连——依赖反转（`core/boundary/` 定义接口，`engine/` 实现注入）已经解决了模块解耦问题，不需要事件多绕一圈。

## 核心边界

| 层 | 职责 | 禁止 |
|----|------|------|
| **Event** | 1:N 广播"发生了什么" | 1:1 场景用事件；在 handler 里执行业务逻辑 |
| **TaskSource** | Event → TaskRequest 翻译器 | 不执行业务逻辑，不持有状态 |
| **GlobalTaskPool** | 任务生命周期、状态转移、调度队列、资源短缺恢复 | 不创建新任务（trigger 系统除外） |
| **FailureAnalyzer** | 分析 FAILED 任务 → 创建修复任务 | 不处理 AWAITING_RESOURCES |
| **OpExecutor** | 执行原子操作 | 不处理资源短缺（抛异常交给 TaskPool） |

## 现有事件清单

| 事件 | 发射者 | 订阅者 | 订阅者数 | 判断 |
|------|--------|--------|---------|------|
| `TaskCompleted` | `GlobalTaskPool.completeTask()` | `EventDrivenTaskSource`（stub） | 1 | 扩展点预留 |
| `CustomEvent` | 蓝图 `EmitEventOp` | 4 个订阅者 | **4** | ✅ 典型 1:N |
| `NarrativeEventTriggered` | `TouristMoveGoal` / `TouristSpawnSystem` | `StatsSystem` / `AchievementSystem` | **2** | ✅ 典型 1:N |

注意：`TaskCompleted` 目前也是 1:1（stub），但它作为蓝图链式反应的扩展点是合理的预留。未来 `TriggerDeclaration` 可能订阅它。

## 已删除的事件（禁止重新创建）

| 事件 | 删除原因 |
|------|---------|
| `ResourceLow` | 有订阅无发射 |
| `MobNearby` | 有订阅无发射 |
| `TaskAwaitingResources` | 1:1 事件。改为 `ResourceShortageHandler` 直接注入 `GlobalTaskPool`。 |
| `ResourceFulfilled` | 1:1 事件。改为 `ResourceAddedListener` 回调直连 `WarehouseManager → GlobalTaskPool`。 |

## 任务生命周期

```
PENDING_APPROVAL → PENDING_ASSIGN → IN_PROGRESS → COMPLETED
                         ↑                │
                         │    AWAITING_RESOURCES ←── ResourceShortageException
                         │          │
                         │    ResourceFulfilled（资源到位自动唤醒）
                         │
                       FAILED（终端：WandRequirementUnmet / ColonyEvaluationTooLow）
```

## 自动恢复链（资源不足 → 合成）

```
① NPC 执行 ResourceRequestOp → 仓库不足 → ResourceShortageException
② TaskExecutionSystem → taskPool.markAwaitingResources → 任务状态 AWAITING_RESOURCES
③ GlobalTaskPool 直接调用 ResourceShortageHandler.handle()
   └── 有合成配方 → 创建 production:synthesize 任务
        └── 合成任务执行时又缺元素 → 回到 ①（递归）
④ 合成完成 → 仓库入库 → WarehouseManager → ResourceAddedListener 回调
⑤ GlobalTaskPool.onResourceAdded() → 唤醒所有等待该资源的任务 → PENDING_ASSIGN
```

**设计决策**：步骤 ③ 和 ④⑤ 都不通过事件。`ResourceShortageHandler` 和 `ResourceAddedListener` 都是 `core/boundary/` 下的 `@FunctionalInterface`，engine 层注入实现。1:1 场景不需要事件解耦——依赖反转接口已经解决了模块解耦问题。

## 如何新增事件

**第一步：数订阅者**。预计会有几个订阅者？

- 1 个 → 别建事件，用接口注入直连
- 2+ 个 → 考虑事件，但也要看这 2+ 个订阅者是否在架构上确实应该分开响应

**第二步：回答三个问题**：

1. **描述的是事实还是动作？** 事实（"资源到位了"）→ 可以考虑事件。动作（"请补货"）→ 直接调方法。
2. **谁发射？谁订阅？** 两边都必须明确存在且有意义。
3. **和现有机制有没有重叠？** 不要创建和已有事件/接口功能重复的事件。

**禁止**：
- 创建事件但没有人 emit
- 创建事件但只有 1 个 subscribe
- 在 Event handler 里直接执行业务逻辑（应该创建 Task，让 Task 系统执行）

## 如何新增直连接口

当确定是 1:1 场景时，用依赖反转替代事件：

1. 在 `core/boundary/` 定义 `@FunctionalInterface` 接口
2. 在 `core/` 的类中添加 `@Nullable` 字段 + setter
3. 在 `EngineBootstrap` 中注入 engine 层实现

示例：`ResourceShortageHandler` — `core/boundary/` 接口 → `GlobalTaskPool` 持有 → `EngineBootstrap.createShortageHandler()` 注入。

## 测试

`EventDrivenTaskSourceTest` — 核心测试。`MockBoundary` 提供可控仓库 stub。新增事件/TaskSource 必须在此加测试。

## 相关文件索引

| 文件 | 角色 |
|------|------|
| `core/event/*.java` | 所有事件定义 |
| `core/boundary/EventBus.java` | 事件总线接口 |
| `core/event/SimpleEventBus.java` | 事件总线实现（tick-batch 模式） |
| `core/system/EventDrivenTaskSource.java` | 事件→任务翻译器（目前仅 TaskCompleted stub + 蓝图注册） |
| `core/system/TaskSource.java` | 轮询型 TaskSource 接口 |
| `core/boundary/ResourceShortageHandler.java` | 资源短缺处理器接口（@FunctionalInterface，直连模式） |
| `core/task/GlobalTaskPool.java` | 任务池：生命周期 + 状态转移 + 资源短缺恢复 |
| `core/task/TaskState.java` | 任务状态枚举 |
| `core/system/SchedulerSystem.java` | 任务调度（NPC 匹配 + 分配） |
| `core/system/TaskExecutionSystem.java` | 任务执行（驱动 NPC 执行 Op 序列） |
| `engine/system/FailureAnalyzerSystem.java` | 失败分析（WandRequirementUnmet → craft_wand） |
| `engine/bootstrap/EngineBootstrap.java` | 组装点：所有 Handler/TaskSource 在此注入 |
| `core/task/TriggerDeclaration.java` | 蓝图级事件→任务映射（数据驱动，1:N 场景） |
