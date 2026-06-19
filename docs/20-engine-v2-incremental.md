# Magic Colony —— 增量设计 #1：蓝图系统与事件驱动任务编排

**状态**：grill-me 完成，27 个问题全部确认
**基线**：engine-arch.md v1（基础框架已实现）
**目标**：重设计 Blueprint → TaskSequence → 事件驱动的跨任务编排

---

## 1. 动机

- `EventDrivenTaskSource` 当前硬编码了所有事件→任务的映射（`onResourceLow → gather:*`、`onTaskAwaitingResources → gather:*` 等）
- 蓝图目前只是 `TaskSequence` 的生成器，不表达"完成/中途会发出什么事件"、"事件触发后应创建什么下游任务"
- 期望：蓝图声明自己的事件行为 + 链式任务规则，使任务编排高度可自定义、数据驱动

---

## 2. 核心决策

### 2.1 Blueprint 形态：声明式 record

Blueprint 不再是 `@FunctionalInterface`，而是一个携带完整声明的数据结构：

```java
record Blueprint(
    String id,
    BlueprintSteps steps,                          // 参数化生成器（保留函数式）
    List<TriggerDeclaration> triggers              // 本蓝图产生的下游任务规则
) {}
```

`BlueprintSteps` 维持函数式接口，仍通过 `params` 生成 `TaskSequence`。

编译产物：

```java
record CompiledBlueprint(
    TaskSequence sequence,
    List<TriggerDeclaration> triggers
) {}
```

### 2.2 EmitEventOp —— 发射事件成为原子操作

不新增 EmissionPoint 枚举。蓝图编写者在 `TaskSequence` 的任意位置插入 `EmitEventOp`，`TaskExecutionSystem` 执行到时自然 emit。复用现有执行框架，零新机制。

```java
record EmitEventOp(String eventName, Map<String, String> templateParams) implements AtomicOp {
    public int baseManaCost() { return 0; }
}
```

`EmitEventExecutor` 逻辑：
1. 解析 `templateParams` 中的 `{{变量}}` 占位符
2. 从执行上下文取值替换
3. `world.eventBus.emit(new CustomEvent(eventName, resolvedParams))`

**分类**：纯 Op（见 §2.8），可在 tick 内与其他纯 Op 连续执行。

**效应延迟**：emit 仅入队，tick 末尾 `EventBus.dispatch()` 才投递。同一 tick 内的 trigger 不会立即触发——效应延迟一个 tick。

#### 2.2.1 模板变量系统（V1 最小集）

`EmitEventOp.templateParams` 是**模板**，允许混合编译期常量与运行时占位符。

| 变量 | 含义 | 来源 |
|------|------|------|
| `{{taskId}}` | 当前全局任务 ID | `exec.globalTaskId` |
| `{{npcId}}` | 执行者 NPC 的 entity ID | 遍历时的 `npcId` |
| `{{task.params.<key>}}` | TaskRequest 的原始 params | `exec.taskParams`（TaskExecutor 加字段） |
| `{{pos.x}}` | NPC 当前位置 X | `world.get(npcId, Position.class)` |
| `{{pos.y}}` | NPC 当前位置 Y | 同上 |
| `{{pos.z}}` | NPC 当前位置 Z | 同上 |

**占位符语法**：
- `{{` 开头、`}}` 结尾的字符串整体替换
- 无匹配变量的占位符：保留原样（不抛异常），便于调试
- 无嵌套变量、无默认值语法（V2+）

#### 2.2.2 TaskExecutor 加 taskParams 字段

```java
class TaskExecutor {
    // ... 现有字段
    Map<String, String> taskParams;   // 新增 — 来自 TaskRequest 的原始 params
}
```

`GlobalTaskPool.assign()` 时填入。`EmitEventExecutor` 直读，无需反查 GlobalTask。

### 2.3 自定义事件：单一 CustomEvent 类型

所有蓝图发射的自定义事件共享同一个 `CustomEvent` 类型，通过 `name` 字段区分：

```java
record CustomEvent(String name, Map<String, String> params) {}
```

### 2.4 TriggerDeclaration：生产者声明

**Trigger 声明在生产者蓝图一侧**。A 蓝图声明"我 emit 某事件后 → source 某下游任务"。B 蓝图无需知道谁触发了它。

