# Magic Colony —— 核心引擎架构设计 (v1)

**状态**：访谈中，持续更新
**关联文档**：Goal.md（系统设计）、arch.md（ECS方向）

---

## 0. 核心引擎边界

- **纯 Java 标准库**，与 Minecraft 零交集。
- 所有基础类型自建（`GridPos`、`ResourceId` 等），不依赖 `BlockPos`/`ItemStack` 等 MC 类型。
- 适配层（Adaptor）负责核心层 ↔ MC 的映射。

---

## 1. ECS 骨架

| 决策项 | 选择 | 说明 |
|--------|------|------|
| ECS 框架 | **自建极简 ECS** | 无第三方依赖，规模不大无需 Archetype |
| Entity 表示 | **`long id`** | ID + version bits 防 ABA，最轻量 |
| Component 存储 | **`Map<Long, T>` 起步** | 每个 Component 类型一个 Map，预留 SparseSet 升级接口 |
| System 抽象 | **`interface System { void update(World world, float delta); }`** | 手写 `world.query()`，按注册顺序执行 |

```java
class World {
    ComponentStore<Position> positions;
    ComponentStore<ManaPool> manaPools;
    ComponentStore<TaskExecutor> taskExecutors;
    // ... 每种 Component 一个 store
    List<System> systems;
    long nextEntityId;

    long createEntity();
    <T> void addComponent(long entity, T component);
    <T> T get(long entity, Class<T> type);
    <T> boolean has(long entity, Class<T> type);
    void addSystem(System sys);
    void tick(float delta);
}

interface System {
    void update(World world, float delta);
}
```

---

## 2. 基础值类型层

所有上层逻辑的原子词汇，无 MC 依赖：

| 类型 | 说明 |
|------|------|
| `EntityId` | 强类型包装 `long`，防止与其他 ID 混用 |
| `GridPos` | `(int x, int y, int z)` 方块坐标 |
| `ResourceId` | 资源标识（石砖、玻璃、铁锭等） |
| `ResourceStack` | `ResourceId` + `int amount` |
| `BehaviourTag` | 行为标签枚举：`building` / `farming` / `crafting` / `ritual` / `entity_interaction` |
| `BehaviourLevel` | 1~5 的等级值 |
| `AtomicOp` | 见第3节 |
| `Mana` | 数值包装或直接 int（待定） |

---

## 3. AtomicOp —— sealed record 多态

四种原子操作，各有独立字段：

```java
sealed interface AtomicOp permits TransformOp, BlockInteractOp, EntityInteractOp, RitualOp {
    int baseManaCost();  // 基础魔力消耗
}

record TransformOp(GridPos target, BlockType from, BlockType to,
                   boolean consumeSource, List<ResourceStack> drops,
                   ResourceStack consumable) implements AtomicOp {}

record BlockInteractOp(GridPos target, InteractAction action) implements AtomicOp {}

record EntityInteractOp(EntityId target, EffectId effect, int strength, int duration) implements AtomicOp {}

record RitualOp(RitualId ritual, GridPos target, int channelTicks) implements AtomicOp {}
```

---

## 4. TaskSequence

**物资请求统一为 AtomicOp**（瞬发型或等特殊处理的）。`TaskSequence` 退化为：

```java
record TaskSequence(List<AtomicOp> steps) {}
```

---

## 5. OpResult 执行结果模型

同步返回枚举：

```java
enum OpResult {
    DONE,          // 瞬间完成，推进 stepIndex
    WAITING,       // 异步操作已启动 → MC 边界层持有 CompletableFuture
                   // TaskExecutionSystem 退出，不推进 stepIndex
                   // 引擎逻辑帧门控关闭，直到 future.complete() 后重新打开
}
```

System 调用 `opExecutor.execute(op, world, npcId)`，拿到结果后：
- `DONE` → 扣魔力，推进 stepIndex / pop privateQueue
- `WAITING` → 不扣魔力，不推进。MC 边界层在异步操作完成时调用 `future.complete(null)`，`whenComplete` 回调自
动清理 pendingFutures。全部 resolve 后引擎逻辑帧自动推进。

