# core/ — ECS 核心（纯 Java，零 MC 依赖）

`src/main/java/com/wsteam/wandscape/core/`

## 职责

轻量 ECS 框架 + 领域类型 + 边界接口，是整个模组运行时的核心。**禁止 import MC 类，禁止持有运行时状态**，MC 实现放 `engine/`。

## CoreBootstrap

`CoreBootstrap.bootstrap(config)` 装配流程：
1. 新建 `World`，注入 boundary 服务（blockOps/entityOps/ritualOps/movementOps/colonyResources）+ 新建 `SimpleEventBus`。
2. 注册 7 个 `HashMapComponentStore`：Position/TaskExecutor/Inventory/ColonyMember/ColonyMetadata/NavigationState/EquipmentComponent。
3. 设 `blueprintRegistry`，建 `GlobalTaskPool`、`BuildingTaskPool`、`OpExecutorRegistry`。
4. `SystemBlueprintRegistry.subscribePermanentTriggers`。
5. 按序 addSystem：**SystemBlueprintSystem → TaskSourcePoller → SchedulerSystem → TaskExecutionSystem**。

`createNpc(npcId, colonyId, world)`：创建 ECS 实体并加 Position、EquipmentComponent（seedBaseValues + equipDefaultWand）、TaskExecutor、Inventory(27)、ColonyMember。
`createColony(...)`：加 ColonyMetadata。

`CoreBootstrapConfig`：record 含 10 个配置字段（各池容量、心跳、评分权重等），有默认值。

## ecs/

- `World`：组件 store（LinkedHashMap）、systems、nextEntityId；boundary 公共字段；异步 op 门控（pendingFutures/startAsyncOp/hasPendingAsyncOps）；`createEntity/registerComponent/addComponent/query`（排序交集）；`tick(delta)` 按注册序执行 system 后 `eventBus.dispatch()`；`clearAllTasks`。
- `System`：`@FunctionalInterface void update(World, float)`。
- `ComponentStore`：add/remove/get/has/entities()（排序缓存列表）。
- `HashMapComponentStore`：HashMap 实现，cachedEntities 写时失效。

## component/（9 个）

| 组件 | 关键字段/行为 |
|---|---|
| `ColonyMember` | `UUID colonyId` |
| `ColonyMetadata` | colonyId/center/radius/prosperity；`contains` 仅比较 X/Z 轴距；`create` 新 UUID+prosperity=0 |
| `Inventory` | list + capacity；`add` 剥离方块 NBT 属性再合并；`remove/count/hasEnough` |
| `NavigationState` | `Mode{IDLE,PATHFINDING,TELEPORT_WAITING,TELEPORT_RITUAL}`；target/future/waypoints |
| `NpcTaskQueue` | 三层结构：suspensionStack(LIFO)/currentPackage/pending(FIFO)；`MAX_SUSPENSION_DEPTH=3`；startPackage/enqueueNormal/enqueueUrgent/suspendCurrent/resumeLatest/releaseCurrent(保留进度)/finishCurrentPackage/startNextPending(先恢复挂起) |
| `Position` | GridPos |
| `SuspensionContext` | pkg/stepIndex/suspendedAtTick |
| `TaskExecutor` | 持有 npcQueue、globalTaskId/currentSequence/stepIndex/taskParams/state、pendingFuture（导航 future 不推进 stepIndex）、currentOpTarget/currentOpKind/stance |
| `EquipmentComponent` | `BASE_VALUES`：MAX_HP 40 / MOVE_SPEED 0.3 / SPELL_POWER 1 / WORK_SPEED 1 / SPELL_SPEED 1 / ARMOR 0；`equip/unequip/equipDefaultWand`；`recalculateAll` 按 vanilla 顺序 `effective = (base + Σ ADDITION) × (1 + Σ MULTIPLY_BASE)`（`ModifierOperation` 两枚举；铁魔法百分比加成走乘区，无乘区时退化为纯加法） |

## boundary/（8 个接口）

| 接口 | 方法 |
|---|---|
| `BlockOps` | setBlock/getBlock/isAir/toggle/activate/openGui/setBlockEntityData(pos, nbtBase64) |
| `EntityOps` | applyEffect(EntityId,EffectId,strength,duration)/getPosition/getCurrentMana(npcId)/isFollowing/isResting/isColonyActive(UUID)/isNpcAlive(npcId)（MC 实体存在且未移除，调度/执行排除幽灵 NPC）/spawnDecoration |
| `RitualOps` | beginRitual(RitualId, GridPos, World, casterId, params) → CompletableFuture |
| `MovementOps` | navigateTo(npcId,x,y,z)（future 永不异常，10s 超时传送兜底）/cancelNavigation |
| `EventBus` | emit/subscribe/unsubscribe(延迟)/record Subscription |
| `ColonyResourceAccess` | hasEnough/reserve/commit/release/available/addResource |
| `ResourceAddedListener` | onResourceAdded(ResourceId, int) |
| `ResourceShortageHandler` | handle(ResourceId, int, GridPos) → boolean |

## event/

- `SimpleEventBus`：emit 入队，tick 末 `dispatch()` 批量派发 + 延迟退订。
- 事件类型：`CustomEvent(name, params)`、`NarrativeEventTriggered(NarrativeEvent)`、`TaskCompleted(taskId, completedByNpcId)`。

## types/

- `AttributeType`：6 值 **MAX_HP/MOVE_SPEED/SPELL_POWER/WORK_SPEED/SPELL_SPEED/ARMOR_VALUE**。
- `AttributeModifier(AttributeType, float, ModifierOperation)`；`ModifierOperation` 仅 `ADDITION`。
- `EquipmentSlot`：仅 `WAND`。
- `GridPos(x,y,z)`：manhattanTo/distSq。
- `ResourceId(String)`：常量 STONE_BRICKS/GLASS/IRON_INGOT/WOOD/STONE/DIRT/WHEAT/MAGIC_CRYSTAL；`getFuckPureResourceId_NotContainFuckedNBT()` 正则剥 `[...]`。
- `ResourceStack(ResourceId, int)`：负数量抛异常。
- `BlockType`：AIR/STONE/DIRT/GLASS/STONE_BRICKS/OAK_PLANKS/BOOKSHELF/IRON_ORE。
- `EffectId`：DAMAGE/HEAL/FOLLOW/SIT/BUFF。
- `EntityId(long)`：NONE = -1。
- `EquipmentPreset(id, displayName, slot, modifiers, color)`。
- `InteractAction`：TOGGLE/ACTIVATE/OPEN_GUI。
- `RitualId`：8 个（含 SELF_TELEPORT/RAIN_CALL 等）。
- `NpcAttributes`：6 个 float + `defaults()` = (40, 0.3, 1, 1, 1, 0)。

## 与其他模块关系

- 边界接口由 `engine/boundary/` 实现。
- `World` 由 `WandscapeEngine` 持有，`Wandscape.onServerTick` 驱动 `world.tick()`。
- `CustomEvent`（如 `build_complete`、`road_segment_complete`）是模块间流程通知的关键机制。