订阅生命周期：**assign 时 subscribe，complete 时 unsubscribe**。

```java
record TriggerDeclaration(
    String eventName,                    // 匹配 CustomEvent.name
    Map<String, String> paramFilter,     // V1：简单子集匹配（事件 params 包含所有 filter entry 即命中），null 则通配
    String sourceBlueprintId,            // 要创建的下游蓝图 ID，支持 {{event.<key>}} 模板
    int priority,                        // 新任务优先级
    Map<String, String> paramMapping,    // 事件 params → TaskRequest.params 的纯 key 重命名
    String dedupKey                      // 跨 tick 去重键（如 "resource"），null 则不去重
) {}
```

**`sourceBlueprintId` 模板**：
- 语法：`{{event.<key>}}` — 从触发事件的 params 取值
- 示例：`sourceBlueprintId = "gather:{{event.resource}}"` → 解析后 `"gather:stone_bricks"`
- 与 `EmitEventOp` 的 `{{pos.x}}`/`{{taskId}}` 模板**不同变量源**：此处源是事件 payload

**`paramFilter` 匹配**：子集匹配 — 事件 params 包含 filter 中所有 entry 即命中

**`dedupKey` 去重**：handler 在 `addTask()` 前，扫描池中所有非 COMPLETED 任务，若任一任务的 blueprintId（解析后）匹配 + 其 `taskParams[dedupKey]` 等于事件 `params[dedupKey]` → 跳过创建。与 EventBus 的 tick 内合并构成双层幂等。

#### 2.4.1 双层幂等：EventBus 合并 + Trigger 去重

**Layer 1 — EventBus 同 tick 合并**：`SimpleEventBus.dispatch()` 前，将同 `eventName + resource` 的事件合并为一条，取 max shortfall。避免 adaptor 连续 emit 导致的重复处理。

**Layer 2 — Trigger 跨 tick 去重**：handler 通过 `dedupKey` 检查池中是否已有相同的 in-flight 任务，避免重复创建。

### 2.5 TaskRequest 统一为 Map<String, String>

`GridPos location` 从 `TaskRequest` 移除，坐标约定为 params 中的 key（如 `x/y/z`），由蓝图自行解析：

```java
record TaskRequest(String blueprintId, Map<String, String> params, int priority) {}
```

### 2.6 EventBus 增加 unsubscribe + 延迟移除

```java
interface EventBus {
    <T> Subscription subscribe(Class<T> type, Consumer<T> handler);
    void unsubscribe(Subscription sub);  // 延迟到 dispatch() 末尾生效
    <T> void emit(T event);
}

record Subscription(Class<?> eventType, Consumer<?> handler) {}
```

**延迟移除（方案 A）**：`unsubscribe()` 不立即移除 handler，而是暂存到 `deferredRemovals` 队列。`dispatch()` 先投递所有排队事件，投递完毕后统一执行移除。下个 tick 起 handler 不再生效。

**动机**：防止 `EmitEventOp` 在同一 tick 内 emit 事件后，`completeTask` 立即 unsubscribe 导致事件无人接收（见 §3 时序末尾）。

### 2.7 运行时条件控制：`IfConditionOp` + `elseSkip`

TaskSequence 引入运行时条件跳转。最轻量形态：

```java
record IfConditionOp(
    String conditionName,
    Map<String, String> params,
    int skipCount,          // 条件为 true 时跳过的 step 数
    boolean elseSkip        // true = 条件为 false 时才跳（语义反转）
) implements AtomicOp {
    public int baseManaCost() { return 0; }
}
```

执行时：
- `eval(conditionName, params)` → 结果为 `conditionTrue`
- `elseSkip ? !conditionTrue : conditionTrue` → `skipIt`
- `skipIt == true` → `stepIndex += skipCount + 1`（跳过下 N 个 step）
- `skipIt == false` → `stepIndex += 1`（正常推进）
- **越界**：`stepIndex >= sequence.size()` → 任务 complete（private queue 则为空 → 结束）

**V1 预置条件**：

| conditionName | 说明 | params |
|---------------|------|--------|
| `resource_below` | 仓库某资源低于阈值 | `resource`, `threshold` |
| `inventory_has` | NPC 背包包含某资源 ≥ 数量 | `resource`, `amount` |
| `inventory_full` | NPC 背包是否满 | 无 |