> **与 V1 的差异**: V1 用轮询（每 MC tick 调 `pollRitual()`）。V2.5 改为事件驱动 — `WAITING` 意味着"已在异步执行，MC 边界层完成后回调"，引擎 tick 停止推进直到所有异步 Op 完成。

---

## 6. OpExecutor 策略注册表

按操作类型分策略，非 System：

```java
interface OpExecutor<T extends AtomicOp> {
    Class<T> opType();
    OpResult execute(T op, World world, long npcId);
}
```

`TaskExecutionSystem` 通过注册表找到对应 executor 调用，自身只负责推进和状态切换。

---

## 7. 边界接口（核心层 ↔ 适配层）

核心层定义接口，适配层注入实现。World 持有所有服务引用：

```java
class World {
    BlockOps blockOps;         // setBlock, getBlock, isAir, toggle, activate, openGui
    EntityOps entityOps;       // applyEffect, getPosition
    RitualOps ritualOps;       // applyRitual
    ResourceOps resourceOps;   // transfer
    ColonyResourceAccess colonyResources;  // hasEnough, reserve, commit, release, available
    EventBus eventBus;         // emit, subscribe
    GlobalTaskPool taskPool;   // 全局任务池
    // ...
}
```

---

## 8. Component 清单

### 8.1 Position
```java
record Position(GridPos pos) {}
```

### 8.2 ManaPool
```java
record ManaPool(int current, int max, int regenPerTick) {}
```

### 8.3 WandCarrier
存预计算的能力并集（装备/卸下法杖时重算），不存原始法杖列表：
```java
record WandCarrier(
    Map<BehaviourTag, BehaviourLevel> capabilities,
    float bestManaEfficiency,
    int maxRange
) {}
```

### 8.4 TaskExecutor
合并了私有任务队列和全局任务进度：
```java
class TaskExecutor {
    Deque<AtomicOp> privateQueue;    // 高优先级，FIFO，DONE 时 pop
    Long globalTaskId;               // 当前接取的全局任务 ID（null = 无）
    TaskSequence currentSequence;    // 全局任务的蓝图
    int stepIndex;                   // 当前执行到第几步
    TaskState state;                 // ACTIVE / WAITING / IDLE
}
```

### 8.5 Inventory
NPC 背包，核心层自有：
```java
record Inventory(List<ResourceStack> items, int capacity) {}
```

### 8.6 Colony 相关
```java
record ColonyId(UUID id) {}
record ColonyMember(ColonyId colony) {}         // 挂在 NPC Entity 上
record ColonyMetadata(ColonyId id, GridPos center, int prosperity) {}  // 挂在 Colony Entity 上
```

---

## 9. 全局任务池

World 级独立容器，不在 ECS 内部：

```java
class GlobalTaskPool {
    Map<Long, GlobalTask> tasks;
    // ...
}

record GlobalTask(
    long id,
    TaskSequence sequence,
    Map<BehaviourTag, BehaviourLevel> requirements,
    int priority,
    TaskState state,
    int stepIndex,
    Long assignedNpcId,
    ResourceStack awaitingResource,
    Deque<InterruptRecord> interruptHistory,
    ApprovalInfo approval
) {}

enum TaskState {
    PENDING_APPROVAL,
    PENDING_ASSIGN,
    IN_PROGRESS,
    AWAITING_RESOURCES,
    INTERRUPTED,
    COMPLETED
}

record InterruptRecord(long npcId, long timestamp, int atStepIndex) {}
record ApprovalInfo(GridPos suggestedPosition, long deadline, boolean autoApproved) {}
```

---

## 10. 任务来源（TaskSource）

统一轮询接口 + 领域事件总线：

```java
interface TaskSource {
    int pollIntervalTicks();
    void poll(GlobalTaskPool pool, World world);
}
```

事件总线（极简实现）：
```java
interface EventBus {
    <T> void emit(T event);
    <T> void subscribe(Class<T> type, Consumer<T> handler);
}
```

