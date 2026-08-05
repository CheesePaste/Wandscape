# 已知问题与待澄清

## 已完成的特性（2026-08-01）

### Overview 建筑信息顶栏闪烁修复 ✅
- **问题**：俯瞰模式下移动时准心扫过建筑，顶栏/建筑信息反复闪烁。根因是 `BuildingDebugController` 每 tick 射线检测，只要准心扫过不同方块就发 `BuildingDebugRequestPacket`（限速 200ms）并立即 `clearCachedData()`，服务器响应到达前缓存为 null → 顶栏闪现；命中非建筑方块时服务器不回包，缓存一直空。
- **修复——本地检测 + 防抖 + 按建筑发包**：
  1. `BuildingAreaSyncPacket.findBuildingIdAt(BlockPos)`：共享边界查询，从 `OverviewFlightController.findBuildingAt` 提取为单一来源。
  2. `BuildingDebugController` 每 tick 用本地建筑区域缓存做即时检测 → `markBuildingDetected()` 刷新防抖窗（顶栏/建筑信息切换零网络、零闪烁）；仅当本地建筑 UUID 变化才发包（同一建筑扫过多块只发 1 包，发包量从 ~5/s 降到接近 0）。
  3. `BuildingDebugClientState` 新增 250ms（5 tick）防抖窗 `SHOW_GRACE_MS` + `getDisplayData()`/`debouncedClear()`：准心离开建筑后最多 0.25s 内仍显示建筑信息，随后回退顶栏。
  4. 消费点 `WandscapePanelOverlay`/`BuildingDebugOverlay`/`WandscapeHighlightRenderer` 改用 `getDisplayData()`。
- **相关文件**：`BuildingAreaSyncPacket.java`, `BuildingDebugController.java`, `BuildingDebugClientState.java`, `BuildingDebugOverlay.java`, `WandscapePanelOverlay.java`, `WandscapeHighlightRenderer.java`, `OverviewFlightController.java`

## 已完成的特性（2026-07-08）

### 市政厅殖民地名称 + Overview 交互统一 ✅
- **Overview 模式点击市政厅走 default**：`OverviewInteractPacket` 原先自己维护了一套重复的 `interactWithBuilding()` 分发逻辑，缺少 town_hall 的 typeId 检查。每新增建筑类型两个地方都要同步改。
- **修复——统一交互分发**：`BuildingInteractHandler` 抽取 `handleInteraction(ServerPlayer, Level, BlockPos, BuildingState)` 公共静态方法，包含所有建筑类型的分类逻辑。`onRightClickBlock`（普通模式）和 `OverviewInteractPacket`（俯瞰模式）都调用同一个方法。新增建筑类型只需改一处。
- **殖民地命名**：`ColonyLevelData.Record` 新增 `name` 字段（String），NBT 持久化。面板顶栏显示殖民地名称而非 UUID。`TownHallScreen` 增加名称编辑框（EditBox），修改即发送 `ColonyNameUpdatePacket`（C→S）持久化。
- **相关文件**：`BuildingInteractHandler.java`, `OverviewInteractPacket.java`, `ColonyLevelData.java`, `ColonyLevelManager.java`, `ColonyNameUpdatePacket.java`（新建）, `TownHallOpenPacket.java`, `TownHallScreen.java`, `ColonyStatsSyncPacket.java`, `WandscapePanelState.java`, `WandscapePanelOverlay.java`, `PanelStateTogglePacket.java`, `PanelStateTracker.java`, `WandscapeClient.java`, `Wandscape.java`

## 已完成的特性（2026-06-25）

### 智能资源调度级联 ✅
- **问题**：建筑建造时缺少方块 → 仅创建 gather 任务（如 `gather:stone_bricks`），但合成品无法被采集，建筑任务永久卡在 `AWAITING_RESOURCES`。
- **方案**：三级调度级联——建筑缺方块 → 发布合成任务 → 合成缺元素 → 发布采集任务。
- **实现**：
  1. 新建 `core/boundary/ResourceShortageHandler` 函数式接口，引擎层注入合成判断逻辑。
  2. `EventDrivenTaskSource.onTaskAwaitingResources()` 先调 handler，handler 返回 true（已创建合成任务）则跳过 gather。
  3. `EngineBootstrap` 生产环境实例化 `EventDrivenTaskSource` + 注入 handler（查 synthesize recipe → 找 crafting station → enqueue WorkItem）。
  4. `WandscapeBlockInteractExecutor` 中 synthesize/craft_wand/brew_potion/decompose 的元素/物品不足时抛 `ResourceShortageException`，thenRun 中捕获后转换任务为 `AWAITING_RESOURCES`，触发级联。
- **相关文件**：`ResourceShortageHandler.java`, `EventDrivenTaskSource.java`, `EngineBootstrap.java`, `WandscapeBlockInteractExecutor.java`

## 已完成的特性（2026-06-23）

