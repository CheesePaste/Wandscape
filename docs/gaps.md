# 已知问题与待澄清

## 设计缺陷

### GlobalTaskPool 内存泄漏
tasks Map 不清理 COMPLETED 任务。100+ 任务后内存持续增长。建议：定时清理或上限策略。

### 祭坛多方块检测跑在 tick()
当前设计每 tick 校验整个多方块区域。应缓存完整性状态，仅在方块放置/破坏时重检。注：祭坛模块尚未构建，此问题在设计中而非代码中。

### 连续执行加成硬编码
SchedulerSystem 中 `score += 50` 是 magic number。应移至 TOML 全局配置（Config.java 已有 `sameBuildingContinuationBonus` 但代码未使用）。

### 殖民地删除未实现
殖民地系统未构建。删除时需清理：BuildingSavedData(建筑记录) + RoadSavedData(路网) + ColonyItemBank(物品) + NPC的ColonyMember组件。世界中方块为原版方块（stone_bricks等），无需特殊处理——它们就是普通方块。注意：项目已无自定义建筑 BE，不要引入 BE 方案。

## 代码问题（2026-06-22 代码审查发现）

### AtomicStep 与 AtomicOp 两套并行类型
`shared/data/AtomicStep.java`（4变体：OperationA/B/C/D）是旧设计。引擎实际用 `core/op/AtomicOp.java`（7变体）。AtomicStep 未被引擎使用但保留在 shared 层，增加混淆。

### WandscapeConstants 与 Config 值重复
`WandscapeConstants.java` 硬编码默认值（SCHEDULER_HEARTBEAT_TICKS=40 等），`Config.java` 定义相同的 TOML 可配值。两者的优先级关系无文档说明。

### 5 个 API 接口无实现
WandscapeApis 中 ColonyApi、HouseApi、ManaPoolApi、TavernApi、AtomicExecutor 的 getter 永远抛 "not loaded"。要么移除，要么标注为预留。

~~TaskApi — 已实现 (2026-06-22)，`task/internal/TaskApiImpl` + GUI 任务编辑器。~~

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

### 当前状态

**有所缓解但未修复。**

保留的 `addLoadedTask()` 去重逻辑修复了 Layer 1（世界重进时同一任务不会出现两次），游戏内新发布任务暂未出现多人共用一个工作站的明显现象。但 Layer 2/3 的根因未解决，以下问题仍可能存在：

1. 世界重进后，若工作站队列中有**多个不同任务**，仍会在加载后的一轮心跳内全部分配给不同 NPC
2. `BuildingTaskSource.poll()` 未修复，正常游戏中如果 poll 间隔与 Scheduler 心跳配合不当，理论上仍可能同时发布多个任务
3. `SchedulerSystem` 循环内的快照竞争问题仍存在，可能导致同一任务被分配给多个 NPC（其他触发路径，如 EventDrivenTaskSource）

### 后续排查方向

1. 加测试验证 `addLoadedTask()` 去重是否真的消除了双键问题
2. 用 debug 日志跟踪 `BuildingTaskSource.poll()` 发布时机与 Scheduler 心跳的对应关系，确认 Layer 3 是否真的被 Layer 1 修复"顺便"覆盖
3. 如果 Layer 2/3 问题确实还存在，需要更细粒度的修复（如仅在 BuildingTaskSource 发布路径加独占，而不是全面拦截）

## 后续待办
- 多人游戏同步（底层模型已兼容，需网络包+权限UI）
- 性能压测（100+ NPC、50+ 建筑场景）
- 区块加载保证（NPC 执行任务时确保目标区块已加载）
- JSON 版本迁移（格式变更时的自动迁移）
- 进度/指南书（Patchouli 或自定义）
- **道路拆除 NPC 化**：RoadEditorHandler.removeEdge 当前即时 server-side setBlock(AIR)。应改为发布 demolition 任务到 GlobalTaskPool，让 NPC 逐块拆除。RoadEdge.placedBlocks 已记录所有位置，拆解工作已就绪。

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