领域事件类型：
- `ResourceLow(ResourceId, int current, int threshold)`
- `MobNearby(GridPos, int count)`
- `NpcStuck(long npcId, String reason)` —— V2
- `TaskAwaitingResources(long taskId, ResourceStack needed)`
- `BuildingCompleted(GridPos, String buildingType)`
- `ColonyEvent(String eventType, Map<String, Object> params)`

---

## 11. 任务编译器（蓝图注册表）

```java
record TaskRequest(String blueprintId, GridPos location, Map<String, String> params, int priority) {}

interface TaskCompiler {
    TaskSequence compile(TaskRequest request, World world);
}
```

TaskSource 发布 `TaskRequest`，编译器查找蓝图 ID 展开为 `TaskSequence`。

---

## 12. 调度器（SchedulerSystem）

每 2 秒一心跳，分数匹配：

1. 扫描所有 `privateQueue` 为空且 `globalTaskId == null` 的 NPC
2. 按 `ColonyMember.colony` 分组
3. 对每组：筛选满足 `requirements` 的 NPC
4. 对候选 NPC 计算分数：`range × 0.5 + (1 - manaEfficiency) × 0.3 + behaviourLevel × 0.2`
5. 全局任务按 priority 降序，分配给最高分 NPC

---

## 13. 魔力系统

- `AtomicOp.baseManaCost()` 声明基础消耗
- 实际消耗 = `baseCost × WandCarrier.bestManaEfficiency`
- `TaskExecutionSystem` 在 execute 前检查 `ManaPool.current >= actualCost`，不足则跳过
- 执行成功（DONE）后扣减
- `WAITING` 时不扣魔力
- 恢复由 `ManaRegenSystem` 每 tick 按 `regenPerTick` 恢复

---

## 14. 库存系统

| 存储 | 位置 | 实现 |
|------|------|------|
| NPC 背包 | `Inventory` Component（核心层自有） | 直接操作 |
| 殖民地仓库 | `ColonyResourceAccess` 接口（核心层定义） | 适配层实现 |

```java
interface ColonyResourceAccess {
    boolean hasEnough(ResourceId resource, int amount);
    boolean reserve(ResourceId resource, int amount);
    boolean commit(ResourceId resource, int amount);
    void release(ResourceId resource, int amount);
    int available(ResourceId resource);
}
```

---

## 15. System 编排顺序

`World.tick()` 按注册顺序依次执行：

```
1. ManaRegenSystem        — 先恢复资源
2. TaskSourcePoller       — 生成新任务
3. SchedulerSystem        — 分配任务给空闲 NPC
4. TaskExecutionSystem    — NPC 干活（遍历 + 推进步骤）
5. EventBus.dispatch()    — 清理本帧事件
```

---

## 16. TaskExecutionSystem 逐 tick 流程

```
遍历所有持有 Position, ManaPool, TaskExecutor, WandCarrier, Inventory 的 NPC:

  1. 确定当前 AtomicOp：
     - privateQueue 非空 → peek()
     - 否则 globalTaskId != null → currentSequence.get(stepIndex)
     - 否则 continue

  2. 计算魔力消耗：actualCost = baseCost × bestManaEfficiency

  3. 魔力检查：mana.current < actualCost → continue

  4. 执行：OpResult result = registry.get(op.getClass()).execute(op, world, npcId)

  5. 处理结果：
     DONE → 扣魔力，private 则 pop，global 则 stepIndex++
            global 完成时清理 taskId，emit TaskCompletedEvent
     WAITING → 不扣魔力，不推进，下 tick 重试
     INTERRUPTED → 魔力不返还，清理 taskId，记录中断，emit TaskInterruptedEvent
```

---

## 17. Tick 模型

### 17.1 引擎逻辑帧 ≠ MC 游戏帧

引擎 tick 是**逻辑帧**，与 MC 20tps 解耦。一次逻辑帧内：
- SchedulerSystem 分配任务
- TaskExecutionSystem 对每个 NPC 批处理纯 Op + 执行一个副作用 Op
- 副作用 Op 全部 DONE 后逻辑帧推进

### 17.2 异步操作门控 (V2.5)