### 数据驱动法杖需求 + 立即失败 ✅
- **统一 `wand_level` JSON 字段**：节点 `node_config.wand_level` 和配方 `wand_level` 共用 `{"gathering": 1, "building": 0}` 格式。缺省=deriver 默认值，0=移除需求，≥1=覆盖等级。
- **传递链**：JSON → NodeConfig/Recipe.wandLevel (Map<String,Integer>) → BehaviourTag.fromKey() → WorkItem.wandRequirementOverrides → TaskRequest → GlobalTaskPool.mergeOverrides() → GlobalTask.requirements。
- **TaskState.FAILED**：终态保留（NBT 兼容），但系统不再生成 FAILED 任务。资源短缺走 AWAITING_RESOURCES 路径自动恢复。
- **BehaviourTag.fromKey()**：JSON key ↔ enum 双向映射，与 WandProvisionSystem.mapToNbtKey() 互为逆映射。
- **TaskFailureReason**：已删除（空接口，无实现）。failTask() 从未被调用。
- **FailureAnalyzerSystem → ResourceSupplySystem**：原 AWAITING_RESOURCES 轮询被 ResourceSupplySystem 替代。新系统扫描 AWAITING_RESOURCES 任务，聚合需求 → 合成/采集，与事件驱动的 onResourceAdded 互补。

### 殖民地三值评估系统 ✅
- **BuildingContributionRegistry**：per-colony per-buildingType 的 intactCount 缓存。0↔1 边界跨越时广播 `ColonyEvaluationChangedEvent`。
- **BuildingSavedData.add/removeBuildingContribution**：build complete → `addBuildingContribution` → 0→1 广播事件；结构损坏/拆除 → `removeBuildingContribution` → 1→0 广播事件。load() 后 rebuildFrom() 全量重建兼容旧存档。
- **BuildingApi.getColonyComfort/Magic/Wonder**：修复原实现的两个 bug——（1）按 `structureIntact=true` 而非 `shutdown=false` 过滤，损毁后贡献正确扣减；（2）per-type 二进制贡献，同类型多栋不叠加。
- 事件流：`ColonyEvaluationChangedEvent(colonyId, oldC, newC, oldM, newM, oldW, newW)` → 订阅者可据此触发建筑解锁、酒馆任务属性调整、新法杖道具解锁等。

### 生产配方三值解锁 ✅
- **RecipeUnlockRequirement**：三字段（minComfort/minMagic/minWonder）。填 0 表示无要求，统一格式，无 legacy 分支。
- **RecipeUnlockChecker.isUnlocked(colonyId, req)**：查询 BuildingApi 三值 → 全满足返回 true。服务端二次验证防篡改。
- **SynthesizeRecipe / CraftWandRecipe / BrewPotionRecipe**：`unlockMagicValue` 字段已迁移为 `unlock_requirement`（JSON 格式统一）。
- **WorkstationDataPacket / CraftingStationPacket**：`from()` 内过滤未解锁配方，不发送到客户端。`unlock_requirement` NBT 随配方下发供客户端锁因展示。
- **RequestProductionTaskPacket**：服务端二次检查，客户端篡改 recipeOrItemId 会被拒绝。
- **BuildingUnlockChecker**：建筑右键提示展示锁因，`default` 分支显示解锁需求。


## 设计缺陷

### GlobalTaskPool 内存泄漏
tasks Map 不清理 COMPLETED 任务。100+ 任务后内存持续增长。建议：定时清理或上限策略。

### 祭坛多方块检测跑在 tick()
当前设计每 tick 校验整个多方块区域。应缓存完整性状态，仅在方块放置/破坏时重检。注：祭坛模块尚未构建，此问题在设计中而非代码中。

### 连续执行加成硬编码
SchedulerSystem 中 `score += 50` 是 magic number。应移至 TOML 全局配置（Config.java 已有 `sameBuildingContinuationBonus` 但代码未使用）。

### BuildingApiImpl null 安全 bug（已修复 2026-06-23）
`getBuildingsWithPendingWork()` 和 `getBuildingsByCategory()` 使用 `colonyId.equals(state.getColonyId())`，当 `state.getColonyId()` 为 null 时抛 NPE，导致建筑任务轮询静默返回空列表，`buildings_with_work=0` 永久卡死。修复：改用 `java.util.Objects.equals(colonyId, state.getColonyId())`。

### 殖民地冷启动能力死锁（已修复 2026-06-23）
默认 NPC 仅有 `BUILDING: 1` 能力。修复方案不是发布前能力检查 + 聊天通知，而是从根源消除死锁：
1. `WandRequirementDeriver.deriveFromAction("craft_wand")` → `Map.of()`（法杖制作不需要法杖能力）
2. `WandRequirementDeriver.deriveFromAction("gather")` → `Map.of()`（基础节点采集不需要法杖能力）
3. 这些 level 0 任务通过 `satisfies()` 时 `requirements.isEmpty()` → `true`，任何 NPC 均可接取
4. 更高级的操作（decompose/synthesize/brew_potion/ritual）仍保留法杖需求，调度器在 NPC 能力不足时保持 PENDING_ASSIGN 等待玩家制作法杖


