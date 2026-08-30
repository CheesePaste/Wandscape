# packages78.md — 批 7、8 节稿（core/engine + 技术层/资源杂项）

> 本文件是 tier0「可信全项目文档摸底」**批 7、批 8**的节稿。批 1–6 由并行 AI 写进 `packages.md`（同批一个 AI）；本文件与 `packages.md` 同源、同临时性，重构落定随 `newplan/` 一并删。**最终合并时把本节并入 packages.md 的全局结论表。**
> 判据：只读代码，每断言锚到真实类/方法；旧 docs 只在「坑」条出现、不作真相。
> 已按子代理深扫（读尽 core+engine 全部 88 文件）+ 本会话 grep 核实。

---

# 7 core / engine — 零 MC 运行内核 + MC 适配层（要拆的"桥层"）

- **一句话**：`{core + task + op}` 构成**零 MC 的模拟/任务运行内核**，`engine` 是它上面的 **MC 适配层**。`core` 只是这套运行内核的"框"（ECS/组件/边界/值类型），它**并不独立**——`core/ecs/World` 直接耦合 `task`/`op`。重构的真相判据：**核心不是 core↔engine 两层，而是 运行内核↔MC 一道真缝 + 一个命名误导的 core 框。**
- **改它先看**：`core/ecs/World`（运行内核的容器/god-object）、`engine/bootstrap/EngineBootstrap.bootstrap()`（唯一装配点）、`engine/WandscapeEngine`（全局静态定位器）、`core/CoreBootstrap`（构建 World 并注册 task 各 system）。
- **数据流**（装配）：`Wandscape.java:941` → `EngineBootstrap.bootstrap()`（ServerStartingEvent）→ ①建 BlueprintRegistry/List<TaskSource> ②实例化 4 个 MC 边界实现（`WandscapeBlockOps/EntityOps/RitualOps/MovementOps`）③解析 `ColonyResourceAccess`（真 `WarehouseManager` 或空 stub）④`CoreBootstrap.bootstrap(config)` → 返回 `World` ⑤接 `ResourceAddedListener`/`ResourceShortageHandler` ⑥`world.addSystem(NavigationSystem/ResourceSupplySystem)` ⑦把 World+各 executor 塞进 `WandscapeEngine` 静态字段。
- **数据流**（运行）：`Wandscape.java:1233` 每 tick `world.tick(1.0f)` → 按注册序跑 6 个 `System` → `SimpleEventBus.dispatch()` 派发排队的域事件。`Wandscape.java:1146-1207` 另逐 tick 调 8 个 executor 的 `tickAll()`。
- **依赖**：`core` 零 MC（**47 文件 / 2421 行，0 条 `import net.minecraft/com.mojang`，实测**）；`engine` 纯 MC（`BlockPos/ServerLevel/SavedData/EntityType/DeferredRegister/SoundEvent`）。只有 16/42 engine 文件 import core。