部分 Op（如 MoveOp 寻路、RitualOp 引导）需要多个 MC tick 才能完成。引擎用 `CompletableFuture` 实现类 Promise 的事件驱动门控：

```
引擎逻辑帧 N:
  分发 Op 到各 NPC
  ├─ NPC1: TransformOp → DONE (瞬时)
  ├─ NPC2: MoveOp → WAITING → world.startAsyncOp("move_to_10_64")
  └─ NPC3: MoveOp → WAITING → world.startAsyncOp("move_to_20_64")
  → pendingFutures = 2 → 后续 MC tick 跳过 world.tick()

MC tick (×N):
  NPC2 到达终点 → future.complete(null) → pendingFutures = 1
  NPC3 到达终点 → future.complete(null) → pendingFutures = 0
  → !hasPendingAsyncOps() → 下一 MC tick 触发引擎逻辑帧 N+1
```

**API**:
| 方法 | 用途 |
|------|------|
| `world.startAsyncOp(label)` → `CompletableFuture<Void>` | MC 边界层启动异步操作，获取 future |
| `future.complete(null)` | MC 操作完成时 resolve |
| `future.completeExceptionally(ex)` | MC 操作失败时 reject |
| `future.orTimeout(30, SECONDS)` | 超时保护，NPC 永不卡死 |
| `world.hasPendingAsyncOps()` | Wandscape.onServerTick 门控：true → 跳过引擎 tick |

**V1 兼容**: 所有 V1/V2 操作都是同步的 — 返回 DONE 且不调 `startAsyncOp()`。`pendingFutures` 永远空，门控不开销。`whenComplete` 回调自动清理已完成的 future，无需手动管理。

- `WAITING` 语义改变：不再每引擎 tick 轮询，而是 MC 层持有 CompletableFuture，操作完成时 `complete()`，引擎门自动打开。

---

## 18. 领域枚举类型

每种领域概念用 `record` 包裹 String ID，保证类型安全 + 数据驱动：

```java
record BlockType(String id) {}       // "minecraft:stone", "magiccolony:glowstone"
record InteractAction(String id) {} // "toggle", "activate", "open_gui"
record EffectId(String id) {}       // "damage", "heal", "follow"
record RitualId(String id) {}       // "item_teleport", "rain_call", "warding"
```

核心层只定义常量引用已知的标准 ID，其余由适配层自由注册。

---

## 19. 蓝图 DSL 与 TaskCompiler

### 19.1 蓝图 DSL

蓝图是高级语义 DSL（可非常简单），编译到 `TaskSequence`：

```json
{
  "id": "build:library",
  "label": "建造图书馆",
  "requirements": {"building": 3},
  "steps": [
    {"type": "build", "material": "stone_bricks", "shape": "box", "from": [0,0,0], "to": [5,3,5]},
    {"type": "build", "material": "glass", "shape": "box", "from": [1,1,1], "to": [4,2,4]},
    {"type": "fill_bookshelves", "count": 16}
  ]
}
```

### 19.2 TaskCompiler + Blueprint 注册表

```java
record TaskRequest(String blueprintId, GridPos location, Map<String, String> params, int priority) {}

interface TaskCompiler {
    TaskSequence compile(TaskRequest request, World world);
}

class BlueprintRegistry implements TaskCompiler {
    Map<String, Blueprint> blueprints;
    
    TaskSequence compile(TaskRequest request, World world) {
        Blueprint bp = blueprints.get(request.blueprintId());
        return bp.generate(request.params(), request.location());
    }
}

interface Blueprint {
    TaskSequence generate(Map<String, String> params, GridPos location);
}
```

每个 step 由数据驱动的生成器展开为一组 AtomicOp（含物资请求）。

---

## 20. World.query() API

可变参数 Class，返回 entity ID 列表：

```java
List<Long> query(Class<?>... componentTypes);
// 用法：world.query(Position.class, ManaPool.class, TaskExecutor.class, WandCarrier.class, Inventory.class)
```