### 法杖制作完成后无反馈（待补）
`craft_wand` / `synthesize` 完成后不发送任何事件或通知。玩家不知道法杖已进入仓库或物品已产出。建议：完成时发送 `ProductionCompleteEvent`，GUI 可显示 Toast 提示。

### 殖民地删除未实现
殖民地系统未构建。删除时需清理：BuildingSavedData(建筑记录) + RoadSavedData(路网) + ColonyItemBank(物品) + NPC的ColonyMember组件。世界中方块为原版方块（stone_bricks等），无需特殊处理——它们就是普通方块。注意：项目已无自定义建筑 BE，不要引入 BE 方案。

## 代码问题（2026-06-22 代码审查发现）

### AtomicStep 与 AtomicOp 两套并行类型
`shared/data/AtomicStep.java`（4变体：OperationA/B/C/D）是旧设计。引擎实际用 `core/op/AtomicOp.java`（7变体）。AtomicStep 未被引擎使用但保留在 shared 层，增加混淆。

### WandscapeConstants 与 Config 值重复
`WandscapeConstants.java` 硬编码默认值（SCHEDULER_HEARTBEAT_TICKS=40 等），`Config.java` 定义相同的 TOML 可配值。两者的优先级关系无文档说明。

### 4 个 API 接口无实现
WandscapeApis 中 ColonyApi、HouseApi、ManaPoolApi、AtomicExecutor 的 getter 永远抛 "not loaded"。要么移除，要么标注为预留。

~~TaskApi — 已实现 (2026-06-22)~~
~~TavernApi — 部分实现 (2026-06-26)：mage resume 相关方法已可用；3 个 NPC 招募方法仍为占位~~

### PLACEHOLDER_COLONY 零 UUID
EntityComponentBridge 使用全零 UUID 作为占位殖民地，注释标记"阶段2占位"。殖民地系统完成后需替换。

### BuildingSavedData posIndex 重建不完整 ✅ 已修复 (2026-06-21)
~~从 NBT 加载时 posIndex 无法完全重建（需要 BuildingConfig pattern）。~~ 已在 `getBuildingIdAt()` 中添加 chunkIndex fallback：posIndex miss 时遍历同区块建筑，用 `BoundingBox.isInside()` 匹配并缓存到 posIndex。重进游戏后所有建筑右键正常。

### 元素物品存储混用 ✅ 已修复 (2026-06-22)
~~WarehouseManager 通过 ELEMENT_TO_BLOCK 把元素映射为 MC 物品（WOOD→oak_log, EARTH→dirt），导致节点产出物理方块而非元素、分解/合成/法杖制作走物品检查。~~ 已在 ColonyItemBank 中新增独立 `elementStorage`（Map<UUID, Map<ElementType, Long>>），元素和物品存储完全分离。节点采集注入元素，decompose 消耗物品注入元素，synthesize/craft_wand 消耗元素注入物品。死代码 `ElementStore` 接口已删除。

### GlobalTaskPool.onChanged 脆弱模式
公开 Runnable 字段用于持久化脏标记，外部设置。如果创建 TaskPool 时未设置此字段，持久化静默失败。

## 法杖系统已知局限 (2026-06-22)

### 仓库无法杖时不会自动触发 craft_wand
WandProvisionSystem 找到匹配法杖→Scheduler 注入 WandEquipOp。但如果仓库中完全没有匹配法杖，当前仅 log 一条 debug，任务保持 PENDING_ASSIGN。理想行为：检测到缺法杖 → 自动 enqueue `production:craft_wand` 蓝图到 GlobalTaskPool，NPC 制作法杖 → 存入仓库 → 下一轮心跳自动分配。

### NPC 默认生成带 WandCarrier.EMPTY
`EntityComponentBridge.onNpcJoinWorld()` 以 `WandCarrier.EMPTY` 创建 ECS 实体。NPC 通过刷怪蛋生成时无任何法杖能力，必须依赖仓库中的法杖。如果殖民地没有法杖且没有 crafting_station 制作法杖，所有需要法杖的任务（建造/采集/合成/仪式）将无限期停留在 PENDING_ASSIGN。一种解决方案是给初始 NPC（如通过 town_hall 生成的第一个 NPC）默认配备 builder_wand。

### 法杖能力合并是 max-of-max 语义
如果 NPC 同时装备 builder_wand(BUILDING:1) 和 ritual_wand(RITUAL:2)，WandCarrier 的能力为 {BUILDING:1, RITUAL:2}，是两个法杖的并集。但 WandRequirementDeriver 当前推导的 level 固定为 1（除 RitualOp 外），所以这个限制目前无实际影响。

