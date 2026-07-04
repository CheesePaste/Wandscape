# core/ — ECS 核心框架

纯 Java 21，零 MC 依赖。ECS 世界 + 组件 + 边界接口 + 内部事件。

**重构后**：op/task/road/system 已独立为各自顶级包。core 只保留 ECS 框架本质。

## 入口

`CoreBootstrap.bootstrap(config)` → 返回装配好的 `World` 实例。`BootStrapConfig` record 打包了所有边界实现 + 蓝图注册表 + TaskSource 列表。

## ecs/ — ECS 框架

- **`World.java`** — 中央容器：持有 ComponentStore Map + System 列表 + 边界服务引用。`createEntity()` / `addComponent()` / `query(A,B,C)` 交集查询 / `tick(delta)` 按序执行所有系统。`clearAllTasks()` 紧急恢复：清空任务池 + 建筑队列 + 重置所有 NPC。异步门控通过 `startAsyncOp()` → `hasPendingAsyncOps()` 实现 tick 阻塞
- **`System.java`** — `@FunctionalInterface`：`void update(World, float delta)`
- **`ComponentStore.java`** — 组件存储接口
- **`HashMapComponentStore\<T\>.java`** — HashMap 存储 + 排序实体列表缓存（交集查询用）
- **`CoreBootstrap.java`** — 引擎装配入口
- **`CoreBootstrapConfig.java`** — 装配配置 record

## component/ — ECS 组件 + 配套处理器

| 组件 | 关键字段 | 说明 |
|------|---------|------|
| Position | GridPos | 实体位置 |
| ManaPool | current/max/regenPerTick + regen()/consume()/add() | 魔力池 |
| EquipmentComponent | 各槽位装备管理 | 装备 + 属性修饰器 |
| TaskExecutor | globalTaskId/currentSequence/stepIndex/stance/ExecutorState + NpcTaskQueue | 任务执行状态 |
| NpcTaskQueue | pending deque + currentPackage + suspensionStack(max3) | NPC 任务队列 |
| Inventory | 列表存储 + add/remove/count/hasEnough | 物品背包 |
| NavigationState | mode(IDLE/PATHFINDING/...) + target + CompletableFuture | 导航状态 |
| ColonyMember | colonyId(UUID) | 殖民地归属 |
| ColonyMetadata | center/territoryRadius/prosperity | 殖民地元数据 |
| SuspensionContext | 挂起包快照 | 紧急任务打断上下文 |
| **ManaRegenSystem** | — | **每 tick 恢复所有 ManaPool 的 ECS System**（原在 ecs/，因紧耦合 ManaPool 归入 component/） |

**`ManaRegenSystem`** 虽然是 ECS System（实现 `core/ecs/System`），但因为只操作 `ManaPool` 组件且零 MC 依赖，放在 component/ 比放在 ecs/ 框架包里更合理。

## boundary/ — 8 个边界接口

`BlockOps`(7方法) / `EntityOps` / `RitualOps`(beginRitual 返回 CompletableFuture) / `MovementOps`(navigateTo 返回 CompletableFuture) / `ColonyResourceAccess`(6方法) / `EventBus`(emit/subscribe/unsubscribe) / `ResourceAddedListener`(仓库添加资源通知) / `ResourceShortageHandler`(资源短缺时回调)

引擎层实现在 `engine/boundary/`。

## event/ — 内部事件定义

通过 `SimpleEventBus` 在 tick 末批量派发。

| 事件 | 用途 | 订阅者数 |
|------|------|---------|
| `TaskCompleted` | 全局任务完成 | 1（扩展预留） |
| `CustomEvent` | 蓝图 emit 的自定义事件 | 4 |
| `NarrativeEventTriggered` | 叙事事件（游客行为） | 2 |

1:N 场景使用事件，1:1 场景使用 `core/boundary/` 接口注入。

## types/ — 基础 record/枚举

- `GridPos(x,y,z)` / `BlockType("mod:id")` / `ResourceId` / `ResourceStack` / `RitualId` / `EntityId` / `EffectId`
- `InteractAction(actionType,target)`
- `EquipmentSlot(WAND, 预留 RING/AMULET/ROBE/BOOTS)`
- `EquipmentPreset(id,slot,modifiers,color)`
- `AttributeType(RANGE, MANA_COST_MULTIPLIER, MAX_MANA, MANA_REGEN, MAX_HP, MOVE_SPEED)`
- `AttributeModifier(attribute,operation,value)` / `ModifierOperation(ADD/MULTIPLY)`

## 测试覆盖

26+ 个测试文件，在 `src/test/java/` 对应路径下。