内部逻辑：
1. 取第一个组件类型的 `entities()` 做基准（排序列表）
2. 逐一用其余类型的 `entities()` 取交集
3. 返回交集 ID 列表

调用方通过 `world.get(id, Component.class)` 取组件。

---

## 21. ComponentStore 接口契约

```java
interface ComponentStore<T> {
    void add(long entity, T component);     // 添加/覆盖
    void remove(long entity);               // 立即删除
    T get(long entity);                     // 查询，null 若不存在
    boolean has(long entity);               // 存在检查
    List<Long> entities();                  // 所有持有此组件的 entity ID（排序，可缓存）
}
```

`entities()` 返回排序列表（首次排序后缓存，写操作时失效）。单线程顺序执行，`remove` 立即生效。

---

## 22. 功能方块

功能方块（奥术工作台、魔力池、仓库、防御水晶、农场结界等）是 **ECS Entity + Component**，纳入统一查询体系：

```java
record ArcaneWorkbench(GridPos pos, ProductionQueue queue) {}
record WarehouseCore(GridPos pos, Map<ResourceId, Integer> stocks, Map<ResourceId, Integer> thresholds) {}
record ManaPoolCore(GridPos pos, int stored, int threshold) {}
```

`ColonyResourceAccess` 接口保留作防腐层，内部实现查仓库 Entity，对外契约稳定。

---

## 23. NPC 空闲行为

NPC 完全空闲（`privateQueue` 空 + `globalTaskId == null`）时：
- 核心层不做任何事，`TaskExecutionSystem` 跳过该 NPC
- MC 适配层检测到 `TaskExecutor.state == IDLE`，自行做动画、徘徊等
- 与"引擎 tick ≠ 游戏 tick" 一致——核心层管逻辑，适配层管呈现

---

## 24. EventBus 投递时机

**批量延迟投递**：所有 `emit()` 暂存队列，`EventBus.dispatch()` 在 tick 末尾统一分发。

同一 tick 内各 System 互相不可见对方的事件，避免级联副作用。事件的效应延迟一个 tick。

---

## 25. 物资请求 AtomicOp 与即时编排

### 25.1 ResourceRequestOp

物资请求也是 `AtomicOp`，NPC 通过 ritual 法杖自己传送物资：

```java
record ResourceRequestOp(ResourceStack requested) implements AtomicOp {}
```

`OpExecutor` 逻辑：
1. 检查 `ColonyResourceAccess.hasEnough()` → 无货返回 `WAITING`
2. `reserve()` → `Inventory.add()` → `commit()` → 返回 `DONE`

`WAITING` 时任务挂起，`TaskSource` 检测事件后发布补货任务。

### 25.2 即时编排

`OpExecutor` 可以在执行时动态修改 `TaskExecutor.privateQueue`（如插入传送任务）：

```java
class ResourceRequestExecutor implements OpExecutor<ResourceRequestOp> {
    OpResult execute(ResourceRequestOp op, World world, long npcId) {
        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        // 插入物品传送 RitualOp 到私有队列
        exec.privateQueue.push(new RitualOp(ITEM_TELEPORT, op.requested(), WAREHOUSE, NPC_INVENTORY));
        return DONE;  // 编排本身是瞬时的
    }
}
```

`TaskExecutionSystem` 统一调 `execute`，不区分叶子操作和元操作。

---

## 26. 核心层不维护世界状态

- **方块状态**：核心层不维护方块网格，全部穿透 `BlockOps` 接口到适配层
- **非 NPC 实体**：`EntityInteractOp` 的 target 是外部句柄（适配层管理的 MC 实体），核心层不持有这些实体的数据
- `EntityInteractOp` 在引擎层仅声明"触发此交互"，魔力/调度/状态切换由引擎管，实际副作用由适配层接管

---

## 27. RitualOp —— 异步执行 (V2.5)

### 27.1 V2.5 事件驱动模型（推荐）

仪式引导时长不固定，使用 `CompletableFuture` 事件驱动：