## core 子包（真实职责，含"命名误导"）
- **core/ecs（4）**：`World`、`System`(`@FunctionalInterface update(World,delta)`)、`ComponentStore`、`HashMapComponentStore`。
- **core/component（11）**：`Position` `TaskExecutor` `Inventory` `ColonyMember` `ColonyMetadata` `NavigationState`（**这 6 个是真·ECS 组件**，在 `CoreBootstrap.bootstrap()` 注册为 store，被 world.get/query 读）+ `MagicState` `EquippedMagicComponent` `CastStrategyComponent`（**不是 ECS 组件**，是 `WandscapeNpc` 直接持有的普通 Java 状态，经 NBT 持久化）+ `NpcTaskQueue`(藏在 `TaskExecutor.npcQueue` 字段里)+ `SuspensionContext`(纯内部 record)。
- **core/types（17）**：值类型。**只见 `EquipmentPreset` 全仓 0 引用（成死码）**；其余 `GridPos/ResourceStack/ResourceId/AttributeType(22 文件)/AttributeModifier/BlockType/EffectId/EntityId/RitualId/EquipmentSlot/InteractAction/ModifierOperation/FriendlyForce/FollowAttackDecision/HostileMarkDecision` 全活。⚠️ **`NpcAttributes` 不是死码**（被 `CoreBootstrap`、`npc/EntityComponentBridge.defaults()`、~9 个测试引用）——plan/status 的"NpcAttributes 0 引用死码"cite 已过期。注：`FriendlyForce/FollowAttackDecision/HostileMarkDecision` 各带单测，是"纯逻辑可测"的红线样本。
- **core/boundary（8）**：`BlockOps` `EntityOps` `MovementOps` `RitualOps` `EventBus` `ColonyResourceAccess` `ResourceAddedListener` `ResourceShortageHandler`。**全 8 个都活、且消费方多在 core+engine 之外**（走 `world.blockOps...` 访问，不是 import 接口，所以"import 计数"误判成单次间接——见坑 2）。
- **core/event（4）**：`CustomEvent` `SimpleEventBus` `NarrativeEventTriggered` `TaskCompleted`。**EventBus 是运行时宽的域事件总线**，订阅方在 building/road/task（`BuildCompleteListener`/`DemolishCompleteListener`/`RoadSegmentListener`/`SystemBlueprintRegistry`/`GlobalTaskPool`），不是 core↔engine 缝。
- **core 根**：`CoreBootstrap`、`CoreBootstrapConfig`、`TemplateResolver`(活)。※ `core` 根 + core/boundary 里的 `BlockOps/EntityOps/MovementOps/RitualOps/EventBus/ColonyResourceAccess` 等与 `core/boundary/` **同名同源**（见坑 1）。

## engine 子包（真实职责）
- **engine/boundary（9）**：4 个核心边界实现 `WandscapeBlockOps/EntityOps/RitualOps/MovementOps`（**这是仅有的真正 MC↔内核适配点**）+ 3 个 `op.api.OpExecutor` 适配器 `AsyncTransformExecutor`/`WandscapeBlockInteractExecutor`/`ResourceRequestExecutor`（它们实现的是 **op** 的接口，不 import core）+ 2 个不 import core 的工具 `BuildPlacementGuard`/`ProductionEligibility`。**→ "engine/boundary" 作为目录名是误导**：只混了 4 个真适配器 + 3 个 op 适配器 + 2 个无关工具。
- **engine/bootstrap（1）**：`EngineBootstrap.bootstrap()`（唯一装配点）。
- **engine/colony（4）**：`ColonyLevelManager`（`ColonyLevelData` 旁）、`ColonyActivation`、`ColonySavedData` —— level/经验 + 激活/离线倍率 + 存档。
- **engine/nav（3）**：`WandscapeNavigation`（`WandscapeNodeEvaluator`、`RoadWalkPlanner` 旁）—— 自定义 `GroundPathNavigation`（水陆通行 + 开门）。
- **engine/attribute（1）**：`WandscapeAttributes`（注册 6 个自定义 MC attribute + bridge `core.AttributeType`↔vanilla `Holder<Attribute>`）。
- **engine/sound（2）**：`WandscapeSounds`（全模组自定义 SoundEvent 唯一注册点，13 跨域引用）、`ColonyAmbientSystem`(客户端循环)。
- **engine/transport（3）**：`ItemTransportManager`(send/tickAll)、`TransportItemEntity`、`TransportStartPacket` —— 沿路线的物品飞行动画（仓库↔NPC）。
- **engine/system（2）**：`NavigationSystem`（**全 NPC 移动的唯一驱动器**）、`ResourceSupplySystem`（资源补给重试环）—— 都是 ECS System 实现。
- **engine/source（2）**：`BuildingTaskSource.poll`（把建筑块实体 WorkItem 翻成 TaskRequest，**建筑→engine 任务唯一桥**）+ `source/blueprint/BlueprintConfigLoader`(JSON→BlueprintDefinition AST→注册可执行蓝图)。
- **engine/service（10）**：跨域 MC 服务 `ChunkLoadManager`(区块强载/lease) `ParticleService` `SoundService` `AchievementService` `StatsService`(sub NarrativeEventTriggered/ColonyLevelUpEvent) `GuideProgressService` `GuideServerContext` `ColonyMetricsService` `ChunkLeaseData` + `service/client/ClientSoundHelper`。⚠️ 这类服务常**只被 EngineBootstrap 注册**（事件订阅/静态 register 做活），import 计数低 ≠ 死码。
- **engine 根**：`WandscapeEngine`（**全局静态服务定位器，36 跨域引用——getXxx() 搭桥反模式本体**）、`TaskPoolSavedData`、`ColonyApiImpl`、`HostileTargetingHandler`、`BuildingNoSpawnZoneHandler`。