### 多人模式下法杖物品同步未测试
WandEquipExecutor 通过 `npc.setItemInHand()` 修改手持，该调用通过 EntityDataAccessor 同步到客户端。但 ColonyItemBank 的 consume/add 操作在服务端直接修改 SavedData，多人模式下的并发安全依赖 ConcurrentHashMap 和 NeoForge 的单线程服务端 tick。法杖从仓库取出→装备→归还的完整流程在多人环境下未经压力测试。

## Bug 记录：工作站任务重复分配（2026-06-22）

### 现象

- 退出世界重进后，多名 NPC 同时执行同一个工作站的多个任务
- 正常情况下（不退出），同一工作站也会出现"任务在 Scheduler 一轮心跳内分配给多个不同 NPC"的现象
- 日志表现为：`complete #132 by NPC 3` 紧接着 `complete #132 by NPC 13`（同一任务 ID 被多次 complete）

### 根因

根因共三层，逐层叠加：

**Layer 1 — 持久化加载双键（世界重进专属）**

`TaskPoolSavedData.taskFromNbt()` 的加载顺序有缺陷：先调用 `pool.addTask(request)` 给 task 分配一个临时 newId，再调用 `pool.addLoadedTask(task, originalId)` 把**同一个 GlobalTask 对象**挂在 `originalId` 键下。此时 `tasks` Map 中同一个对象有两个键：`tasks[newId] == tasks[originalId]`，`getAssignableTasks()` 遍历 `tasks.values()` 返回同一个对象两次，导致一个任务被两个 NPC 同时持有。

**Layer 2 — SchedulerSystem 快照竞争**

`getAssignableTasks()` 返回的是 `PENDING_ASSIGN` 任务列表的**新快照**，循环内 `assign()` 把 task 状态改为 `IN_PROGRESS`，但快照列表本身不更新。后续 iteration 中，若快照里还有同一任务（双键问题），或被其他路径重新放回 `PENDING_ASSIGN` 的任务，仍会被再次分配。

**Layer 3 — BuildingTaskSource 无建筑独占**

`BuildingTaskSource.poll()` 每 20 tick 遍历建筑列表，对每个建筑取出一个 WorkItem 发布到 GlobalTaskPool，但从未检查该建筑是否已有活跃任务。若一个工作站队列中有 N 个任务，一轮 poll 会同时发布 N 个，Scheduler 自由分配给不同 NPC。

### 尝试过的修复及其引入的新 Bug

| 修复 | 文件 | 意图 | 引入的新问题 | 当前状态 |
|------|------|------|-------------|---------|
| `addLoadedTask()` 去重 | GlobalTaskPool.java | Layer 1：同一对象只出现一次 | 无 | ✅ 保留 |
| `assign()` 状态守卫 | GlobalTaskPool.java | Layer 2：拒绝非 PENDING_ASSIGN 的分配 | **NPC 停止接取任务**：assign 被完全抑制 | ❌ 已回退 |
| `taskFromNbt()` 跳过已完成 | TaskPoolSavedData.java | 已完成的不要在加载时恢复 | 部分已完成任务的进度丢失 | ❌ 已回退 |
| `BuildingTaskSource` 建筑独占 | BuildingTaskSource.java | Layer 3：建筑有活跃任务则不发布新任务 | **新任务发布后也无人接取**：正常游戏流程被阻断 | ❌ 已回退 |
| `SchedulerSystem` 位置隔离 | SchedulerSystem.java | 同位置任务一次心跳只分配一次 | 目标位置为 null 的任务全部被跳过；多个不同建筑但位置相近的任务被错误合并 | ❌ 已回退 |
| `SchedulerSystem` 目标位置去重 | SchedulerSystem.java | 目标位置已被 IN_PROGRESS 任务占用时，跳过 PENDING_ASSIGN 任务 | 无 | ✅ 2026-06-25 |

### 当前状态

**全部三层已修复（2026-06-25 任务系统重构）。**

- **Layer 1**：`addLoadedTask()` 去重（双键问题）。
- **Layer 2**：`SchedulerSystem` 目标位置去重 + TreeSet-backed assignableSet 保证同一任务只出现一次。
- **Layer 3**：`BuildingTaskPool` 建筑独占——每建筑只有 head task 进入 GlobalTaskPool。`BuildingTaskSource.poll()` 在建筑有 head 时跳过该建筑，head 完成后 `onHeadCompleted` 自动 promote 下一个 WorkItem。彻底消除一建筑多任务同时竞争的问题。

### 任务系统重构 v2（2026-06-25 完成）

8 个阶段的重构已完成：