```
RitualOp 首次执行 → RitualExecutor.execute()
  → beginRitual() 启动 MC 层引导
  → world.startAsyncOp("ritual_warding") → 持有 future → 返回 WAITING
  → 引擎逻辑帧门控关闭

MC tick (×N):
  仪式引导持续... MC 层维护引导进度

引导完成 → MC 层: future.complete(null)
  → whenComplete 自动清理 → 引擎逻辑帧门打开 → 下个逻辑帧 RitualOp DONE
```

### 27.2 旧轮询模型（仍保留）

部分简单仪式（如 `self_teleport`）不走 CompletableFuture，而是沿用原有轮询接口在每个 MC tick 推进。选择权在 MC 边界实现：

```java
interface RitualOps {
    void beginRitual(RitualId ritual, GridPos target, World world, long casterId);
    OpResult pollRitual(RitualId ritual, GridPos target, World world, long casterId);
}
```

核心层完全不管引导多久。中断即失败，无部分生效。

---

## 28. 殖民地边界

`ColonyMetadata` 加 `(GridPos center, int radius)` 定义矩形领地范围。
判断某点是否在殖民地内：`abs(pos.x - center.x) <= radius && abs(pos.z - center.z) <= radius`（O(1)）。

---

## 29. 殖民地生命周期

- 核心层假设殖民地 Entity 已由适配层创建（如玩家放置"殖民地水晶"时）。
- 核心层提供简单 API 让适配层调用：`createColony(GridPos center, int radius)` → EntityId。
- System 如调度器在面对"无殖民地 Entity"时优雅降级（跳过心跳）。

---

## 30. 任务审批流程

`GlobalTaskPool` 暴露直接 API：

```java
class GlobalTaskPool {
    void approve(long taskId);  // PENDING_APPROVAL → PENDING_ASSIGN
    void reject(long taskId);   // PENDING_APPROVAL → COMPLETED（丢弃）
}
```

适配层 UI 按钮直接调用。小任务（如采集/生产/物流循环）跳过审批，直接生成 `PENDING_ASSIGN`。
调度器只分配 `PENDING_ASSIGN` 状态的任务。

---

## 31. TaskState 完整状态转移图

```
TaskSource.poll()
    │
    ├─ 小任务 ─────────────────────────────► PENDING_ASSIGN
    │                                              │
    └─ 大型任务 ──► PENDING_APPROVAL                │
                        │                          │
                   reject → COMPLETED               │
                        │                          │
                      approve                       │
                        │                          │
                        └────────────► PENDING_ASSIGN
                                              │
                                    SchedulerSystem 分配
                                              │
                                              ▼
                                         IN_PROGRESS
                                              │
                              ┌───────────────┼───────────────┐
                              │               │               │
                         资源不足           执行完毕       中断/被挤
                              │          (stepIndex=size)        │
                              ▼               │               │
                     AWAITING_RESOURCES        ▼               │
                              │           COMPLETED            │
                         补货完成                               │
                              │                                │
                              ▼                                │
                      PENDING_ASSIGN ◄─────────────────────────┘
                     (保留 stepIndex，释放 NPC)
```

- **中断**：记录 `InterruptRecord`，保留 `sequence` 和 `stepIndex`，状态回 `PENDING_ASSIGN`，释放NPC。不强制换人。
- **物资等待**：释放 NPC，状态 `AWAITING_RESOURCES`，记录 `awaitingResource`。补货完成后状态回 `PENDING_ASSIGN`。
- **物资等待唤醒**（详见 §32）。

---

## 32. AWAITING_RESOURCES 唤醒 —— 基于事件的广播机制

完整流程：