## 关键坑 / 旧文档矛盾（tier0 核心价值）
1. **"core 是真·纯 Java 独立层" 只对一半**：`core` 零 MC 是真的，但它**不独立**。`core/ecs/World.java:6,8-10` 直接 import `op.executor.OpExecutorRegistry`、`task.engine.dsl.BlueprintRegistry`、`task.engine.pool.BuildingTaskPool`、`task.engine.pool.GlobalTaskPool`；`CoreBootstrap` 注册 `task.scheduler.SchedulerSystem/TaskExecutionSystem/SystemBlueprintSystem` + `task.source.TaskSourcePoller`——**真正干活的是 task/op 的 system，core 只是框**。所以"双层是好抽象"要改成"**运行内核{core+task+op}↔MC 一道缝**"。
2. **boundary 接口不是单次间接桥（我此前草案判错，已修正）**：`BlockOps/EntityOps/RitualOps/MovementOps` 的主消费方是 `op/executor/DefaultOpExecutors` + `task/scheduler/TaskExecutionSystem`（经 `world.blockOps...` 调用，非 import）；`ColonyResourceAccess` 由 `warehouse/WarehouseManager` 实现（core+engine 外唯一实现）+ `DefaultOpExecutors`/`GlobalTaskPool`/`TaskExecutionSystem` 消费；`EventBus` 达 building/road。→ 它们是**真·多调用方运行时接缝**，不是"单次间接→内联"。判"能否内联"要看 `world.xxx` 消费方数量，不能看 `import core.boundary.X`（这计数会漏）。
3. **"自制 ECS 拆除（core/ecs+component，15+ 文件）" 是过度判断**：ECS 活且承重——6 个 `System` 实现（engine 2 + task 4）、`CoreBootstrap` 注册 6 个组件 store（Position/TaskExecutor/Inventory/ColonyMember/ColonyMetadata/NavigationState）、被 task/op/npc/engine 读。能安心删的只有：`ComponentStore`+`HashMapComponentStore`（**core 外 0 引用、单实现→内联**）、`SuspensionContext`（0 引用→内联/私有）。**注意 5/11 component 不是 ECS 组件**（`CastStrategyComponent`/`MagicState`/`EquippedMagicComponent`/`NpcTaskQueue`/`SuspensionContext`，住 `WandscapeNpc`，各有 `*Test`）——"拆 ECS"一刀切会误伤它们。可行的重构是**拆 `World` 这个 god-object 的职责**（store/DI/async/task 池），不是删 ECS。
4. **`WandscapeEngine` + `EngineBootstrap` = getXxx() 搭桥本体**：`WandscapeEngine` 36 处跨域静态引用、`getWorld()/getAsyncExecutor()/getRitualOps()/...`；且 `getWorld()` 在 engine 外 **0 调用者**（World 是装配时构造注入，非懒取）。拆法：消费方直接 new/直接调对应服务，定位器消失。`EngineBootstrap` 是唯一装配点，拆桥时它兜底注册处。
5. **core 边界 = op/task 运行内核↔MC**，不是 core↔engine。所以 Tier 4「ops 双层合一（engine/boundary+core/boundary 合并成一份）」需重新掂量：边界接口消费方多（op/task/warehouse/building/road），**保留接缝但搬到运行内核旁边**（不说成"独立 core 层"）；把 `ComponentStore` 这类单实现内联；把 `WandscapeEngine` 定位器解散。

