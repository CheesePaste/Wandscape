# Tier 2 改名清单（重命名 work order）

> 目的：把全仓 648 个类的命名问题梳理成 Tier 2「改名」的执行面。plan.md 只写了「Mage→Npc 全仓统一」，本文把它展开成一份可落地的清单。
> 侦察方式：6 个并行子代理按包域扫 648 类，逐一用 `neoforge-21.1.233-sources.jar` 核对 MC/NeoForge/JDK 撞名、跨仓重名、注册 id/lang/NBT/JSON 字符串引用面。
> **原则：一物一名，词表先行。** 改名不是把每个别扭名字单独换掉，而是先给每个概念定一个标准词，再让所有类名向标准词收敛。
> 状态：草稿（已全仓扫描，待用户拍板决策①后分批执行）。进度记 `status.md`。

---

## 决策 ①（已拍板 2026-08-30）：殖民地法师统一到 `Mage`，字符串全字典

同一个人物在现网叫 **Mage / Npc / Wizard / Citizen** 四套，外加 `getNpcName()` 硬编码返回 `"Mage"`。

**拍板结果**：选 **B + 全字典**——把殖民地法师这个概念彻底归到 **`Mage`**，不只是 `WandscapeNpc`→`WandscapeMage` 等类名，**注册 id、网络包 id、菜单 id、lang key（含散文）、NBT 键、指南名**里的 `npc`/`wandscape_npc`/`NPC` 字面量也一并翻成 `mage`/`法师`。lang 值「法师」天然贴（现网早已这样译），无需改。

**理由**：MC 无 `Mage` 类（`Npc`/`Citizen`/`Villager` 都会撞 MC），语义最准最干净；lang 玩家可见文案现网已是「法师」；代价是 churn 大（见批 2a 血口量）。

**连带定名**（词表第一行）：
- `Wizard`（外观/皮肤/帽）归入 `Mage`：`WizardHatLayer/Model`→`MageHat*`，`textures/entity/wizard` 皮肤池→若一并统一则 `mage`（玩家可见资源，见批 2a 备注）。
- `Citizen`（残留 2 处 lang）清成 `mage`。
- **遗留**：包名 `npc/`（com.wsteam.wandscape.npc.*）是否也 `npc/`→`mage/` 属**结构改动（挪包），留 Tier 4**，Tier 2 只改类名/标识符，**不挪包**。plan.md 目标形态里写的 `content/npc/` 若随此为 `content/mage/`，在 Tier 4 一并定。

**影响范围**：批 2a 全表按 `Mage` 写死。其余批次（1/3/4）不依赖此决策。

---

## 词表（决策 ②：每个概念一个标准词）