1. **NpcTaskPackage + NpcTaskQueue**：NPC 队列存储自包含任务包（source + sequence + stance + priority），不再存储裸 AtomicOp。支持挂起/恢复（紧急任务打断→suspensionStack）。
2. **GlobalTaskPool → TreeSet 优先级队列**：ordering=priority desc→createdAt asc→id asc。状态变更时维护 assignableSet。
3. **WandLifecycle 状态机**：IN_WAREHOUSE→RESERVED→IN_TRANSIT_TO_NPC→EQUIPPED→IN_TRANSIT_TO_WAREHOUSE。法杖预留先于装配，消除借还循环。
4. **TaskExecutionSystem 重写**：驱动 NpcTaskQueue。while 循环执行纯操作→包完成/释放。compat bridge 兼容旧 SchedulerSystem。
5. **SchedulerSystem WandLifecycle 集成**：法杖装配前检查 WandLifecycle 可用性并预留。
6. **BuildingTaskPool + BuildingTaskSource 集成**：每建筑只暴露 head task 到全局池。source.poll() 在建筑有 head 时跳过。
7. **大量删除死代码**：TaskData/TaskStatus/TaskTemplate/TaskPublishedEvent/TaskAssignedEvent/TaskInterruptedEvent 等旧类型。
8. **文档更新**：architecture/packages/core.md 和 building.md 反映新架构。

## Bug 记录：重进世界后任务被多 NPC 反复接取 —— 真正根因（2026-08-03）

### 现象

- 正常游戏内发布任务只有一个 NPC 接取；**退出世界重进**后，做到一半（IN_PROGRESS）的任务会被多个 NPC 同时接取。
- 日志表现为同一任务 id 被 Scheduler 反复分配给不同 NPC（如 `assigned #1 'Demolish...' → NPC 6`，随后 NPC 3/4/5/12），分数递减、距离递增，像「同一心跳内按分数挑剩下的人」。
- 与任务类型无关（node 采集、建造、拆除都会触发）。概率触发，且一直没真正修好——2026-06-22 的「工作站任务重复分配」记录结论是错的。

### 根因（真正的）

`TaskPoolSavedData.taskFromNbt()` 用 `pool.addTask(request)` 建任务，得到一个**临时 id**（`nextTaskId++`，从 1 开始，按存档里 HashMap 迭代顺序分配），随后 `addLoadedTask(task, originalId)` 把**同一个对象** re-key 到保存的 id。但 `GlobalTask.id` 是 **final 字段不会跟着变**，于是加载后任务的 `id` 字段 ≠ `tasksById` 的 key：

- 存档任务 id 与加载顺序对不上时（如 id={7,1}，先加载 7 → 字段 id=1、key=7；后加载 1 → 字段 id=2、key=1），前者的**字段 id=1 恰好命中后者的 key=1**。
- Scheduler 遍历 assignableSet，对幽灵对象调 `assignLight(task.id=1)` → `tasksById.get(1)` 解析到**另一个任务**，把它置为 IN_PROGRESS、`assignableSet.remove(该任务)`——但幽灵对象本身（字段 id=1、key=7）**永远留在 assignableSet**。
- 于是每个心跳幽灵对象都被再次遍历、再次把同一个底层任务分配给下一个空闲 NPC，直到没人可分配。`GlobalTask.id` 与池 key 错位也导致 `get(task.id)` / `advanceStep` / `completeTask` 等查错或查空。
- 2026-06-25 的「Layer 1 addLoadedTask 去重」只处理了 tasksById 双键，没处理 id 字段本身，所以修了个寂寞。

### 修复（2026-08-03）

- `GlobalTaskPool` 抽出 `addTaskWithId(request, id)`：直接以指定 id 建任务，`GlobalTask.id` 恒等于 `tasksById` key。`addTask(request)` 委托给它（`nextTaskId++`）。
- `taskFromNbt()` 改用 `addTaskWithId(request, originalId)`，从源头消除临时 id 错位。
- `addLoadedTask()` 幂等：同 id 旧副本先从 map/assignableSet 清掉；非 PENDING_ASSIGN 状态从 assignableSet 移除（修掉 AWAITING_RESOURCES 误入可分配集）。
- 回归测试 `reload_loadedTasksKeepOriginalId_singleOwnerPerTask`：加载非顺序 id 的任务后，任务 id 字段==池 key，且带空闲 NPC 时任务不会被抢走。

相关文件：`GlobalTaskPool.java`, `TaskPoolSavedData.java`, `CoreSystemsTest.java`。

## Bug 记录：`setColonyId` 静默吞失败 —— 殖民地分配的幽灵bug（2026-06-24）

### 现象

- `create` 后 `fill town_hall` → 建筑建成，`colonyId = null`
- 同位置第二次建 warehouse → `colonyId` 突然正常
- 第三次及之后 → 全都正常
- 退出世界重进 → 再次建造 → `colonyId = null`，回到解放前
- **无任何日志、无任何异常、无任何警告。** 纯纯的静默失败，像你那个从来不回消息的前任。

### 根因

`ColonyApiImpl.setColonyId()` 原实现是他妈的绝世烂活：