## 归属
- **纯值类型 + op/task 运行逻辑**（`GridPos`/`ResourceStack`/`AttributeType`/`AtomicOp`/`BlueprintInterpreter`/任务评分…）→ 构成**零 MC 可单测内核**，各随其 content 域（task/op 已归 task 域，值类型随用它的域或 foundation/util）。**红线："纯逻辑不 import MC"正是靠这批类守住，保留自然分层、不必保留整个 core 框。**
- **活 ECS（World/System/6 组件）** → 跨域基建，归 `foundation`（或并入 task 域——它是任务引擎的骨架）。**不删、拆 god-object 职责。**
- **boundary 接口 + engine 4 实现** → 保留接缝但跟运行内核走（task/op 旁），非"独立 core 层"；`ComponentStore`/`HashMapComponentStore`/`SuspensionContext` 内联。
- **`EquipmentPreset`** → Tier 1 删。**`NpcAttributes`** → 从"死码"清单除名（已活）。
- **engine 跨域服务**（sounds/nav/attributes/transport/chunkload）→ 各自域或 foundation；**`WandscapeEngine`/`EngineBootstrap` 定位器桥** → dissolve。**engine/boundary 目录名** → 随归类改名（去掉"boundary"这误导名）。

---

# 8 技术层/资源杂项 — client / compat / command / dataconfig / mixin / gametest / guidebook + resources

- **一句话**：这批大半是真正的技术/资源层，归 `foundation`/`compat`；但**没有一个全局单体 client 层**——client 代码早已按域分散（17 个 `*/client` 子包），真实散点仅 1 个错位渲染器。
- **改它先看**：`WandscapeDataLoader`（dataconfig 数据加载主入口）、`WandscapeClient.onEntityRenderers`（渲染注册）、`Wandscape.onRegisterCommands`（命令装配）、`WandscapeClient.onClientSetup`（guidebook 打开路径）。
- **数据流**：各域 client 子包→`WandscapeClient` 注册；`dataconfig`→`Wandscape.DATA_LOADER`→各域加载器；`command` 各 `node()`→`/wandscape` 根；guidebook item→`shared/ui/markdown` 渲染。

## client（顶层）
- 顶层 `client` 包**只有 1 个文件**：`client/renderer/TransportItemEntityRenderer`（112 行），渲染 `engine.transport.TransportItemEntity`。这是全局 client 唯一代码，且是**域专属**（transport）。
- "80 文件/20k 行"是**所有域内 `*/client` 子包之和**（17 个）：building 13、road 13、projection 10、npc 7、shared 6、tourist 5、production 4、overview 3、magic 3、compat/curios/client 2、ring/warehouse/compass/engine-service 各 1、顶层 client 1。
- 实体渲染器映射（`WandscapeClient.onEntityRenderers`）：`npc/client/WandscapeNpcRenderer`(→WandscapeNpc+EvilMage)、`tourist/client/TouristRenderer`(→TouristEntity)、`magic/client/MagicBeamEntityRenderer`(→MagicBeamEntity)、`building/scanner/client/ScannerRenderer`(→ScannerBlockEntity)、`client/renderer/TransportItemEntityRenderer`(→TransportItemEntity)。
- **结论**：无大全局 client 层可拆。散点仅 `TransportItemEntityRenderer` → 挪 `engine/transport/client`。其余域内 client 已聚合，按目标形态"域内不设 client 子包"并入各功能块；`shared/client`+`shared/ui` → foundation/ui。
- **坑**：plan/旧文档把 client 当 20k 行技术层，实际顶层几乎空、真身是域内分散。