```
0. 前置：NPC-A 执行建造任务 stepIndex=3 遇到 ResourceRequestOp(STONE_BRICKS, 64)

1. ResourceRequestOp.executor → ColonyResourceAccess.hasEnough() → 仓库仅 20 → WAITING

2. TaskExecutionSystem 检测 WAITING：
     → emit TaskAwaitingResourcesEvent(taskId, STONE_BRICKS, need=44)
     → GlobalTask.state = AWAITING_RESOURCES, awaitingResource = (STONE_BRICKS, 44)
     → assignedNpcId = null（释放 NPC-A）
     → NPC-A 的 TaskExecutor.globalTaskId = null, state = IDLE

3. 下个 tick，EventBus.dispatch()：
     → TaskSource（仓库监控）收到 TaskAwaitingResourcesEvent
     → 发布补货任务：TaskRequest(blueprint="gather:stone", ...) → PENDING_ASSIGN

4. SchedulerSystem 分配补货任务给 NPC-B

5. NPC-B 完成补货 → 仓库 STONE_BRICKS += 44
     → ColonyResourceAccess.commit() 内部 emit ResourceFulfilledEvent(STONE_BRICKS, 44)

6. 下个 tick，EventBus.dispatch()：
     → GlobalTaskPool 订阅了 ResourceFulfilledEvent
     → 扫描所有 AWAITING_RESOURCES 的任务
     → 对每个检查 ColonyResourceAccess.available(awaitingResource) >= needed
     → 满足 → 任务唤醒：state = PENDING_ASSIGN, awaitingResource = null

7. 下次 SchedulerSystem 心跳 → 分配此任务给任意空闲 NPC-C
     → NPC-C 从 stepIndex=3 继续执行
```

核心：`GlobalTaskPool` 订阅 `ResourceFulfilledEvent`，在 `EventBus.dispatch()` 时批量检查所有等待中的任务。
调度器自然在下次心跳发现新的 `PENDING_ASSIGN` 任务——无需额外 broadcast。

---

## 33. SchedulerSystem 心跳

V1 测试阶段：**手动触发**（直接调 `world.scheduleTick()`），不自动轮询。

未来用启发式替换固定间隔（如任务数 > 空闲 NPC × 2 就立即调度，否则降频）。

---

## 34. ColonyResourceAccess 实现

**适配层实现**。仓库与 MC 物品系统挂钩（箱子、自定义方块等），适配层负责：
- `hasEnough` / `available` → 查 MC 仓库内容
- `reserve` / `commit` / `release` → 操作 MC 仓库

核心层只发请求（类似前后端关系），不持有仓库数据。

---

## 35. V1 TaskSource 最小子集

| TaskSource | 触发 | 发布任务 |
|------------|------|----------|
| `WarehouseSource` | 订阅 `ResourceLow`，poll 发布补货 | `gather:xxx` 采集 |
| `WorkbenchSource` | 检测生产队列，poll 发布 | `craft:xxx` 生产 |
| `PlayerManualSource` | 适配层直接调 API | 玩家手动发布 |

覆盖"物资补货 → 生产 → 玩家操控"最小闭环。

---

## 36. GlobalTaskPool 分配 API

调度器只做匹配，分配通过 Pool API 封装原子操作：

```java
class GlobalTaskPool {
    long addTask(TaskRequest request);  // 内部编译，分配 ID，判断是否需审批
    void approve(long taskId);          // PENDING_APPROVAL → PENDING_ASSIGN
    void reject(long taskId);           // → COMPLETED
    void assign(long taskId, long npcId, World world);  // 调度器调用
}

// assign() 内部：
void assign(long taskId, long npcId, World world) {
    GlobalTask task = tasks.get(taskId);
    TaskExecutor exec = world.get(npcId, TaskExecutor.class);
    task.state = IN_PROGRESS;
    task.assignedNpcId = npcId;
    exec.globalTaskId = taskId;
    exec.currentSequence = task.sequence;
    exec.stepIndex = task.stepIndex;    // 中断恢复时从此步开始
    exec.state = ExecutorState.ACTIVE;
}
```

---

## 37. TaskExecutor.state —— 独立 ExecutorState 枚举

`TaskExecutor.state` 是 NPC 的本地执行状态，不和 `GlobalTask` 的生命周期 `TaskState` 混用：

```java
enum ExecutorState { IDLE, ACTIVE, WAITING }

class TaskExecutor {
    Deque<AtomicOp> privateQueue;
    Long globalTaskId;
    TaskSequence currentSequence;
    int stepIndex;
    ExecutorState state;  // IDLE | ACTIVE | WAITING
}
```