```java
// 旧代码 —— 别学，这是反面教材
private void setColonyId(BlockPos pos, @Nullable UUID colonyId) {
    BuildingSavedData sd = getSavedData();
    if (sd == null) return;
    BuildingData bd = sd.getBuildingAt(pos);  // ← 罪魁祸首
    if (bd instanceof BuildingState bs) {
        bs.setColonyId(colonyId);              // ← 永远走不到这行
        sd.setDirty();
    }
}
```

`getBuildingAt(pos)` 的查找链是个三层漏斗，每一层都能把你坑死：

1. **`posIndex.get(pos)`** — 这个 Map 只存 **anchor + pattern偏移** 的方块位置。anchor 本身？不存。除非你 pattern 里恰好有个 `(0,0,0)` 的方块，否则 anchor 坐标永远不在 posIndex 里。**第一层漏斗：90% 的建筑直接掉下去。**

2. **chunkIndex fallback** — 遍历同 chunk 的建筑，检查 `bounds.isInside(pos)`。但 anchor 不一定在 boundary 里面！boundary = `[anchor+min, anchor+max]`，如果 min 不是 `(0,0,0)`，anchor 在 boundary 外面。**第二层漏斗：你又掉下去了。**

3. **服务器重启后 posIndex 为空** — `BuildingSavedData.load()` 只重建 chunkIndex，不重建 posIndex（需要 BuildingConfig pattern，加载时没有）。**第三层漏斗：重启后你连第一层都没得掉，直接摔死。**

三层全穿 → `getBuildingAt(anchor)` 返回 `null` → `bd instanceof BuildingState` 是 `false` → **整个 `setColonyId` 方法体被跳过，colonyId 永远写不进去。** 而且没有任何日志。调用方 `assignColonyIfPossible` 和 `onBuildingIntact` 里的 `getColonyId()` 明明**查到了正确的 UUID**，但就是写不回去——查到了，塞不进，就像你明明记得密码但输入框被 disabled 了一样操蛋。

为什么 warehouse 反而正常？因为它的 anchor 碰巧落在 boundary 内部，`isInside` 返回 true。纯属撞大运，不是设计正确。

### 修复

`setColonyId` 改签名为 `setColonyId(BuildingData data, UUID colonyId)`，**直接 cast 写引用。** 调用方本来就他妈持有 `BuildingState` 引用（`assignColonyIfPossible(BuildingData data)`、`onBuildingIntact(BuildingData data)`），兜一个大圈通过 BlockPos 反查纯属脱裤子放屁。

```java
// 新代码
private void setColonyId(BuildingData data, @Nullable UUID colonyId) {
    if (data instanceof BuildingState bs) {
        bs.setColonyId(colonyId);
        BuildingSavedData sd = getSavedData();
        if (sd != null) sd.setDirty();
    }
}
```

### 教训

1. **有对象引用就别反查。** 通过坐标回查存储层等同于你拿着钥匙还去撬锁——傻逼且不可靠。
2. **静默失败是最大的恶。** 如果 `getBuildingAt` 返回 null 时打一行 `LOGGER.warn`，这 bug 分分钟抓到。不记日志的 fallback 不是防御性编程，是埋地雷。
3. **变量名是文档。** `townHalls` 里存的是殖民地原点不是 town_hall 建筑，命名诈骗协助隐藏了这个问题。已重命名为 `colonyOrigins`。

### 连带修复

- `ColonyCommand.createColony()` 删除了预注册 town_hall 的逻辑（Step 6）——那是个永远不会被建造的僵尸 BuildingState，create 只负责注册殖民地 UUID + 生成 NPC，建筑由 `fill` 命令独立触发。
- `townHalls` → `colonyOrigins`，`colonyToHall` → `colonyToOrigin`，`isColonyBlock` → `isColonyOrigin`，`townHallPos` → `origin`。

## 已完成的特性（2026-07-08）

### 游客等级与殖民地经验系统 ✅
- **问题**：游客等级随机分配（1-5），与殖民地发展脱钩；殖民地无经验/等级概念，无法驱动渐进式解锁；游客生成无周期管理。
- **方案**：三级联动——殖民地等级 → 游客等级分布 → 法师数值缩放 + 殖民地经验反哺升级。
- **殖民等级**：`ColonyLevelManager` 管理每殖民地的 level/exp，`expToNext(lvl) = (lvl+1) × 1000`。升级溢出经验继承。仅在 100% 满意度游客离开时获得经验。
- **经验贡献**：游客等级 < 殖民地等级 → 0 exp；游客等级 = 殖民地等级 → 100 exp；游客等级 > 殖民地等级 → 500 exp（Config 可配）。
- **游客等级分布**：40% colonyLevel-1，40% colonyLevel，20% colonyLevel+1。`rollTouristLevel()` 实现，下限 1。
- **生成公式**：`base(6) + colonyLevel × bonus(3)`，每日 ×0.8~1.2 随机浮动。
- **三阶段日周期**：
  - 清晨（0-1000）：重置调度。从 `onServerTick` 重构为在 0-1000 窗口内条件性清除 `scheduleDay` 标志。
  - 生成窗口（1000-13000）：`createSchedule()` 预计算当日游客数量（含等级、生成位置、目标建筑的 `PendingSpawn` 列表），`flushPendingSpawns()` 按 tick 到达逐个生成。
  - 夜晚离城（18000-24000）：满意度 <50 或 =100 → 0-1500 tick 随机延迟后离城；50-99 → 引导至旅馆。
