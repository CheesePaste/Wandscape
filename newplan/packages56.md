# packages56.md — 批 5（task/op/magic）+ 批 6（warehouse/shared）认知地图

> 本文件是 tier0（`newplan/tier0.md`）的**批 5、6 产物**，单独成文（避开与其它并行 AI 在 packages.md 上的并发冲突）。
> 只写支撑「content/ 分包 + 合并判断」的要点，**记要点不记详情**；填不出的标「未探明」。同概念全地图只定义一次，其它节交叉引用。
> 归属已并入文末结论表（此表为批 5、6 之行的子集；全表待所有批完成后由汇总方合并）。

---

# 批 5 任务/施法域（task op magic）

## task（任务自动化引擎）
- **职责**：殖民地自动化的大脑——蓝图 DSL 描述「NPC 依序执行的一串原子操作」，中央任务池照优先级/审批/资源等待/触发订阅持有全局任务，调度器把任务派给空闲 NPC，执行系统逐 NPC 驱动其操作序列（导航、异步 future、资源短缺、吞并/恢复）。建造/采集/合成/守卫都靠它把「蓝图 → NPC 干活」串起来。
- **改它先看**：`task/engine/pool/GlobalTaskPool`（任务生命周期唯一入口：addTask/assignLight/completeTask/cancelTask）、`task/scheduler/TaskExecutionSystem`（逐 NPC 驱动 op 序列）、`task/engine/dsl/BlueprintInterpreter`（DSL 解析）。
- **数据流**：来源(task/source: 事件驱动/玩家手输/工作台) → `TaskRequest` → GlobalTaskPool(编译蓝图→GlobalTask, 按 priority 入 assignableSet) → `SchedulerSystem`(打分派给空闲 NPC) → 入 NPC 的 NpcTaskQueue → TaskExecutionSystem 逐 op 查 OpExecutor 执行 → 调 core 边界(blockOps/movementOps/colonyResources) → 完成/告警。建筑侧经 `BuildingTaskPool` 保证每建筑只队头任务进全局池。
- **依赖**：重度 `core.*`（ecs/component/boundary/types/event）+ `op.api.AtomicOp` + `op.executor` + `shared.log`。**不 import 任何 net.minecraft / net.neoforged**。纯逻辑可单测（已有 BlueprintInterpreterTest/GlobalTaskPoolTest/TaskExecution 系列/Scheduler 系列）。执行器实体由 engine.boundary/guard/building 提供。
- **坑/旧文档矛盾**：① task/runtime `InterruptRecord(long npcId, timestamp, atStepIndex)` 是**活的**（GlobalTask.interruptHistory 用）；死的是 `shared/data/InterruptRecord(UUID, timestamp)`（批 6）。同名双 record 别删错。② task 是 core 边界（ECS/组件）最重消费方之一，拆桥层时任务引擎要连带处理。
- **归属**：独立 content 域 `task`（自动化引擎）。域内已按功能块切 dsl/pool/scheduler/source/runtime——符合「域内按功能块切」目标。core 拆除后它直接依赖 foundation + 各 content 域。

## op（原子操作执行框架）
- **职责**：TaskExecutionSystem 驱动 NPC 看的「原子操作词汇表」——sealed `AtomicOp` 层级（TransformOp/BlockInteractOp/RitualOp/AltarCastOp/ResourceRequestOp/AttackMonsterOp/SelfDefenseOp/ParallelOp/IfConditionOp/EmitEventOp/EntityInteractOp/SpawnDecorationOp… 12 变体）+ `OpExecutorRegistry`（op 类→执行器）+ 默认执行器 `DefaultOpExecutors` + `ConditionEvaluator`。它是任务引擎与世界的接口层。
- **改它先看**：`op/api/AtomicOp`（sealed 接口）、`op/executor/OpExecutorRegistry`。
- **数据流**：TaskExecutionSystem 从 TaskSequence 读 AtomicOp → 按 op.getClass() 查 OpExecutorRegistry → execute(op, world, npcId) → 执行器调 core 边界（blockOps.setBlock/colonyResources）与 ECS 组件（Inventory/Position）。
- **依赖**：`core.types`（GridPos/BlockType/ResourceStack/RitualId）、`core.boundary`、`core.component`、`core.ecs.World`、`core.event.CustomEvent`、`core.TemplateResolver`。**不 import net.minecraft**。
- **坑/旧文档矛盾**：① **执行器分散**——op 自带同步默认(DefaultOpExecutors)，但异步/真实现散在 engine/boundary(AsyncTransformExecutor/WandscapeBlockInteractExecutor/WandscapeRitualOps/ResourceRequestExecutor)、guard(GuardAttackExecutor/SelfDefenseExecutor)、building(AltarCastExecutor)。同一 op 的「默认同步 vs 异步真实现」+「跨域实现」是重复/镜像源，重构应把执行器收敛回 op（或 task）。② 消费方不止 task：guard/building/engine.boundary 也 import op——但都是把这些 op 注入 NPC 队列，属跨包直接调用，正常。
- **归属**：并入 `task`（作 task/op 或 task/atomic）。op 只有在「任务引擎执行上下文」下才有意义；guard/building 引用它是跨域调 task 的执行词汇，可接受。纯逻辑零 MC，可单测层。