| 概念 | 现用词（乱） | 标准词 | 说明 |
|------|------------|--------|------|
| 殖民地法师 NPC | Mage / Npc / Wizard / Citizen | **Mage**（已拍板） | Wizard 帽/皮肤池并入，Citizen 清理；包名 `npc/`→`mage/` 留 Tier 4 |
| 法术 | Magic / Cast / Spell | **Spell** | magic/ 包名保留（更贴业务），类名统一 Spell |
| 玩家权杖 | Wand / Scepter / MageWand / `*_wand` id | **Scepter** | 类已叫 ScepterItem，注册 id `*_wand` 反了 |
| 游客 | Tourist / Visitor / Guest / Citizen | **Tourist** | Guest 旅馆住客语义保留 |
| 任务步骤（op/） | AtomicOp / XxxOp / OpExecutor | **Step** / StepHandler | 与「引擎能力 ops」错开词汇 |
| 引擎能力（boundary） | core/boundary/*Ops / engine/boundary/Wandscape*Ops | **Port / Access** | 同上，双层不再撞词 |
| 绝对方块坐标 int3 | GridPos / PathPoint | **GridPos** | PathPoint 并入 |
| 平面坐标 int2 | XZPoint | **XZPos** | |
| 相对偏移 int3 | BlockOffset / RoadTemplateBlock | **BlockOffset** | 模板块复用 |
| 向量 double3 | SplineVec3 | **SplineVector3** | 消除复刻 vanilla Vec3 误导 |
| 建筑投影域 | Projection* / Build* | **BuildMode*** | 投影并入 building 域 |
| 建筑扫描器 | Scanner* / BuildingScanner | **BuildingScanner**(基) / **SurvivalScanner**(子) | 当前继承倒置 |
| 3D 轴 gizmo | BuildGizmo* / ScannerGizmo* | **Gizmo**（一处） | |
| 法师候选 | MageResume / RecruitmentCandidate / MageAttributeRoller | **MageCandidate** 词族 | 瞬态/持久分名 |

---

## 批 1 — 撞 MC/NeoForge/JDK 实锤（最高优先，独立于任何决策）

这些已在真实代码里造成被迫写全限定名 / 一眼读错的代价，建议最先做，不依赖决策①。

| 现名（包） | 撞谁 | 建议新名 | 字符串面 |
|-----------|------|---------|---------|
| `core/ecs/System` | JDK `java.lang.System` | `EcsSystem` | 6 文件 import + 2 处已写 `java.lang.System` 全限定名（NavigationSystem:308 / TaskExecutionSystem:639）；低 |
| `core/types/AttributeModifier` | MC `world.entity.ai.attributes.AttributeModifier` | `NpcModifier` / `StatModifier` | WandPresetLoader:63-71 被迫全限定 MC 版；低 |
| `core/component/Inventory` | MC `world.entity.player.Inventory`（实为 `List<ResourceStack>` 资源储物） | `NpcInventory` / `Stockpile` | 语义根本冲突；lang「Inventory」是散文非类名；低 |
| `core/boundary/EventBus` | NeoForge `bus.EventBus`/`IEventBus`（本接口是延迟 tick 派发，语义相反） | `DomainEventBus` / `TickEventBus` | 低 |
| `core/types/EquipmentSlot` | MC `world.entity.EquipmentSlot` | `NpcEquipmentSlot` / `GearSlot` | 易误读；低 |
| `core/component/Position` | MC `core.Position`（record 包 GridPos） | `EntityPos` / `GridPosition` | 低 |
| `core/ecs/World` | MC World/Level 历史概念 | `EcWorld` / `EcsWorld` | 低；优先级低于前 6（可顺手） |
| `shared/data/Activity` | MC `world.entity.schedule.Activity`（混装 AI 移动态+交互位动作） | `TouristActivity` | **高**：`EnumProperty.create("action", Activity.class)` 块状态属性直接用该类 + JSON + lang `activity.wandscape.*` 双版 + 多处 import；改类名不动属性名 `action` 与 lang 前缀 |
| `shared/ui/I18n` | MC `client.resources.language.I18n` | `Localization` | **中**：40+ UI 类 import `I18n.name()`；纯静态工具迁 import |

> 注意：`core/ecs/World`、`core/boundary/*Ops`、`core/event/*`、`Bootstrap` 等**同时是 Tier 4 拆 core 的直接靶子**——若 Tier 4 会挪包，这批别现在改（见「跟 Tier 4 走」节）。批 1 只列「现在改不依赖分层」的。

---

## 批 2 — 一物多名收敛（词表驱动）

### 2a 殖民地法师 → Mage 全字典统一（已拍板：类名 + 全字典）

**血口量**：27 个 `Npc*.java` 类定义 + `WandscapeNpc*` 家族 + ~120 个引用文件 + 15 个资源文件（lang `gui.wandscape.npc.*`×50、`message.wandscape.npc.*`×4、`npc_guide`×16、`npc_cast`×3、实体 id `wandscape_npc`）。

> 已归 `Mage`、不需要动的：`MageHutScreen/ServerHandler/ActionPacket/DataPacket`、`MageHutAttributes/MageHutResident`、`EvilMage/EvilMageCastGoal`、`MageResume/MageAttributeRoller`、`MageSummaryDto/MageModeActionPacket`、`shared/data/MageWandItem`。这批只把 `Npc*` 翻成 `Mage*`。

**子步 2a-1 类名（定义 + 引用）**

| 现名（包） | 建议 |
|-----------|------|
| `npc/entity/WandscapeNpc` | `WandscapeMage` |
| `npc/client/WandscapeNpcRenderer` / `WandscapeNpcModel` | `WandscapeMageRenderer` / `WandscapeMageModel` |
| `shared/api/NpcApi` / `npc/internal/NpcApiImpl` | `MageApi` / `MageApiImpl` |
| `npc/NpcMenu` / `NpcStrategyMenu` | `MageMenu` / `MageStrategyMenu` |
| `npc/NpcScreen` / `NpcStrategyScreen` | `MageScreen` / `MageStrategyScreen` |
| `npc/data/NpcData` / `NpcDataImpl` | `MageData` / `MageDataImpl` |
| `npc/network/NpcDataPacket` / `NpcDismissPacket` / `NpcOpenEquipPacket` / `NpcOpenStrategyPacket` / `NpcRenamePacket` / `NpcStrategyPacket` / `NpcTogglePacket` / `NpcOpenCuriosPacket` | `Mage*Packet` 对应 |
| `npc/internal/NpcDeathHandler` | `MageDeathHandler` |
| `guard/NpcEscapeTeleport` / `NpcSpellPowerHandler` | `MageEscapeTeleport` / `MageSpellPowerHandler` |
| `compat/curios/NpcCuriosMenu` / `NpcCuriosScreen` / `NpcCuriosButton` / `NpcCurioSlot` | `MageCurio*`（顺带统一单复） |
| `shared/api/NpcBindingItem` + `shared/api/MageWandItem` | 统一交互钩子：`MageInteractHook`（非潜行）/`MageSneakHook`（潜行）——「…Item」后缀是错的（是接口非物品） |
| `npc/internal/ReviveHandler` / `ColonyDeathRegistry` | `ColonyReviveHandler` / `MageDeathRegistry`（同簇三套词归位） |
| `core/component/NpcTaskQueue`（可选，随 core）；`core/types/NpcAttributes` | 0 引用死代码，随 Tier 1 删 |

**子步 2a-2 注册 id / 网络包 id / 菜单 id / NBT 键（协议与存档层）**

| 现串 | 建议 | 备注 |
|------|------|------|
| 实体 `wandscape_npc` / `wandscape_npc_spawn_egg` | `wandscape_mage` / `wandscape_mage_spawn_egg` | 旧档有该实体即失联 → **断档**（开发期允许） |
| 菜单 `npc` / `npc_strategy` | `mage` / `mage_strategy` | |
| 包 id `npc_data` / `npc_rename` / `npc_toggle` / `npc_dismiss` / `npc_strategy` / `npc_open_equip` / `npc_open_strategy` / `npc_open_curios` | `mage_*` | 协议键 |
| 声音 `npc_cast` | `mage_cast` | |
| NBT/数据键 `npc_id`（BuildingSavedData） | `mage_id` | 存档断档 |
| `npc_curios` | `mage_curios` | |
| `wandscape_npc_deaths`（ColonyDeathRegistry） | `wandscape_mage_deaths` | |

**子步 2a-3 lang / 散文（玩家可见）**

| 现串 | 建议 |
|------|------|
| `gui.wandscape.npc.*`（×50） | `gui.wandscape.mage.*`（值「法师…」不动） |
| `message.wandscape.npc.*`（×4） | `message.wandscape.mage.*` |
| `npc.wandscape.state.*` | `mage.wandscape.state.*` |
| `gui.wandscape.panel.npc_count` | `mage_count`（值「%s/%s 法师」） |
| `gui.wandscape.tavern.recruit_npc` | `recruit_mage`（值已「招募法师」） |
| `blueprint.wandscape.*.desc` 里「NPC 站在…」 | 法师 |
| `npc_guide`（指南 id + 文件名） | `mage_guide` |

**子步 2a-4 Wizard → Mage（外观词族，随本批顺手）**

| 现名/串 | 建议 |
|---------|------|
| `WizardHatLayer` / `WizardHatModel` | `MageHatLayer` / `MageHatModel` |
| 皮肤池 `textures/entity/wizard`（`WandscapeMage.detectSkinVariants()`:555 / Renderer:39,46 扫它） | 改 `mage`（玩家可见资源，全链同步） |
| 成就 `a_wizards_interest` + id/lang | `a_mages_interest` |

**执行注意**：
- 一次性改动大，**拆多个 commit**（大重构例外逐步提交）：类名一个、注册/包 id 一个、lang/散文一个、wizard 一个。每 commit 三查（grep 旧名零命中、串一致、净减）。
- 撞名提醒：`Mage*` 与 `magic/`（法术域）近音但语义分离，**不合并**；`magic/` 包与 `Mage*` 类名并存合理。
- 高层风险串（实体 id、成就 id、NBT 键）走断档处理。旧档 `wandscape_npc` 实体在 `mage` 世界会失联，直接弃档重开。

### 2b 法术 Magic/Cast/Spell → Spell

| 现名（包） | 建议 | 字符串面 |
|-----------|------|---------|
| `magic/data/MagicDef` | `SpellDef` | `SPECIAL_SPELLS` 常量 + SpellRef 引用 |
| `magic/internal/MagicCaster`/`MagicCastManager`/`CastBrain` | `SpellCaster`/`SpellCastManager`/`SpellBrain` | `MagicCaster.DEFAULT_CIRCLE/DEFAULT_COLOR` |
| `magic/internal/MagicSpellExecutors` | `SpellExecutors` | 同词干混 Magic+Spell |
| `magic/internal/SpellcastingApiImpl` + `shared/api/SpellcastingApi` | `SpellApi`/`SpellApiImpl` | `WandscapeApis.getSpellcastingApiSilently()` |
| `magic/entity/MagicBeamEntity` | `SpellBeamEntity` | 注册 id `magic_beam`→`spell_beam` |
| `magic/item/SpellItem` 字段 `MAGIC_ID_KEY`/`setMagicId`/NBT `magic_id` | `spell_id` | NBT/DataComponent 键 `magic_id` 玩家可见/存档层 |
| `npc/client/CastBoltParticle` | `SpellBoltParticle`（移 `magic/client`） | 纯客户端，无注册 id |

### 2c 法杖 Wand/Scepter/MageWand → Scepter（玩家权杖）

| 现名（包） | 建议 | 字符串面 |
|-----------|------|---------|
| `scepter/ScepterItem`×4（PEACE/FOLLOW/SHELTER/HOSTILE） | 保留类名，注册 id `peace_wand`/`follow_wand`/`shelter_wand`/`hostile_wand` → `peace_scepter` 等 | **玩家可见注册 id + lang `item.wandscape.*_wand.tooltip`**，全链改 |
| `scepter/OmniScepterItem` | 保留（id 已对） | non-`*_wand` 特例 |
| `wand/item/WandItem` | `NpcStaffItem`（若决策①选 Npc）/保留 | 注册 id `wand` |
| `shared/api/MageWandItem` | 见 2a 交互钩子 | |
| `scepter/ScepterKind` | `ScepterMode` | 拼接 `item.wandscape.<id>.tooltip` |

### 2d 游客 Tourist/Visitor/Guest/Citizen → Tourist

| 现名（包） | 建议 | 字符串面 |
|-----------|------|---------|
| `shared/entity/ColonyVisitor` | 删接口并入 `TouristEntity`，或 `TouristMarker` | `WandscapeNpc.isFriendlyForce` + `FriendlyForce` 引用 |
| `shared/entity/VillagerLike` | `TouristLike`/`TouristTargetable` | 撞 MC `Villager`；唯一实现 TouristEntity |
| `shared/entity/PlayerLike` | `PlayerTargetable` | 与 VillagerLike 成对 |
| lang `tourist_role_citizen`（en 残留 "Citizen"） | `tourist_role_tourist` | lang key 玩家可见 |
| `gui.wandscape.scanner.node_desc` "Citizen wizards" | "tourists" | 玩家可见文案 |
| 旅馆 `guests` lang | 保留（住客语义） | |

### 2e 任务步骤 vs 引擎能力（op/ 双层，Tier 4 前置词汇错开）

| 现名（包） | 建议 | 字符串面 |
|-----------|------|---------|
| `op/api/AtomicOp` | `TaskStep`/`AtomicStep` | `getSimpleName()` 仅日志串 |
| `op/api/*Op`（Transform/BlockInteract/EntityInteract/Ritual/AltarCast/ResourceRequest/EmitEvent/IfCondition/Parallel/SpawnDecoration/AttackMonster/SelfDefense） | `XxxStep` | 同上 |
| `op/executor/OpExecutor` | `StepHandler`/`AtomicOpHandler` | `Class` 键控分派 |
| `op/executor/DefaultOpExecutors` | `OpExecutorRegistrar` | 注册引导器非「复数 executor」 |
| `op/executor/DefaultOpExecutors.ResourceRequestExecutor`（vs `engine/boundary/ResourceRequestExecutor` 同名不同类） | `SyncResourceRequestExecutor` | 消双层同名 |
| `.BlockInteractExecutor`/`.TransformExecutor`/`.RitualExecutor`（vs engine/Wandscape* 双胞胎） | `Sync*Executor` | 同上 |
| `op/api/ConditionEvaluator` | `BlueprintCondition` | 注册键是字符串非类名 |
| `core/boundary/*Ops`（Block/Entity/Movement/Ritual） | `*Port`/`*Access` | 跟 Tier 4 |
| `engine/boundary/Wandscape*Ops` | `Wandscape*Access` | 跟 Tier 4 |

### 2f 点/向量自造族（road + building，计划一致）

| 现名（包） | 建议 | 字符串面 |
|-----------|------|---------|
| `road/core/PathPoint` | 并入 `GridPos` | 完全同构；NBT 用 `placedBlocks:{x,y,z}` 类名非键 |
| `road/core/XZPoint` | `XZPos` | 半同构 2D |
| `building/data/BlockOffset` | 保留（`RelativeBlockOffset`） | JSON `[x,y,z]` + block_mapping `"x,y,z"` 契约 |
| `road/core/RoadTemplate.RoadTemplateBlock` | 复用 `BlockOffset` | JSON 模板 |
| `road/core/SplineVec3` | `SplineVector3` | NBT double 列表，类名非键 |

### 2g 道路域词（读码负担最大）

| 现名 | 建议 | 详情 |
|------|------|------|
| `SplineModel` | `SplineCurve` | 三次贝塞尔容器 |
| `SplinePoint` | `SplineControlPoint` | 控制点（anchor+2 handle） |
| `CurveSample` | `SplineSample` | 采样点 |
| `SplineLeg` | `RouteLeg` | 路由子段 |
| `RoadEdge`/`RoadNode`/`RoadNetwork`/`TransportRoute` | 保留 | 图论词固定 |
| `GlobalTaskPool` | `TaskManager` | 名过窄（存储+审批+分配+触发） |
| `TaskRequest` | `TaskSpec` | Request 与 GlobalTask 关系难懂 |
| `NpcTaskPackage` | `NpcTask` | Package 过泛 |
| `road/engine/WandscapeTags` | `RoadBlockTags` | tag id `wandscape:custom_roads` 是 datapack 契约保留，仅常量改名 |

### 2h 建筑域名词（跨包）

| 现名（包） | 建议 | 字符串面 |
|-----------|------|---------|
| Scanner 继承倒置：`CreativeScannerBlock` 是基类/完整版、`ScannerBlock` 是子类/生存简化版 | 基类→`CreativeBuildingScannerBlock`/`BuildingScannerBlock`，子类→`SurvivalBuildingScannerBlock`；Screen/BlockEntity 同理 | 注册 id `creative_building_scanner`/`building_scanner` + lang 块名保留 |
| `TownHall*` 类 vs `townhall` 注册/lang/guide vs `town_hall` compass/包 id | 统一下划线 `town_hall`，类名可保留 `TownHall*` | 注册 id、lang `gui.wandscape.townhall.*`、guide、包 id `town_hall_name_style` 全链 |
| `NodeDataPacket`/`MageHutDataPacket` | `NodeOpenPacket`/`MageHutOpenPacket` | 对齐同包 `*OpenPacket` 惯例；包 id 保留 |
| `HotelScreen` vs category=`service`（同一 GUI 兼 youth_hostel/luxury_hotel） | 补 `hotel` category 或改名 `ServiceHotel*` | |
| `building/network/OpenWarehousePacket` | 移 `warehouse/network` 改 `WarehouseOpenPacket` | payload id `"open_warehouse"` + log TAG |
| `projection/*` 域 | `Projection*`→`BuildMode*`；`BuildPlacement`/`BuildingRotation`/`BuildingCentering`/`BuildingSlot` 移 building | **先解耦 `ProjectionNetwork` 里 `Class.forName("...BuildingConfigLoader")` 硬编码**（改 building 类名会触发 `ExceptionInInitializerError`） |

### 2i gizmo 双名

`BuildGizmoController/Renderer` vs `building/scanner/client/gizmo/ScannerGizmo*`——同一 3D 轴 gizmo 概念，统一放 `building/gizmo` 或 `building/scanner`。`BuildGizmoRenderer` 的 RenderType id `"build_gizmo_quads"` 需一并处理。

---

## 批 3 — 泛名/名不副实（可读性）

| 现名（包） | 建议 | 说明 |
|-----------|------|------|
| `shared/data/WorkItem` | `BuildingTaskItem`/`QueuedBlueprintJob` | 「工作项」实为待办蓝图任务 |
| `shared/data/BarRatio` | `VisitorNeedsRatio`/`SatisfactionRatio` | 游客三需求条填充率 |
| `shared/data/Emotion` | `VisitEmotion`/`MoodOutcome` | 一次访问满意度层级 |
| `shared/data/ItemKey` | `ItemStackKey`/`ItemIdNbtKey` | 物品 id+NBT 复合键 |
| `shared/data/NameStyle` | `NameGenerationStyle` | 以 ordinal 传输，删/重排常量会破坏序 |
| `building/internal/BuildingState` | `BuildingRuntimeState` | 低优先 |
| `building/internal/EnqueueHelper` | 拆 `BuildingRegistrar`+`BuildingWorkItemFactory` | 职责远超「入队」 |
| `building/internal/ShopInteractionHandler` | **删除并入 `ShopStockManager`** | 空壳+死代码，属 Tier 1 删 |
| `configrecord` 群（Relax/Service/Shop/Wonder/Atm/Decoration `*Config`） | 抽 `BuildingBehavior` 概念 | 字段重叠（energyRestore vs energyPerUse=同一「精力」）；与顶层 `Config` 撞词 |
| `production/data/CraftRecipeView` | `CraftRecipeResolver`（随 Tier 3 收敛 `CraftRecipe`） | 「View」实为 resolve 门面 |
| `production/data/MiscRecipe` | `UtilityItemRecipe` | 实为功能物品配方 |
| `production/client/QuantityStepper` vs `production/internal/QuantityWindow` | `QuantityWindow`→逻辑、`QuantityStepper`→`QuantitySlider` | 命名与职责倒挂 |
| `compat/jei/ElementRecipe*` | 加 `Jei` 前缀 | 与元素域隔开；`ElementRecipeKind`→Type |
| `compat/jei/SmallCountItemStackRenderer` | `StackCountIngredientRenderer` | 晦涩 |
| `compat/curios/NpcCurios*`× vs `NpcCurioSlot` | 统一 `NpcCurio*` | 单复不一致 + CuriosCompat/Impl 夹生 |
| `stats/*` 缩写 | 统一 `Statistics*` 或 `Stats*` | 全称/缩写不一 |
| 根 `Config` | `WandscapeConfig` | 900 行巨型配置撞 ModConfigSpec |
| `engine/WandscapeEngine` | `EngineServices`/`EngineRuntime` | 实为静态服务持有器非「引擎」 |
| `engine/attribute/WandscapeAttributes` | `NpcAttributeRegistry` | 撞 MC `Attributes` 概念 |
| `engine/bootstrap/EngineBootstrap` | `EngineAssembler`/`EngineWireup` | 装配主力却叫 Bootstrap |
| `engine/source/blueprint/BlueprintConfigLoader` | `BlueprintLoader` | 与 BuildingConfigLoader/WandPresetLoader 同角色 |
| `shared/data/ElementType` | 移 `element/` | 住 shared 桥包；`@SerializedName` + `valueOf(id.toUpperCase())` 用枚举常量名，改名连常量 + `item.wandscape.element.*` lang |
| `ring/RingTier` | `OathRingTier` | 与 ScepterKind/ItemTier 译名不一 |
| `npc/internal/ColonyDeathRegistry`/`ReviveHandler`/`NpcDeathHandler` | 并入 2a-1：`MageDeathRegistry`/`ColonyReviveHandler`/`MageDeathHandler` | 同簇三套词归位 |
| `npc/.../NpcEscapeTeleport` | 并入 2a-1 → `MageEscapeTeleport` | |
| `shared/data/InterruptRecord`（shared） | 删（疑似死代码）或 `NpcInterruptRecord` | 整仓唯一重名（vs task/runtime/InterruptRecord），task 版在用 |
| `shared/registry/WandscapeConstants`/`shared/log/Log` | 保留或 `WandscapeLog` | 极泛化，低优先 |

---

## 批 4 — 命名质量重灾区（方法名级，不只类名）

| 位置 | 现状 | 建议 | 字符串面 |
|------|------|------|---------|
| `core/types/ResourceId.java:24` | 方法 `getFuckPureResourceId_NotContainFuckedNBT()` | `stripBlockStateSuffix()` | 剥离 `[...]` 块状态后缀；`WarehouseManager` 6 处调用同改；纯逻辑无串 |

> 此方法 2026-07-04 由 commit `0afd43ec`（"fix bugs"）引入，一个 commit 内改了定义+6 处调用。功能正当，纯粹名失礼。

---

## 跟 Tier 4 走、现在别动（避免二次 churn）

这些类本身就是 Tier 4 拆 core/engine/shared 或重排的直接靶子，现在改名会白做一遍：

- `core/ecs/*`：`World`、`ComponentStore`、`HashMapComponentStore`（`System` 除外——它撞 JDK 已全限定名 hack，批 1 已列现在改）
- `core/boundary/*Ops`、`core/event/*`、`core/CoreBootstrap`/`CoreBootstrapConfig`、`engine/bootstrap/EngineBootstrap`
- `engine/boundary/Wandscape*Ops`（跟 core/boundary/*Ops 一起在 Tier 4 定名）
- `projection/*` 的整包移动（批 2h 只先做词汇错开 + 解耦 `Class.forName`，挪包留 Tier 4）
- `op/` 与 engine 的双层（批 2e 的词汇错开可先做，结构合一留 Tier 4）

**判定规则**：引用重且被 Tier 4 挪包的 → 跟 Tier 4 走；引用重但 Tier 4 不挪 → 现在改；独立于分层且正造成实害 → 现在改（批 1 全属此类）。

---

## 执行建议

1. **决策①已拍板（B，统一 Mage + 全字典）**：批 2a 全表已写死。批 1 与批 3/4 不依赖它，可先行；批 2a 因改动大，建议放批 1 之后、按子步拆分提交。
2. **按批次分期**，每批一个 `refactor:` commit（大重构例外逐步提交）。批 1 的 Activity/I18n 高风险，单独 commit 单独验证。
3. **每批三查**（plan.md 复核）：`grep 旧类名` 零命中、字符串 id 与代码一致、行数净减。
4. **字符串面高风险的（Activity/I18n/注册 id 变更如 `*_wand`→`*_scepter`/`magic_beam`）**：先 grep 全量命中清单，确认识别所有玩家可见/存档层引用，改完跑 `./gradlew build` + `test` 全绿。
5. **禁顺手**：一个 commit 只改一件事，不顺手改格式/旁边类/注册 id（除非那条正是注册 id 改名本身）。