- **法师缩放**：`TouristEntity.onAddedToLevel()` 中 `scale = 0.8 + level × 0.2`，maxMana/manaRegen/spellPower 乘以 scale。
- **相关文件**：`ColonyLevelData.java`, `ColonyLevelManager.java`, `TouristSpawnSystem.java`, `TouristEntity.java`, `Config.java`, `ColonyStatsSyncPacket.java`, `PanelStateTogglePacket.java`, `PanelStateTracker.java`, `TownHallOpenPacket.java`, `TownHallScreen.java`, `BuildingInteractHandler.java`, `WandscapeEngine.java`, `WandscapeClient.java`, `Wandscape.java`

## 后续待办

### 游客经济剩余项 (2026-06-26 核验)
- **奇观→满意度加成**：WonderEffectApplier 计算 satisfaction bonus，TouristMoveGoal 查询并应用
- **游客离开动画**：当前直接 discard()，应改为走出殖民地边界后移除
- **商店 max_stock 调整 GUI**：ShopScreen 当前仅查看库存，需增加每种货物 max_stock 的可编辑输入框
- **TavernApi NPC 招募**：3 个占位方法需实现

### 原有待办
- 多人游戏同步（底层模型已兼容，需网络包+权限UI）
- 性能压测（100+ NPC、50+ 建筑场景）
- 区块加载保证（NPC 执行任务时确保目标区块已加载）
- JSON 版本迁移（格式变更时的自动迁移）
- 进度/指南书（Patchouli 或自定义）
- **道路拆除 NPC 化**：RoadEditorHandler.removeEdge 当前即时 server-side setBlock(AIR)。应改为发布 demolition 任务到 GlobalTaskPool，让 NPC 逐块拆除。RoadEdge.placedBlocks 已记录所有位置，拆解工作已就绪。

## Overview 模式已知问题（2026-07-07）

### ✅ Build/Road 标签在俯瞰模式中可用（已修复 2026-07-07）
点击 Build/Road 标签时不再退出俯瞰模式返回地面，而是保持俯瞰摄像机运行的同时激活 Build/Road 子系统。

**状态管理**（`WandscapePanelState`）：
- `enterSubMode`：OVERVIEW→BUILD_PROJECTION/ROAD_PROJECTION 时保留俯瞰摄像机
- `exitCurrentSubMode`：BUILD_PROJECTION/ROAD_PROJECTION 退出时回到纯俯瞰
- `closePanel`：确保关闭面板时正确退出俯瞰摄像机

**Build 模式在俯瞰中**：
- `OverviewFlightController.performRaycast`：当 Build 投影激活时，同步更新 `ProjectionClientState` 的 ghost 位置和 overlap 状态
- `OverviewFlightController.handlePlace`：使用 ghost 位置（与 ProjectionRenderer 一致）发送放置包
- `ProjectionRenderer`：使用 `event.getCamera().getPosition()` 做平移，俯瞰摄像机 override 自动适配

**Road 模式在俯瞰中**：
- `RoadProjectionController.updateGhostPosition`：俯瞰模式下 ghost Y 使用射线命中位置而非玩家脚部
- `OverviewFlightController.onClientTickPost`：Road 激活时跳过右键处理（由 Road 控制器负责）
- `OverviewFlightController.onMouseScroll`：Road 激活时放行滚轮（Road 控制器处理宽度切换）

### ✅ 建筑高亮改为全包围箱 + 白色（已修复 2026-07-07）
`OverviewRenderer` 原实现仅渲染建筑包围盒顶部 1/3 线框环（橙色），已改为：
- 渲染整个包围盒的全部 12 条边（白色 #FFFFFFFF）
- 移除顶部 1/3 限制，移除颜色语义（金色/绿色/红色）

### ✅ ESC/建筑 UI 交互修复（已修复 2026-07-07）
原因为 `OverviewFlightController.onMouseButtonPre` 无条件拦截所有鼠标按钮事件，即使屏幕打开时也阻断交互。修复：
- `onMouseButtonPre`：有屏幕打开时放行，不调用 `setCanceled`
- `onMouseScroll`：有屏幕打开时放行，不拦截滚动

屏幕打开时（PauseScreen、建筑 UI 等），鼠标点击和滚动正常传递到屏幕层。