## compat（第三方集成，全 `compileOnly`，安全可选）
- **curios（8）**：`CuriosCompat`（**facade，零 curios import**，`ModList.isLoaded("curios")` 门禁）+ `CuriosCompatImpl`（直接 import，仅门禁后触达）。`NpcCuriosMenu/NpcCuriosScreen/NpcCurioSlot/NpcOpenCuriosPacket/CuriosCommand` + `client/NpcCuriosButton`（故意不 import curios 类）。隔离靠"门禁-再触达"（JVM invokestatic 运行时解析）。
- **ironspellbooks（4）**：`IronSpellsCompat`(facade 门禁)+`IronSpellsHelper/IronSpellsCaster/IronSpellsAttributes`。**泄漏**：`npc/entity/WandscapeNpc` 直接 import `io.redspace.ironsspellbooks.entity.mobs.IMagicSummon`+`instanceof`（`isLoaded()` 守卫不崩，但域实体直触可选依赖类型，应走 compat 接缝）。
- **jei（9）**：`@JeiPlugin WandscapeJeiPlugin`+`ElementRecipe/ElementRecipeCategory/ElementRecipeCollector/ElementRecipeManagerPlugin/SpellInfoCollector/SmallCountItemStackRenderer`。mezz.jei 只在 compat/jei 内 import（全仓唯一），由 JEI 插件类路径发现，无 JEI 永不加载。
- **结论**：compat 保持独立顶层包（与目标一致），隔离良好。唯一要修的是 `WandscapeNpc→IMagicSummon` 直触。

## command（19 文件 / 2981 行）
- **无注册表/基类**：每命令一个静态 `node()`/`buildNode()` 工厂返回 brigadier `CommandNode`，全接进 `/wandscape`（`Wandscape.onRegisterCommands`，1075-1103）。
- 18 子命令：audit_elements、colony、consume_warehouse、fill、generate_element_mappings、guide、logfilter、magic、navtest、profile、publish、recover、roadstudio、seed_warehouse、spline、stresstest、tavern、tourist、transport。**绝大多数 debug/test**（仅 colony/generate_element_mappings/curios 偏生产）。
- 位置不一致：`guard/GuardCommand` 是唯一不在 command/ 的命令类；`compat/curios/CuriosCommand` 因门禁留 compat。
- **结论**：各命令归各自域；debug 命令（audit/stress/nav/profile/logfilter/seed/consume/fill/transport/roadstudio）是未来拆分大清理对象。

## dataconfig（2 文件）
- `WandscapeDataLoader`（extends `SimpleJsonResourceReloadListener`，扫 `data/wandscape/<category>/*.json`、wandscape 命名空间优先、`/reload` 重载；注册 `Wandscape.DATA_LOADER`，喂 `WandPresetLoader/ElementMappingLoader/MagicCircleLoader/SpellbookLoader/ProductionRecipeLoader/BlueprintConfigLoader/RoadPresetLoader/BuildingConfigLoader`）。`SimpleDataRegistry<T>`(implements shared/registry `WandscapeDataRegistry<T>`，id→T map)。
- **结论**：干净 foundation（datapack JSON 注册/重载基建）。

## mixin（4，全功能钩子，无一可删，已 common/client 分置）
- `MixinLevelTicks`→`LevelTicks.schedule`（建楼守卫期间取消方块 tick，**building**）、`MixinServerLevel`→`ServerLevel.isVillage`（市政厅旁 raid 跑全波，**raid**）、`MixinOverviewCamera`→`Camera.setup`（overview 客户端）、`MixinSplineEditorCamera`→`Camera.setup`（road 样条编辑器）。
- **结论**：各归各自域，不归通用 foundation 桶。

## gametest（1）
- `ElementAuditRunner`（`@EventBusSubscriber`，ServerStartedEvent，`wandscape.runAudit` sysprop，由 `elementAudit` run config 设）——加载 element_seeds.json、审计元素映射覆盖、写 build/reports、halt 服务器。（跑审计用 `runElementAudit`，runGameTestServer 无 @GameTest 会崩——见记忆。）
- **结论**：测试/工具桶（元素域色彩）。

## guidebook（2 Java）
- `guidebook/item/GuideBookItem`(Item，use() 发 `GuideBookOpenPacket(INDEX_DOC)`)+`guidebook/network/GuideBookOpenPacket`(server→client payload)。真正阅读器/渲染在 `shared/ui/markdown/**`+`shared/ui/guide`。
- **结论**：薄域入口。item→items，UI→foundation/ui。