- `IDLE`：privateQueue 空且 globalTaskId == null
- `ACTIVE`：正在执行，下次 tick 继续
- `WAITING`：当前 Op 返回 WAITING，暂停推进

`TaskState` 只管全局任务全生命周期，`ExecutorState` 只管 NPC 执行节奏。

---

## 38. V1 推迟项

- `OpResult.INTERRUPTED` — V1 无中断机制
- `TaskState.INTERRUPTED` — 同上，中断后直接回 `PENDING_ASSIGN`（保留进度）
- 卡死监控与自愈

---

## 39. Engine 引导

```java
record EngineConfig(
    BlockOps blockOps,
    EntityOps entityOps,
    RitualOps ritualOps,
    ColonyResourceAccess colonyResources,
    List<TaskSource> taskSources,
    BlueprintRegistry blueprints
) {}

class Engine {
    static World bootstrap(EngineConfig config) {
        World world = new World();
        world.blockOps = config.blockOps();
        world.entityOps = config.entityOps();
        world.ritualOps = config.ritualOps();
        world.colonyResources = config.colonyResources();
        world.eventBus = new SimpleEventBus();
        world.blueprintRegistry = config.blueprints();
        world.taskPool = new GlobalTaskPool(world.eventBus, world.blueprintRegistry, world.colonyResources);
        
        // 注册 System（按顺序）
        world.addSystem(new ManaRegenSystem());
        world.addSystem(new TaskSourcePoller(config.taskSources()));
        world.addSystem(new SchedulerSystem());
        world.addSystem(new TaskExecutionSystem(world.taskPool));
        
        return world;
    }
}
```

---

## 40. V1 完整类清单

### 基础值类型（10）
`EntityId`, `GridPos`, `ResourceId`, `ResourceStack`, `BehaviourTag`, `BehaviourLevel`, `BlockType`, `InteractAction`, `EffectId`, `RitualId`

### ECS 框架（4）
`World`, `System`（接口）, `ComponentStore`（接口）, `HashMapComponentStore`

### AtomicOp（7）
`AtomicOp`（sealed）, `TransformOp`, `BlockInteractOp`, `EntityInteractOp`, `RitualOp`, `ResourceRequestOp`, `OpResult`（枚举：DONE, WAITING）

### OpExecutor（2）
`OpExecutor`（接口）, `OpExecutorRegistry`

### 边界接口（5）
`BlockOps`, `EntityOps`, `RitualOps`, `ColonyResourceAccess`, `EventBus`

### Component（7）
`Position`（record）, `ManaPool`（class）, `WandCarrier`（record）, `TaskExecutor`（class）, `Inventory`（record）, `ColonyMember`（record）, `ColonyMetadata`（record）

### 任务系统（8）
`TaskSequence`, `GlobalTask`, `InterruptRecord`, `ApprovalInfo`, `TaskState`（枚举）, `GlobalTaskPool`, `TaskRequest`, `TaskCompiler`（接口）, `Blueprint`（接口）, `BlueprintRegistry`

### 事件（5）
`SimpleEventBus`, `ResourceLow`, `MobNearby`, `TaskAwaitingResources`, `ResourceFulfilled`, `TaskCompleted`

### System（5 + 3 TaskSource）
`ManaRegenSystem`, `TaskSourcePoller`, `SchedulerSystem`, `TaskExecutionSystem`
`TaskSource`（接口）, `WarehouseSource`, `WorkbenchSource`, `PlayerManualSource`

### 引导（2）
`Engine`, `EngineConfig`

**总计约 51 个类/接口/record/enum。**

- `EntityOps`/`RitualOps`/`BlockInteractOp` 保留：引擎层只负责"告知当前应触发什么事件"，副作用全在适配层。
- `OpResult.INTERRUPTED` 推迟。

---

## 41. 暂缓项（V2+）

- 卡死监控与自愈（`StuckMonitorSystem`）
- 高级语义到 AtomicOp 的简易编译器
- 多殖民地隔离（`SubWorld` 拆分）
- 自动审批模式配置

---

**文档状态**：v1 骨架完成，持续访谈更新中。