### ✅ WASD 移动流畅化（已修复 2026-07-07）
WASD 移动已从 `ClientTickEvent.Post`（20Hz tick）迁移至 `RenderLevelStageEvent.AFTER_SKY`（渲染帧率），并引入 `System.nanoTime()` 帧间隔计时实现帧率无关移动（`MOVE_SPEED_BPS=10.0` blocks/sec），消除因 tick 间隔跳跃导致的卡顿。

### 任务队列 UI (2026-06-22 已实现)

**已实现**
- BuildingApi.getQueue() / removeFromQueue() / moveUp() / moveDown()
- 网络包：TaskQueueModifyPacket (C→S) + TaskQueueDataPacket (S→C)
- UI 组件：TaskQueuePanel (shared/ui/component/)
- WorkstationScreen + CraftingStationScreen 右侧面板

**已知局限**
- 任务描述太长 → 第二轮优化：改为 [icon] + [category label] + [×quantity] 三列布局，不再依赖 summary 文本
- TaskQueuePanel 图标渲染：若 itemOrRecipeId 解析不到 MC 物品/方块（Recipe ID 无对应物品），图标位置留空，文字仍正常显示
- moveUp/moveDown 仅调用 Collections.swap，不校验相邻任务依赖关系
- 无任务导入/导出功能
- 队列刷新依赖玩家打开 Screen 时请求，非实时推送
- BuildingApiImpl 服务端无 INFO 级别日志，调试时只能靠 LOGGER.warn，已补充详细日志

### 投影模式建筑解锁 UI (2026-06-23 尝试中，未完成)

投影模式（按 V 键进入灵魂出窍）玩家用鼠标滚轮切换可选建筑类型，在世界中放置虚影方块。

**现状：Lock UI 未实现，退回原样**

尝试向 `BuildingSlot` record 加入 `minComfort/minMagic/minWonder` 三个 int 字段并走网络下发，在投影模式 HUD 覆盖层（`ProjectionHudOverlay`）渲染锁因。遇到以下问题后放弃本轮实现，回退所有 projection 客户端文件到原样：

1. **`RenderLevelStageEvent` 没有 `getGuiGraphics()`**：该事件暴露 `getPoseStack()`（`PoseStack`）和 `getCamera()`，没有 `GuiGraphics` 实例。要做 2D HUD 文字渲染需要自行构造正交投影 + 直接调用 `Font.drawInBatch`，`PoseStack` 也没有 `ortho()` 方法（只有 `mulPose(Matrix4f)`），需要手动构建一个 `Matrix4f.ortho()`，但实际编译报错 `PoseStack.ortho()` 不存在。

2. **`Font.drawInBatch` 参数类型严格**：实际方法签名为 `drawInBatch(Component, float, float, int, boolean, Matrix4f, MultiBufferSource, DisplayMode, int, int)`，`PoseStack` 不能直接传给 `Matrix4f` 参数，需要 `pose.last().pose()` 单独取 Matrix4f。但实际编译报错参数类型不匹配。

3. **HUD 覆盖层未被注册**：`ProjectionHudOverlay` 的注册调用写入 `WandscapeClient` 后，编译未通过，未能验证其实际渲染效果。

**结论**：这轮需要实现 `ProjectionHudOverlay`，并用 `RenderLevelStageEvent.getPoseStack().pushPose()/popPose()` + 手动正交投影 + `Font.drawInBatch(Component, float, float, int, boolean, Matrix4f, ...)` 完成 2D 文字渲染，但在没 IDE 实时类型检查、纯文本编辑条件下，逐个试错 MC API 签名代价过高。

**保留的服务端改动**：`BuildingSlot` record 仍保持三字段未回退（服务端 `ProjectionNetwork.getAvailableBuildings()` 调用 `BuildingSlot.fromConfig()` 仍编译），客户端相关改动已全部回退。若下次实现 HUD，服务端已就绪。


### 殖民地卸载运行：游客/守卫尚未 sim（2026-08-05）

强加载方案只覆盖"必须放置方块"的行为（建造/生产/采集/拆除）。以下仍只在区块加载时运行，属已知取舍：
- 游客：仅"经济 sim"规划中（人口/满意度/消费/补货数据推进，待与用户确认以区块判定）；物理游客只在区块加载时存在并走真实 AI，卸载时游客不移动。
- 守卫/袭击：物理任务，依赖区块加载。
- NPC 跨重启持久化：库存/私有任务队列不落盘（既有缺口，另开阶段）。

### 游客 sim 已知缺口（2026-08-05）
- 影子 spawn 实体时外观/皮肤不还原（TouristEntity 无 setSkinVariant/setAppearance，纯装饰性，暂接受）。
- 过夜统计 countOvernightStayers 只数加载实体，未含卸载影子（指标级，待补）。
- 游客 sim 为直线移动，忽略地形/碰撞；与真实 AI 的路径不同（可接受）。