## resources（data / lang / assets —— JSON 泛滥与 lang 灾难真实数字）
- **data/wandscape：1368 文件（1366 json+2 nbt）。`element_mappings` = 1187 json = 整个 data/wandscape 的 87%**——JSON 泛滥几乎全在这一类（逐原版方块→7 元素映射）。其余：buildings 53（+deprecated 14=67）、craft_recipes 32、advancement 33、magic_spells 10、magic_circles 10、blueprints 13（含 production 4）、recipe 2、loot_table/blocks 2、damage_type 2、narratives 3、road_templates 2、structure/road 2 nbt、tags/block 1、根 element_seeds.json。
- **buildings/deprecated（14）是 building 兼容载荷，不可删**（`ProjectionNetwork`：deprecated 建筑隐藏但仍可用）——与 status.md/tier1.md 一致。
- data/curios：5 json。
- **lang：en_us 2031 + zh_cn 2032 = 4063 行，正好一语言一大坨**——「lang 分文件」目标实锤。
- **guide：52 .md（en/zh 各 26），无 json**，~26 页/语言、每语言 ~1000 行。内容 `assets/wandscape/guide/`。
- assets 总 391：models 130（item 126）、textures 193（item 112/gui 43/entity 31）、blockstates 4、particles 2、sounds 7 ogg+sounds.json、lang 2、guide 52。
- 小死数据候选：element_mappings/disabled(1)、blockstates/models 的 `$name` 模板(2)、structure/road↔road_templates 疑似冗余(4)。
- **意义**：JSON 泛滥是**单点**(element_mappings)非处处；lang 是**每语言一坨**；guide 是 **markdown** 非 json。这三条直接圈定 Tier 里 JSON/lang 治理的范围。

---

# 批 7、8 结论表（并入 packages.md 全局结论表用）

| 顶层包 | 归属 | 一句话依据 |
|--------|------|-----------|
| `core` | 拆散但不删活骨架：纯值类型+op/task 运行逻辑→各 content 域；**活 ECS(World/System/6组件)→foundation 或 task 域（拆 god-object 职责，不删）**；boundary 接口→保留接缝但跟运行内核走（非独立 core 层）；`ComponentStore`/`HashMapComponentStore`/`SuspensionContext`→内联；`EquipmentPreset`→删 | 零 MC 为真，但 World 直接耦合 task/op——core 是运行内核的"框"不是独立层；ECS 活且承重 |
| `engine` | 拆散：真 MC 适配(4 Ops 实现+2 ECS system)保留；跨域服务(sounds/nav/attributes/transport/chunkload)→各自域或 foundation；`WandscapeEngine`+`EngineBootstrap` 定位器桥→dissolve；`engine/boundary` 目录名→随归类删 | 纯 MC、只有 ~6 个真适配器，其余是装配/服务；定位器是 getXxx() 搭桥本体 |
| `client`(顶层) | 仅 1 文件 `TransportItemEntityRenderer`→挪 `engine/transport/client` | 无全局 client 层，client 早已按域分散（17 个 `*/client` 子包） |
| `compat` | 独立顶层包（保留） | 三集成全 compileOnly、门禁/插件发现隔离良好；唯一泄漏 `WandscapeNpc→IMagicSummon` 走接缝 |
| `command` | 各命令归各自域；debug 命令大清理 | 无基类/注册表，18 命令 12+ 是 debug 工具 |
| `dataconfig` | foundation | datapack JSON 注册/重载基建(WandscapeDataLoader) |
| `mixin` | 各归各自域(building/raid/overview/road) | 4 个全功能钩子、无一可删、已 common/client 分置 |
| `gametest` | 测试/工具桶 | ElementAuditRunner，构建期元素审计 |
| `guidebook` | item→items，UI→foundation/ui | 薄入口，重活在 shared/ui |
| `resources` | element_mappings 单点泛滥；lang 分文件；guide 是 md | data 87% 在 element_mappings；lang 每语言 2031/2032 一坨；guide 52 md |

> 注：`NpcAttributes` 已从 plan/status 的"死码"清单除名（现被 `CoreBootstrap`/`EntityComponentBridge.defaults()`/~9 测试引用）；`core/types/EquipmentPreset` 才是真·0 引用死码。