**示例**：库存不足则 emit，充足则跳过 EmitEventOp：

```
steps: [
  IfConditionOp("resource_below", {resource: "stone_bricks", threshold: 64}, 1, true),
  // resource_below 为 false（库存充足）时，elseSkip=true → skipIt=true → 跳过 EmitEventOp
  EmitEventOp("resource_low", ...)
]
```

**分类**：纯 Op，可在 tick 内连续执行。

**private queue 中的 `IfConditionOp`**：`skipCount` 通过连续 `popPrivate()` 实现。私人队列长度不足 → 清空即结束。约束：若 `IfConditionOp` 跳数超出序列长度，任务直接 complete。

### 2.8 纯/副作用 Op 与 Tick 批处理

当前"每 tick 推进一步"的模型过于僵化。实际上很多 Op（条件检查、emit 事件）无副作用，可以一口气跑。

**分类标准**：不直接修改 World/边界状态 + 永远返回 DONE = 纯 Op。

| Op | 分类 | 理由 |
|---|---|---|
| `EmitEventOp` | 纯 | emit 仅入队，tick 末尾才 dispatch |
| `IfConditionOp` | 纯 | 只读检查 |
| **所有其余 Op** | 副作用 | 写方块/改资源/执行仪式等 |

**批处理规则**（适用于普通 NPC 和 System 蓝图）：

```
TaskExecutionSystem.update(world):
  for each NPC:
    循环 {
      取当前 step 的 AtomicOp
      若纯 Op → execute → stepIndex++（不扣 mana，不计 tick 边界）
      若副作用 Op → mana 检查 → execute → DONE则 stepIndex++，WAITING则 break
                    → 一个副作用 Op 执行后 break 内层循环（下 tick 继续）
    }
```

- 纯 Op 可以一口气跑一串（如 3 个 `IfConditionOp` + 1 个 `EmitEventOp`）
- 撞到副作用 Op → 执行 → 无论结果都退出本轮（下 tick 继续下个 step）
- WAITING 的副作用 Op 下 tick 重试同一个 step

**优点**：`IfConditionOp(true) + EmitEventOp` 可在同一 tick 内原子完成，不会有"条件检查和 emit 跨越两个 tick"的竞争。

### 2.9 System 蓝图 —— infrastructure 事件的统一入口

基础设施事件（`ResourceLow`、`TaskAwaitingResources` 等）也通过蓝图 trigger 系统路由，消除 `EventDrivenTaskSource` 中所有硬编码。

#### 2.9.1 与普通蓝图的关键差异

| | 普通蓝图 | System 蓝图 |
|---|---|---|
| 注册容器 | `BlueprintRegistry` | `SystemBlueprintRegistry`（独立） |
| Steps 执行者 | NPC（TaskExecutor） | Heartbeat 心跳直接驱动 |
| Steps 执行方式 | 每 tick 批处理（§2.8） | 一次 heartbeat 跑完所有纯 Op + 一批副作用 Op |
| Trigger 生命周期 | assign→complete，随任务消亡 | 启动时永久注册 |
| 任务池 | GlobalTaskPool | 不在池中，不参与调度 |
| 典型实例 | `plant:wheat` | `warehouse:monitor` |

#### 2.9.2 `warehouse:monitor` 形态

纯 trigger 容器，steps 为空。infrastructure 事件由边界接口直接 emit（非 system 蓝图 step 产生）：

```
SystemBlueprint "warehouse:monitor":
  steps: 无
  triggers:
    - on CustomEvent("resource_low") → source "gather:{{resource}}" (priority=15)
    - on CustomEvent("task_awaiting_resource") → source "gather:{{resource}}" (priority=40)
```

Trigger 永久订阅在 `SystemBlueprintRegistry` 上。

#### 2.9.3 System 蓝图的 steps 执行

当 system 蓝图有 steps（如自定义监控），由新增的 `SystemBlueprintSystem`（排在 `TaskSourcePoller` 之前）每 tick 驱动执行。约束：
- 副作用 Op 不支持 WAITING（不存在"下 tick 重试"的上下文 — 无 NPC）
- 纯 Op 照常批处理（连续跑直到撞到副作用 Op 或末尾）

#### 2.9.4 System 编排顺序更新

engine-arch §15 的注册顺序更新为：