## magic（施法域）
- **职责**：魔法系统——法术定义(MagicDef 自 magic_spells/*.json)、施法决策脑(CastBrain)、实际施放(MagicCaster: 法阵+光束→MagicCastManager 编排)、魔文书加载(SpellbookLoader)、效果执行器(MagicSpellExecutors: 治疗/陨石/石化 + WandscapeEffects)、事件处理(MagicEventHandler)、法术物品(SpellItem)、客户端渲染(MagicCircleEmitter/MagicBeamEntityRenderer/MagicCircleDotParticle)。供守卫/自防御/祭坛/玩家施法使用。
- **改它先看**：`magic/internal/MagicCaster`（施放入口）、`magic/internal/CastBrain`（决策）、`magic/internal/SpellbookLoader`（数据加载）、`magic/internal/MagicCastManager`（光束调度）。
- **数据流**：GuardCombat/AltarCastExecutor → `CastBrain.select`（已知魔法 + 施放门控 + 世界快照 → 选魔法）→ MagicCaster.castNpcAt（法阵→MagicCircleCastPacket 客户端渲染；光束→MagicCastManager.schedule→MagicBeamEntity 服务器跟踪）→ 渲染器。NPC 的 `EquippedMagicComponent` + `CastStrategyComponent` 驱动 knownSpells/resolvePriority。
- **依赖**：`core.component`（EquippedMagicComponent/CastStrategyComponent/CastStrategy）、`npc.entity.WandscapeNpc`、`compat.ironspellbooks`、`shared.registry.WandscapeConstants`、`shared.log`、`shared.network.MagicCircleCastPacket`，**大量 net.minecraft**（ServerLevel/LivingEntity/Vec3/MobEffect）。MC 重（与 task/op 正相反）。
- **坑/旧文档矛盾**：① **plan.md 说「CastBrain 死代码」是错的**——CastBrain 是活的：被 `guard/executor/GuardCombat`（L1 选魔法）与 `magic/internal/SpellcastingApiImpl` 调用，且 CastBrainTest 有 30+ 断言。误删会废掉守卫施法决策。② **「magic 接收 element」初始假设为假**——magic 不消费经济元素（耗的是魔力/冷却），grep 全 magic 包无 `shared.data.ElementType`。③ 命名撞车：magic 的 `Element`/`ElementType`（法阵绘图形状 RING/ARC/POLYGON/STAR/GLYPH，见 MagicCircleSpec）与经济 `shared/data/ElementType` 同名但**完全无关**，别合并、别混淆判定。
- **归属**：独立 content 域 `magic`。施法系统（CastBrain/Caster/Beam 编排）留 magic；下属 `magic/item/SpellItem` 等纯物品位按 plan「物品并入 items」处理，但施法系统本体独立。下游 npc/guard/生产驱动它，是纯功能域。

---

# 批 6 仓储/公共数据域（warehouse shared）

## warehouse（殖民地仓储）
- **职责**：殖民地经济存储枢纽——「仓库是终端、银行是真相」。`ColonyItemBank`(SavedData) 是按 level 持久化的物品+元素银行（物品/元素分存、In-memory 预约 reservation、元素种子一次性发放、引导期玩家行为计数器）；`WarehouseManager` 同时实现 `WarehouseApi`（对外查询存取）与 `ColonyResourceAccess`（core 边界供任务系统取料）；`WarehouseMenu`/`WarehouseScreen` 是双标签 GUI（Overview 元素+可搜物品列表；Exchange 原版 6 行箱存物）；`WarehouseTerminalItem` 是便携终端（右键开自己殖民地仓库，不绑建筑）。
- **改它先看**：`warehouse/ColonyItemBank`（数据真相）、`warehouse/WarehouseManager`（API+core 边界实现）、`warehouse/WarehouseMenu`（菜单）。
- **数据流**：上游——玩家/tourist/建筑/任务系统经 `WarehouseManager`(WarehouseApi) 或 core 边界 `ColonyResourceAccess` 存/取/预约物品与元素；银行变更发 `WarehouseElementChangedEvent`/`WarehouseItemChangedEvent`/`ElementBalanceChangedEvent`(NeoForge)。下游——任务系统 ResourceRequestOp、合成/分解/补给引擎、游客采购都从它取料。核心任务引擎 AWAITING_RESOURCES 任务靠 `ResourceAddedListener` 唤醒。
- **依赖**：`core.boundary.ColonyResourceAccess` + `core.types.ResourceId`（它是 core 边界的 MC 适配器）、`shared.data.ElementType`/`ItemKey`、`shared.event.*`、`shared.registry.WandscapeApis`、`engine.service.SoundService`/`engine.sound.WandscapeSounds`（engine 层，在拆）、`shared.ui.vanilla.ToggleableSlot`/`VanillaPlayerInventory`、大量 net.minecraft。
- **坑/旧文档矛盾**：① warehouse 是 **core 边界 `ColonyResourceAccess` 的唯一实现方**——任务系统经它取料；Tier 4 边界内联时它是最重的适配器之一，移动前后要保住「资源存取语义不变」。② `ResourceId.getFuckPureResourceId_NotContainFuckedNBT()`——改名残留带脏话的临时方法（在 WarehouseManager），重构顺手清。③ `WarehouseMenu` 直接 import `engine.service`/`engine.sound`，拆 engine 时菜单调用点随功能域走。
- **归属**：独立 content 域 `warehouse`（经济存储域）。同时扮「对外契约(WarehouseApi) + 核心边界适配(ColonyResourceAccess) + 双标签 GUI」，纯功能域，符合 content/ 形态。

## shared（公共/桥层·待拆）
- **职责**：重构要拆的「桥层」之一（旧架构为「互不直接引用」搭的桥），但**混着三类**：真公共基建（log/网络包/DTO/公共 UI 控件/markdown 栈）、纯功能实现（各面板/overlay/具体 Overlay、API 内部实现）、搭桥/死码。判定「该拆哪些进 foundation」的对象。
- **改它先看**：`shared/log/Log`（公共日志过滤入口）、`shared/registry/WandscapeApis`（静态注册表、API 面收敛处）、`shared/network`（全局网络包层）。
- **数据流**：`shared/log` 全模组日志过滤；`shared/event` 收 NeoForge/custom 事件供跨系统响应；`shared/network` 服务器↔客户端包收发；`shared/ui` 渲染公共控件、被各域 Screen/Overlay 用；`shared/data` 纯 DTO 与配置 record；`shared/api` 各 XxxApi 接口给跨域/第三方调。
- **依赖**：跨功能域。拆法判定靠引用计数：低层真公共（log/network 基类/公共控件/theme/markdown 栈/真 DTO）→ foundation；具体功能面板/overlay/API 内部实现 → 随各自功能域；0-1 引用搭桥/死码 → 内联或删。
- **坑/旧文档矛盾**：① `shared/data/InterruptRecord(UUID,timestamp)` 是**死码**（批 5 已证 active 的是 task/runtime/InterruptRecord）。② `shared/registry/WandscapeApis` 是静态注册表 + 14 套 get/set 样板（plan.md 要瘦身迁进 api/）。③ shared 里有 `MageHutAttributes`/`MageAttributeRoller`/`MageResume`/`MageHutResident`——NPC 属性定义的其中一处（plan 说「NPC 属性五处定义」，散在 core/types/`NpcAttributes`(死) 等多处），须收敛到 npc 域一处。④ `shared/ui/panel/TaskManagementOverlay`(1094)/`WandscapePanelController`(716)/`shared/ui/component/TaskQueuePanel`(596)/`WandscapePanelState`(536) 是 **UI 堆积**主犯——具体功能面板随功能域走，不进 foundation。⑤ `shared/ui/markdown`（MarkdownParser/GifDecoder/MarkdownRenderWidget/… ~13 文件）是一整个独立子栈，将来可独立成库，归属 foundation/ui。⑥ `shared/client/bubble` + `shared/client/render` 是具体渲染功能（气泡/建筑 ghost），随功能域。
- **归属**：拆散。→ foundation：`shared/log`、`shared/network`（基类+全局包）、`shared/ui`（公共控件+theme+markdown 栈+skin+animation）、`shared/registry`（WandscapeConstants 等常量）、`shared/data`（真 DTO/record）。→ 随功能域：`shared/ui/panel`+`shared/ui/component`（具体功能 Overlay/Panel/TaskQueuePanel）+`shared/ui/guidance`/`guide`+`shared/client`（bubble/render）+`shared/ui/util`（BuildingPreview 等）。→ 内联：`shared/api` 各 XxxApi（内部 use >80% 的删接口、消费方直连实现类，只留 addon 真公开面）。→ 保留/删：`shared/event` 真事件流保留、假事件内联；`shared/data/InterruptRecord` 等死码删。

---

# 结论表（批 5、6 行；待全批完成后由汇总方并入全表）

| 顶层包 | 归属 | 一句话依据 |
|--------|------|-----------|
| `task` | 独立 content 域 | 自动化引擎，纯逻辑零 MC 可单测，域内已按功能块切 |
| `op` | 并入 `task`（作 task/op 或 task/atomic） | 执行词汇只有任务引擎语境下有意义；但执行器分散在 op/building/guard/engine.boundary，须收敛 |
| `magic` | 独立 content 域 | 施法系统(CastBrain/Caster/Beam)；不接经济元素；CastBrain 非死码 |
| `warehouse` | 独立 content 域 | 经济存储；兼 WarehouseApi 契约+ColonyResourceAccess 核心边界适配+双标签 GUI |
| `shared` | 拆散：log/network/ui(控件+markdown)/registry/data→foundation；面板/overlay/client/api 实现→随域或内联；死码删 | 桥层，混真公共基建+纯功能实现+搭桥死码；判定靠引用计数 |