```
1. ManaRegenSystem
2. SystemBlueprintSystem    ← 新增（驱动 system 蓝图 steps + 管理永久 trigger 订阅）
3. TaskSourcePoller
4. SchedulerSystem
5. TaskExecutionSystem
6. EventBus.dispatch()
```



---

## 3. 运行时流程

```
1. plant:wheat 蓝图注册：
   - steps: [ResourceRequestOp(种子), RitualOp(种植引导), EmitEventOp("planted", {crop: wheat})]
   - triggers: [{ eventName: "planted", paramFilter: {crop: "wheat"},
                  sourceBlueprintId: "harvest:wheat", priority: 10,
                  paramMapping: {x: "x", y: "y", z: "z"} }]

2. TaskSource 发布 TaskRequest("plant:wheat", {x:10,y:64,z:20}, priority=10)
   → BlueprintRegistry.compile() → CompiledBlueprint(sequence, triggers)
   → GlobalTaskPool.addTask() → GlobalTask(triggers=triggers)

3. SchedulerSystem 分配给 NPC-A
   → taskPool.assign(taskId, npcId)
      内部：for each trigger → eventBus.subscribe(CustomEvent.class, handler)
      exec.taskParams = request.params

4. TaskExecutionSystem 批处理循环 → EmitEventOp 作为最后一步 → emit("planted", ...)
   → stepIndex++ → isComplete() → taskPool.completeTask()
      内部：for each subscription → eventBus.unsubscribe(sub)
             // ↑ 仅暂存到 deferredRemovals，handler 仍在线

5. tick末尾 EventBus.dispatch()
   → 投递 CustomEvent("planted", ...) → 步骤3的 handler 仍在 → 命中！
   → handler 匹配 paramFilter → paramMapping 重命名
   → TaskRequest("harvest:wheat", {x:10,y:64,z:20}, 10) → taskPool.addTask() → #43
   → 投递完毕 → 执行 deferredRemovals → 移除 handler

6. 下个 SchedulerSystem 心跳 → 分配 #43 harvest:wheat 给 NPC-B
```

---

## 4. 改动范围（增量，非破坏性）

| 文件 | 改动性质 | 行数估计 |
|------|---------|---------|
| `AtomicOp.java` | 新增 `EmitEventOp`、`IfConditionOp` record | ~15 |
| `OpExecutor` 注册 | 新增 `EmitEventExecutor`、`IfConditionExecutor` | ~30 |
| `EventBus.java` | 加 `unsubscribe` + `Subscription` | ~5 |
| `SimpleEventBus.java` | 实现 `unsubscribe` | ~15 |
| `CustomEvent.java` | 新建 record | ~5 |
| `TriggerDeclaration.java` | 新建 record | ~8 |
| `CompiledBlueprint.java` | 新建 record | ~5 |
| `Blueprint.java` | 返回值改为 `CompiledBlueprint`，`BlueprintSteps` 保留函数式 | ~8 |
| `BlueprintRegistry.java` | 透传 triggers | ~3 |
| `GlobalTask.java` | 加 `triggers` + `subscriptions` 字段 | ~5 |
| `GlobalTaskPool.java` | assign/complete 时管理订阅 | ~25 |
| `TaskRequest.java` | 移除 `GridPos location` 字段 | ~3 |
| `TaskExecutor.java` | 加 `taskParams` 字段 | ~3 |
| `TaskExecutionSystem.java` | 纯/副作用 Op 批处理循环 | ~20 |
| `SystemBlueprintRegistry.java` | 新建 — 独立注册 + 永久订阅管理 | ~20 |
| `SystemBlueprintSystem.java` | 新建 — 每 tick 驱动 system 蓝图 steps | ~15 |

**总计：约 195 行，零架构翻车。**

---

## 5. 暂不处理（V2+）

- params 映射规则 DSL（事件字段 → TaskRequest 字段的显式映射，V1 由蓝图自行解析）
- 循环依赖检测（A → B → A）
- 复杂条件过滤（范围判断、不等于等）
- `EventDrivenTaskSource` 中硬编码逻辑的迁移（逐步替换为 System 蓝图）
- `{{}}` 模板中的 `{{shortfall}}` 等动态计算变量

---

**文档状态**：已实现（2026-06-19），全部 9/9 单测通过。grill-me 访谈完成，27 项决策已确认。
