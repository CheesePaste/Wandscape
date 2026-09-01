# Tier 2 改名候选清单（重构后全仓扫描 v2）

> ⚠️ **2026-09-01 审计裁决（已执行/已取消）**：本文档是候选清单，经代码逐条核验后——
> - **词表方向已由用户拍板推翻**：殖民地法师标准词 **npc**（非 Mage）；法术 **magic**（非 Spell）；**wand/scepter 两物保留原名**；Citizen 实为游客角色标签。§零词表与 §3.1-3.4 的 Mage/Spell/Scepter 收敛方向**作废**。
> - **§1.1「编译期咬人」仅 3/7 真成立**（System/AttributeModifier/EquipmentSlot）；I18n/Position/Activity 全仓 0 处共 import MC 类，系假设性 churn。
> - **已执行 11 项**：EcsSystem/NpcAttributeModifier/NpcInventory 撞名改名、StatsService/HouseApi/EquipmentSlot 删除、stripBlockStateSuffix 收口、NpcInteractHook/NpcSneakInteractHook、GuideScreen/GuideDocOpenPacket、RoadStudio 包合一、39 死 import。
> - **核查后取消 3 项**：npc 域 Mage\* 类（法师游客/建筑概念）、Scanner 继承（正确设计非倒置）、MageResume#touristName。
> - 执行记录见 `status.md` Tier 2 段。未执行条目按下文"过度优化"处理，**勿再按原方向动**。

> 状态：**候选清单，仅审计，未动任何代码**（2026-09-01 用户拍板：先出文档，不先改名）。
> 方法：机械 grep（撞名/词族/字符串面全局分布）+ 6 个并行域审计子代理（npc+tourist / task / building / road+magic+production+element / items+colony+warehouse / foundation+api+impl+compat）+ `build/moddev/artifacts/neoforge-21.1.233-sources.jar` 逐一核实 MC 撞名。
> 适用范围：迁移后的目标结构（5 顶层 + 11 content 域，`core/engine/shared` 桥层已拆完）。
> 取代旧 `newplan/rename.md`（2026-08-30 迁移前扫描，仅作历史参考）。旧清单里「跟 Tier 4 走、现在别动」的条目（ecs/op 双层/projection）现在 Tier 4 已完成，**全部可以动**。
> 原则：一物一名，词表先行。字符串面（注册 id / lang / NBT / JSON / 存档 / datapack）是改名唯一风险源，下文逐条标注。**开发期不承诺存档兼容，改串可断档**（CLAUDE.md 数据格式纪律），但要意识到并逐条拍板。

---

## 零、词表（先定标准词，再让名字向标准词收敛）

| 概念 | 现用词（乱） | 标准词 | 说明 |
|------|------------|--------|------|
| 殖民地法师 NPC | Mage / Npc / Wizard / Citizen | **Mage** | 已拍板 2026-08-30；类名 + 全字符串字典（见 §3.1） |
| 法术 | Magic / Cast / Spell | **Spell** | `magic/` 包名保留，类名/键统一 Spell（见 §3.2） |
| 玩家权杖（庇护/敌对/和平等） | Scepter*（类）/ `*_wand`（id）/ MageWandItem（接口） | **Scepter** | 三处向 Scepter 收敛（见 §3.3） |
| 建造法杖（NPC 默认装备） | WandItem / WandApi / id `wand` | **MageWand** | 与玩家权杖 Scepter 错开词 |
| 游客 | Tourist / Visitor / Guest / Citizen | **Tourist** | 旅馆住客 Guest 语义另议（见 §3.4） |
| 方块坐标 int3 | GridPos / PathPoint | **GridPos** | PathPoint 与 GridPos 同构，并入（见 §3.5） |
| 平面坐标 int2 | XZPoint | XZPoint | 自明，保留 |
| 相对偏移 int3 | BlockOffset / RoadTemplateBlock | BlockOffset | 模板复用 |
| 向量 double3 | SplineVec3 | **SplineVector3** | 消除 vanilla Vec3 近似（见 §1.2） |
| 建筑扫描器 | ScannerBlock(子) / CreativeScannerBlock(基) | **BuildingScannerBlock(基) / SurvivalBuildingScannerBlock(子)** | 修正继承倒置（见 §3.7） |
| 建筑投影模式 | Projection* / Build* / Building* | **Projection***（唯一前缀） | 见 §3.7 |
| 3D 轴 gizmo | BuildGizmo* / ScannerGizmo* | **AxisGizmo**（共享核心） | 双份逐行重复代码合一 |
| 任务步骤（op/） | AtomicOp / XxxOp / OpExecutor | **TaskStep / StepHandler** | 与引擎能力错开词（见 §3.6） |
| 引擎能力（boundary） | *Ops / Wandscape*Ops | **\*Port / \*Access** | 同上 |
| 市政厅 | TownHall / townhall / town_hall / town hall / `townhall1` | 类 **TownHall**、字符串 **town_hall** | 见 §3.8 |
| 统计 | StatsService / StatisticsCollector / ColonyMetricsService | 二选一（**Statistics\*** 或 **Metrics\***） | 见 §3.9 |
| 戒指 | RingTier / OathRing* 家族 | **OathRingTier** | 家族 7 类都有 Oath，唯独枚举没有 |
| 法师候选 | MageResume / RecruitmentCandidate | MageCandidate 词族 | 旧清单遗留，未重扫 |

---

## 一、角度 1：撞 MC / JDK 重名或近似（最高优先，独立于任何决策）

### 1.1 精确撞名（7 个，编译期已实际咬人）

| 位置 | 撞谁 | 现状证据 | 建议 | 字符串面 | 波及 |
|------|------|---------|------|---------|------|
| `content/task/ecs/System` | JDK `java.lang.System` | 已被迫写全限定名：`TaskExecutionSystem.java:646` 用 `java.lang.System.currentTimeMillis()` | `EcsSystem` | 无 | 7 文件 import |
| `content/tourist/data/Activity` | MC `world.entity.schedule.Activity` | 被 `EnumProperty.create("action", Activity.class)` 直接当块状态属性类用；NBT `currentActivity` 存 `name()`；扫描器 JSON 存小写名 | `TouristActivity` / `TouristAction` | **高：改枚举常量名破坏已存方块状态/NBT/JSON** | 三层 |
| `content/npc/types/AttributeModifier` | MC `world.entity.ai.attributes.AttributeModifier` | 被迫全限定名 3 文件：`WandscapeNpc.java:456,465,468`、`IronSpellsAttributes.java:81-113`、`WandPresetLoader.java:62-70` | `NpcAttributeModifier` | 无 | 3 文件 |
| `content/npc/types/EquipmentSlot` | MC `world.entity.EquipmentSlot` | **全库零引用死码**，唯 `WandscapeNpc.java:5` 一处 import 迫使 MC 的 `EquipmentSlot` 全写 FQN（400-417 行） | **删除**（或 `NpcEquipmentSlot`） | 无 | 0 引用 |
| `foundation/ui/I18n` | MC `client.resources.language.I18n` | `I18n.name(` 全模组 **62 文件 / 515 调用点**；同文件需 import 区分 | `Localization` | 无（lang key 不变） | 爆炸半径最大，纯机械替换 |
| `content/task/component/Inventory` | MC `world.entity.player.Inventory` | 实为 `List<ResourceStack>` 资源储物，语义也冲突 | `NpcInventory` / `ResourceInventory` | 无 | 30+ 文件 import |
| `content/task/component/Position` | MC `net.minecraft.core.Position` | 实为 `GridPos` 的 int 网格点 ECS 组件 | `GridPosition` / `EcsPosition` | 无 | 90+ 文件 import（波及面最大） |

### 1.2 近似 / 易误读（非精确撞名）

| 位置 | 问题 | 建议 | 字符串面 |
|------|------|------|---------|
| `content/task/ecs/World` | 与 MC 历史 `World`/现 `Level` 同名；且是「上帝对象」（组件仓库+边界+异步+实体+系统管理混一）。**building 域 53 文件 import 其中 ≥50 为死 import** | `EcsWorld` / `Simulation` | 无 |
| `content/road/core/SplineVec3` | 方法面（add/subtract/scale/length/normalize/dot）与 vanilla `Vec3` 逐一对齐，代码里与 MC `Vec3` 混用，读者易当同一类 | `SplineVector3` | 无（`RoadSavedData` 只落盘原始 double） |
| `content/tourist/entity/VillagerLike` / `PlayerLike` | 与 MC `Villager` / `Player` 仅差一个 `Like` | `VillagerTargetable` / `PlayerTargetable` | 无 |
| `content/road/engine/WandscapeTags$Blocks` | 嵌套类简单名 `Blocks` 与 vanilla `net.minecraft.world.level.block.Blocks` 相同，road 包内 5+ 文件同时 import 两个 `Blocks` | 内层改 `BlockTags` | 无 |
| `content/npc/types/ModifierOperation` | 与 MC `AttributeModifier.Operation` 概念重复，需手工 `case ADDITION -> MC.Operation.ADD_VALUE` 转换 | `NpcModifierOperation`，或并入 `NpcAttributeModifier` 内部 | JSON（wand preset `operation` 字段值 `ADDITION`/`MULTIPLY_BASE`） |
| `content/items/compass/MagicCompassItem` | 与 MC `CompassItem` 同后缀 | 可不动（Magic 前缀已区分）；若要更清可 `TownHallCompassItem` | 无 |
| `foundation/log/Log` | 与 `org.slf4j`/`com.mojang.logging` 日志门面易混（非 MC 撞名） | `WandscapeLog`（低优先，引用面极大） | 无 |

---

## 二、角度 2：泛名 / 名不副实（看不出功能域）

| 位置 | 问题 | 建议 | 字符串面 |
|------|------|------|---------|
| `Config`（根包） | 433 行巨型配置持有类，仅叫 `Config`，撞 `ModConfigSpec` 概念，根包无前缀 | `WandscapeConfig` | 无（config path 串不变） |
| `content/task/ecs/World` + `ComponentStore` + `HashMapComponentStore` | 通用 ECS 骨架名，靠 `ecs` 包名消歧 | 可随 World 一起加 `Ecs` 前缀 | 无 |
| `impl/WandscapeEngine` | 名不副实：不是引擎，是静态服务定位器（一堆 `getXxx/setXxx` 静态方法），真引擎是 `World` | `EngineServices` / `WandscapeServices` | 无 |
| `impl/CoreBootstrap` vs `impl/EngineBootstrap` | "Core/Engine" 命名颠倒难分：`CoreBootstrap` 才建引擎 World，`EngineBootstrap` 是 MC 适配装配层，还反调前者 | `EngineAssembly` / 统一前缀 | 无 |
| `content/building/internal/EnqueueHelper` | 只叫「入队」，实际是 建筑注册 + WorkItem 工厂(8 重载) + 材料统计 + 仓库播种 四合一 | 拆 `BuildingRegistrar` + `BuildingWorkItemFactory`，或 `BuildingWorkFactory` | 无 |
| `content/production/data/CraftRecipeView` | 叫 "View" 实为 craft 配方运行时 resolve 门面，易与 JEI RecipeView 混淆 | `CraftRecipeResolver` | 无 |
| `content/production/data/MiscRecipe` | 「杂项配方」产物实为功能物品（peace_wand/magic_compass/warehouse_terminal/oath_ring） | `FunctionalItemRecipe` / `ArtifactRecipe` | 类名无；JSON `type=="misc"` 是数据契约，连 JSON 一起改则高 |
| `content/production/ProductionEligibility` | 叫「资格」实算「元素够不够」，与 `internal/ProductionAffordability` 近义重叠 | 并入 `ProductionAffordability` / `ElementCostResolver` | 无 |
| `content/warehouse/WarehouseManager` | "Manager" 过泛，实为仓库系统门面（同时实现 WarehouseApi + ColonyResourceAccess） | `WarehouseService` / `WarehouseFacade` | 无 |
| `content/warehouse/ColonyItemBank` | 名含 "ItemBank"，实际同时存 items + elements + 购买计数 + 种子标记 | `ColonyResourceBank` / `ColonyStorage` | 无（`DATA_NAME` 与类名解耦） |
| `content/colony/raid/RaidTownHall` | 名像「袭击市政厅」，实为无状态定位器（findTownHall/isNearTownHall） | `TownHallLocator` / `ColonyTownHallLocator` | 无 |
| `content/colony/service/AchievementService` | 术语与 MC 不一致：MC 1.21 叫 `Advancement`（数据目录也是 `advancement/`） | `AdvancementService` | 无 |
| `content/colony/service/ChunkLoadManager` | 名宽泛，实为「建筑足迹 force-load 租约管理器」，与 `ChunkLeaseData` 不呼应 | `ChunkLeaseManager` | 无 |
| `content/items/SpellItem` | 泛名，实为「CUSTOM_DATA 存 magicId 的魔法物品形态」 | `BoundSpellItem` / `SpellScrollItem` | 无 |
| `content/tourist/data/BarRatio` / `Emotion` | 看不出是「游客三需求条填充率」/「一次访问满意度层级」 | `VisitorNeedsRatio` / `MoodOutcome` | 无 |
| `content/task/component/TaskExecutor` | 名像执行器，实为纯数据组件（一堆 public 字段），与 `OpExecutor`/`TaskExecutionSystem` 三角混淆 | `NpcTaskState` / `TaskExecutionState` | 无 |
| `content/npc/data/NpcData` | 语义模糊，且与 `NpcDataPacket`/`NpcDataImpl` 近义词堆叠 | `MageInfoView` / `NpcSnapshot` | 无 |
| `content/npc/internal/ReviveHandler` | "Handler" 名不副实：不是事件订阅者，是静态工具类（`NpcDeathHandler` 才是订阅者） | `ReviveService` / `ReviveExecutor` | 无 |
| `content/element/internal/ElementMaps` | 名像「映射表」，实为静态 JSON→Map 解析工具，与 `ElementMappingConfig`/`Loader` 前缀雷同 | `ElementValueMapParser` / `ElementCostMapParser` | 无 |
| `content/magic/data/ParamTypeInfo` | "Info" 后缀像元数据，实为 `ParamType` 的 client-safe 镜像枚举 | `ClientParamType` / `ParamTypeMirror` | 无 |
| `content/building/projection/BuildPlacement` | 名像「放置对象」，实为「射线命中→建筑锚点」解析器，且与 Projection 家族前缀不一致 | `BuildingAnchorResolver` | 无 |
| `content/building/BuildPlacementGuard` | "Guard" 暗示校验，实为「放置瞬间压制红石/流体 scheduled tick」的开关 | `BuildPlacementTickGuard` / `PlacementTickSuppressor` | 无 |
| `content/building/client/BuildingAreaRenderer` | "Area" 过宽，渲染的是建筑边界 + 交互位 | `BuildingBoundaryRenderer` / `BuildingZoneOverlay` | 无 |
| `content/building/scanner/CreativeScannerBlockEntity#BlockMode` / `#TargetMode` | 枚举名过泛，看不出是扫描器模式 | `ScannerBlockMode` / `ScannerTargetMode` | 只改类型名安全；改枚举常量破坏 NBT |
| `content/items/scepter/.../ScepterMarksSavedData` 相关 | 无 | 见 §3.3 | |
| `foundation/ui/guide/GuideTestScreen` | **生产级**指南书阅读器（10 文件/15 处生产调用）却叫 "Test" | `GuideScreen` / `MarkdownGuideScreen` | 无（doc path 不变） |
| `foundation/ui/component/LessButton` / `MoreButton` | "Less/More" 不表意，看不出是 −/+ 步进钮 | `MinusButton` / `PlusButton` | 无 |
| `foundation/registry/WandscapeConstants` | 内容 = 平衡常数 + 配置默认值混合，多处与 `Config` 重复定义同一数值（双源真相） | 与 `Config` 合并 / 由 Config 派生 | 无 |
| `content/building/ui/BuildingSort` | 一般名，实为建筑栏排序器 | `BuildingBarSorter` | 无 |
| `content/npc/component/MagicState` | "State" 泛，实为 魔力池+每魔法CD+施法互斥锁 | `ManaPool` / `CastingResource` | NBT（序列化键） |

---

## 三、角度 3：一物多名（词族收敛，词表驱动）

### 3.1 殖民地法师 Mage / Npc / Wizard / Citizen → **Mage**（已拍板 2026-08-30）

字符串面最广的一组，机械实测（2026-09-01 迁移后仍全量存活）：

| 层 | 现串 | 建议 | 风险 |
|----|------|------|------|
| 实体 | `wandscape_npc`（`Wandscape.java:276`）+ 刷怪蛋 `wandscape_npc_spawn_egg` + lang `entity.wandscape.wandscape_npc` + curios datapack `data/curios/curios/entities/wandscape_npc.json` + `FriendlyForce.AllyKind.WANDSCAPE_NPC` | `wandscape_mage` 等 | 存档断档 |
| 类 | `WandscapeNpc`（entity）/ `WandscapeNpcRenderer`/`Model` / `NpcApi`+`NpcApiImpl` / `NpcMenu`/`NpcScreen` / `NpcData`/`NpcDataImpl` / 7 个 `Npc*Packet` / `NpcDeathHandler` / `NpcTaskQueue` / `NpcStrategyMenu`/`NpcStrategyScreen` 等 | `WandscapeMage` 家族 | 无 |
| 菜单 id | `npc` / `npc_strategy`（`Wandscape.java:219,223`） | `mage` / `mage_strategy` | 菜单 id |
| 网络包 id | `npc_data` `npc_dismiss` `npc_open_equip` `npc_open_strategy` `npc_rename` `npc_strategy` `npc_toggle`（7 个） | `mage_*` | 协议键 |
| lang key | `gui.wandscape.npc.*`（en+zh 各 42 键，实测）/ `message.wandscape.npc.*` / `npc_count` / `npc_guide` / `npc_cast` | `gui.wandscape.mage.*` 等 | lang（玩家可见） |
| NBT | `npc_id`（BuildingSavedData）/ `npcId`（ColonyDeathRegistry, MageHutDataPacket）/ `npcLevel`（WandscapeNpc:1622） | `mage_id` / `mageId` / `mageLevel` | 存档断档 |
| SavedData | `wandscape_npc_deaths`（ColonyDeathRegistry:29） | `wandscape_mage_deaths` | 存档断档 |
| 外观 | `WizardHatLayer`/`WizardHatModel` + `wizard_hat.png` + 皮肤池 `textures/entity/wizard` + `WIZARD_SKIN_COUNT` | `MageHat*` + `textures/entity/mage` + `MAGE_SKIN_COUNT` | 资源路径 |
| 成就 | id `a_wizards_interest` vs 显示名 "A Mage's Interest" | id `a_mages_interest` | 注册 id/lang |
| 散文 | `gui.wandscape.scanner.node_desc` = "Citizen wizards channel elemental energy here"；lang `tourist_role_citizen` = "市民" | 改文案/键 | lang |
| 方法 | `getNpcName`/`generateRandomNpcName` 等 | 随类统一 | 无 |

> 注意：`content/npc/types/EquipmentSlot`（死码）里还挂着 `EquipmentSlot.WAND` 概念，若删除一并解决「法杖槽」命名。

### 3.2 法术 Magic / Cast / Spell → **Spell**

| 位置 | 问题 | 建议 | 字符串面 |
|------|------|------|---------|
| `content/magic/data/MagicDef` | 用 Magic，数据目录已是 `magic_spells`，类型多叫 Spell | `SpellDef` | 无 |
| `content/magic/internal/MagicCaster` + `MagicCastManager` | 两名字仅差 "Manager"，分属「施放」与「调度」 | `SpellCaster` + `SpellCastScheduler` | 无 |
| `content/magic/internal/CastBrain` | "Brain"+动词 Cast，实为选法术决策引擎 | `SpellBrain` / `SpellDecisionEngine` | 无 |
| `content/magic/internal/MagicSpellExecutors` | 一个类名塞入 Magic+Spell 两词根 | `SpellExecutors` | 无 |
| `content/magic/internal/SpellbookLoader` | 加载的是 `magic_spells` 目录 + `MagicDef` | `SpellDefLoader` | 无 |
| `content/items/SpellItem#MAGIC_ID_KEY="magic_id"` + `getMagicId`/`setMagicId` | NBT 键用 magic_id，与 Spell 词族不符 | `SPELL_ID_KEY="spell_id"` + `getSpellId` | **高：物品 CUSTOM_DATA 已进存档/背包；`craft_recipes/*.json` 的 `output.magic_id`；蓝图 `altar_cast` 的 `magic_id`；`MagicStationPacket` 字段；`ElementRecipeCollector` 直接 `putString("magic_id")`**（实测 magicId×145 + magic_id×17 vs spellId×64 + spell_id×3 并存） |
| `api/SpellcastingApi` + 实现 | 词族内 Spellcasting | `SpellApi`（可随族） | 无 |

### 3.3 玩家权杖 Wand / Scepter / MageWand → **Scepter**（建造法杖另定 MageWand）

| 位置 | 问题 | 建议 | 字符串面 |
|------|------|------|---------|
| `content/items/scepter/*`（ScepterApi/ScepterItem×4/OmniScepterItem/ScepterKind/ScepterMarksSavedData）vs 注册 id | 类/API 已统一 Scepter，注册 id 却还是 `peace_wand`/`follow_wand`/`shelter_wand`/`hostile_wand`/`carpenter_wand`（实测 5 个） | id 改 `*_scepter` | **高：注册 id + `craft_recipes/*.json` + `models/item/*.json` + lang `item.wandscape.*_wand.tooltip` + curios/tag** |
| `api/MageWandItem`（接口，被 ScepterItem/OmniScepterItem 实现） | 接口叫 MageWand（第三词），且带 `Item` 后缀实为交互钩子 | `ScepterInteractHook` | 无 |
| `content/items/wand/*`（`WandItem` + `WandApi` + id `wand` + `hasDefaultWand`） | NPC 建造法杖也叫 Wand，与玩家权杖 Scepter 并行 | 定 `MageWand` / `BuilderWand` | 注册 id `wand` + NBT |
| `content/items/scepter/.../ScepterKind` vs 方法/lang | 枚举叫 Kind，但 OmniScepterItem 全用 `getMode/setMode/cycleMode`，lang `mode.wandscape.scepter.*`，NBT 键 `"mode"` | `ScepterMode` | 类名无风险；改枚举常量破坏 CUSTOM_DATA `"mode"` 值 |

### 3.4 游客 Tourist / Visitor / Guest / Citizen → **Tourist**

| 位置 | 问题 | 建议 | 字符串面 |
|------|------|------|---------|
| `content/tourist/entity/ColonyVisitor` vs `TouristEntity` | 同实体两套名 | `ColonyTourist` / 标记接口 `TouristMarker` | 无 |
| `HotelStayHandler#occupancy`/`getGuestNames` + lang `gui.wandscape.hotel.guests` + 成就 `guest_of_honor`/`overnight_guest` | 住店游客叫 Guest | 统一 `tourist`（住客语义可保留，见 CLAUDE.md「Guest 旅馆住客语义保留」旧条目，需拍板） | lang/成就 id |
| 成就 `first_visitor` vs 文案 "Welcome your first tourist" | id 用 visitor，文案用 tourist | `first_tourist` | 注册 id/lang |
| lang `tourist_role_citizen` = "市民" | 角色名用 Citizen | `tourist_role_tourist` | lang |
| `MageResume#touristName`（`@SerializedName("tourist_name")`）+ `TavernApiImpl` 入参 | Mage 简历字段叫 tourist | `mageName` | JSON `tourist_name` / NBT `name` |

### 3.5 点/向量自造族（跨 road/building/task）

| 位置 | 问题 | 建议 | 字符串面 |
|------|------|------|---------|
| `content/road/core/PathPoint` ↔ `content/task/types/GridPos` | **同构 int3 出现两次**：`RoadNode.pos` 用 GridPos、`RoadEdge` 用 PathPoint，packet 显式 `new GridPos(p.x(),p.y(),p.z())` 互转；`xz()` 方法重复实现 | 并入 `GridPos`（road 改用） | 无（RoadSavedData 只落盘原始 x/y/z） |
| `content/road/core/RoadTemplate$RoadTemplateBlock` | 与 `BlockOffset`（building 域）同模式局部偏移 | 复用 `BlockOffset` / Javadoc 标注「局部模板坐标」 | JSON 模板 |
| `content/road/core/SplineVec3` | 见 §1.2 | `SplineVector3` | 无 |
| `content/road/core/XZPoint` | 自明，保留 | 保留 | 无 |

### 3.6 任务步骤 op 层 vs 引擎能力 Ops 层（双层撞词）

| 位置 | 问题 | 建议 | 字符串面 |
|------|------|------|---------|
| `content/task/op/api/AtomicOp` + 12 变体（TransformOp/BlockInteractOp/RitualOp/AltarCastOp/...） | 任务步骤叫 Op | `TaskStep` 词族 | 无（JSON 蓝图 `steps[].type` 是独立串，不随类名） |
| `content/task/op/executor/OpExecutor` / `OpExecutorRegistry` / `DefaultOpExecutors` | 任务步骤执行器叫 Executor | `StepHandler` / `StepHandlerRegistry` / `DefaultStepHandlers` | 无 |
| `content/task/boundary/*Ops` + `Wandscape*Ops`（BlockOps/EntityOps/RitualOps/MovementOps 及其 Wandscape 实现） | 引擎能力也叫 Ops，与 op 层撞词；实现再叠 Wandscape 前缀两级不一致 | `*Port` / `*Access` / 实现 `Mc*Ops` | 无 |
| `DefaultOpExecutors` 内部类 `ResourceRequestExecutor`/`TransformExecutor`/`BlockInteractExecutor` vs 顶层同名 | 同名双实现（内部同步类 vs 顶层引擎类），EngineBootstrap 里后者覆盖前者 | 三级命名 `XxxExecutor`/`DefaultXxxExecutor`/`EngineXxxExecutor` | 无 |
| `content/task/runtime/NpcTaskPackage` | "Package" 与 Java 包概念撞，且与 TaskRequest/GlobalTask/TaskSequence 关系不直观 | `NpcTaskUnit` / `AssignedTask` | 无 |

### 3.7 建筑扫描器 / 投影 / gizmo

| 位置 | 问题 | 建议 | 字符串面 |
|------|------|------|---------|
| `scanner/CreativeScannerBlock`(基/完整版) vs `scanner/ScannerBlock`(子/生存版)；BE/Screen 同病 | 继承倒置 + 双名 | 基 `BuildingScannerBlock`(+Entity/Screen)，子 `SurvivalBuildingScannerBlock`(+...) | 只改类名无风险；**切勿连带改注册 id**（`building_scanner`/`creative_building_scanner` 已写入存档/BE type/lang/blockstate） |
| `projection/client/BuildGizmoController`/`Renderer` vs `scanner/client/gizmo/ScannerGizmo*` | 同一 3D 轴 gizmo 两套双名，hitTest/drag/箭头几何逐行重复 | 抽共享 `AxisGizmo` 核心，统一一前缀 | 无（render-type id `build_gizmo_quads` 仓内） |
| 投影模式四前缀：`Projection*` / `Build*`（Gizmo/Placement/PopPanel）/ `Building*`（Debug*/SelectionOverlay）/ "soul projection" | 同一建造投影模式四种命名 | 定 `Projection` 唯一前缀：`BuildPopPanelOverlay`→`ProjectionPanelOverlay`、`BuildingDebug*`→`ProjectionDebug*`、`BuildGizmo*`→`ProjectionGizmo*` | lang `gui.wandscape.buildpop.*`（低危） |
| `projection/client/ConstructionScreen`（放置微调）vs `client/ConstructionSiteScreen`（工地材料） | 两个 Construction* Screen 易混 | `BuildingPlacementScreen` / 保留 | lang `gui.wandscape.construction.*`（低危） |
| `render/BuildingGhostRenderer`（VBO 幽灵）vs `client/ConstructionGhostRenderer`（施工足迹） | 两个 GhostRenderer 语义相邻 | 后者 `ConstructionFootprintRenderer` | 无 |
| `building/network/OpenWarehousePacket` vs `{Altar,Hotel,Shop,Tavern,TownHall}OpenPacket` | 同族 `XxxOpenPacket` 约定被 OpenWarehouse 反转 | `WarehouseOpenPacket` | 网络 id `open_warehouse`（运行期） |
| `scanner/client/ScannerClientHelper` | Helper 万能兜底，实为按 BE 类型开对应 Screen | `ScannerScreenOpener` | 无 |
| `content/building/preview/BuildingPreviewGifCache` | 非 GIF，是离屏烘焙 PNG 帧条（flipbook） | `BuildingPreviewFrameCache` / `ThumbnailCache` | 无（自管理磁盘缓存目录） |

### 3.8 TownHall 拼写（同一对象 6 种拼写）

| 位置 | 现拼写 | 建议 |
|------|--------|------|
| 类 `TownHall*`（Screen/OpenPacket/CreateScreen） | TownHall | 类保留 `TownHall` |
| lang `gui.wandscape.townhall.*` | `townhall` | `town_hall` |
| 网络 id `town_hall_open`/`town_hall_name_style`/`town_hall_warehouse_request` | `town_hall` | 保留 |
| 建筑 type id `townhall1`（datapack JSON + 已存建筑 NBT type + lang） | `townhall1` | 改 `town_hall_1`（**破坏存档**，需拍板） |
| 命令/注释 `townHall`（java 侧 49 处） | `townHall` | 统一拼写 |
| guide key `TOWN_HALL` | `TOWN_HALL` | 随 lang 统一 |

### 3.9 stats / metrics 三套并存

| 位置 | 问题 | 建议 |
|------|------|------|
| `colony/service/StatsService`（34 行空壳，onEvent 只有 TODO） | 空壳 + 注册订阅 | **删除**（属死代码，非改名） |
| `colony/stats/*`（StatisticsCollector/StatisticsData/ColonyStatsSummary/ColonyDailySnapshot） | 真统计 | 保留为一套 |
| `colony/service/ColonyMetricsService` + `ColonyMetricsSnapshot` | 聚合指标，与 Statistics 双轨 | 与上二选一并统一命名 |
| `colony/network/ColonyStatsSyncPacket` vs `colony/stats/network/StatsSyncPacket` | 两个 stats sync 包，都写 `WandscapePanelState` | 按负载改名 `ColonyMetricsSyncPacket` / `StatsSummarySyncPacket`；channel id `colony_stats_sync`/`stats_sync` 同步 |

### 3.10 网络包命名惯例

| 位置 | 问题 | 建议 | 字符串面 |
|------|------|------|---------|
| `npc/NpcStrategyPacket` vs `NpcOpenStrategyPacket` | 一个提交、一个开屏，名字仅差 Open | `NpcStrategySubmitPacket` / `NpcStrategyUpdatePacket` | 网络 id |
| `npc/NpcDataPacket` / `tourist/TouristDataPacket` | javadoc 自称「opens/updates the screen」，兼任开屏+数据同步 | 拆 Open + Data（可选） | 网络 id |
| `road/network/RoadStudioEnterPacket` vs `SplineEditorEnterPacket` | **两个包 handleClient 逐行相同**，同一功能两套名（"Road Studio" vs "Spline Editor"），客户端 `RoadStudioOverlay` 与 `SplineEditorClientState/Controller/Renderer` 并存 | 二合一，统一 `RoadStudio*` 或 `SplineEditor*` | 网络 id 仓内 |
| `task/network/TaskManagement*` vs `TaskPanel*` | 同一任务面板两套前缀 | 统一前缀 | 网络 id |
| `production/network/CraftingStationPacket`/`MagicStationPacket` vs `WorkstationDataPacket` | 同族后缀不一（两个无 Data、一个有） | 统一后缀 | 无 |

### 3.11 api 层命名惯例

| 位置 | 问题 | 建议 |
|------|------|------|
| `api/*ApiImpl` 三例外：`WarehouseApi→WarehouseManager`、`ColonyMetricsApi→ColonyMetricsService`、`GuideProgressApi→GuideProgressService` | 服务类无 *ApiImpl，惯例不齐 | 统一 *ApiImpl 或全 *Service |
| `api/HouseApi` | **无任何实现**（`getHouseApi()` 调用即抛） | 接线或删 |
| `WandscapeApis` 缺 `getXxxApiSilently` 变体 | 11 个 Api 风格不齐（House/Tavern/Road/Spellcasting 无 Silently） | 补齐 |
| `compat/curios/NpcCurioSlot`（单）vs `NpcCuriosMenu/Screen/Button/OpenPacket`（复） | 单复数不一致 | 统一单数 `NpcCurio*` |
| `compat/jei/ElementRecipe*` 5 类 vs `WandscapeJeiPlugin` | 模型名暗示游戏内配方，实为 JEI 展示模型；插件带 Jei | `JeiElementRecipe*` 或 `ElementRecipeView`（低优先，包已消歧） |
| `command/*` 19 个命令 | 下划线不齐（`audit_elements` vs `logfilter`/`roadstudio`）；工厂方法 `node()`/`buildNode()`/`fillNode()` 三样；`FillBuildingCommand.NAME="wandscape"` + 死 `register()` | 全下划线 + 统一 `node()` + 删死代码 | 无 |

### 3.12 其他一物多名

| 位置 | 问题 | 建议 | 字符串面 |
|------|------|------|---------|
| `content/items/ring/RingTier` vs `OathRing*` 家族（7 类） | 唯独枚举无 Oath 前缀 | `OathRingTier` | 无 |
| `content/items/compass/MagicCompassItem` vs `CompassTier/CompassService/...` + 注册 id `magic_compass*` | 物品带 Magic、其余不带、id 又带 | 统一（见 §1.2 近似的取舍） | lang 与注册 id 关系 |
| `content/npc/guard/ColonyDeathRegistry` + `ReviveHandler` + `NpcDeathHandler` | 同簇三套词 | 随 §3.1 统一 `MageDeathRegistry`/`ColonyReviveHandler`/`MageDeathHandler` | SavedData `wandscape_npc_deaths` |
| config record 群 `content/tourist/data/{Relax,Service,Shop,Wonder,Atm,Decoration}Config` + `BuildingConfig` 内嵌 + `CreativeScannerBlockEntity` 平铺字段/NBT | 同一套经营参数三处表达（record / BuildingConfig 字段 / BE 平铺 + 各自 NBT key） | record 作唯一真源，抽公共 `interactionDurationTicks`；NBT/JSON key 对齐 | BE NBT keys + datapack `@SerializedName` JSON keys（改则破坏存档） |
| `content/colony/ColonySavedData` vs `ColonyLevelData` | 同包两个持久化类，一个殖民地注册表一个等级经验，名易混 | `ColonyRegistryData` | 无（各自 DATA_NAME 解耦） |
| `foundation/ui/guide`（生产指南）vs `foundation/ui/guidance`（新手引导 GuideSession/GuideStep） | guide 与 guidance 两包语义撞车 | 指南包改 `guidebook` 或新手指引改 `onboarding` | 无 |

---

## 四、角度 4：命名质量重灾区（方法/字段级）

| 位置 | 问题 | 建议 | 字符串面 |
|------|------|------|---------|
| `content/task/types/ResourceId#getFuckPureResourceId_NotContainFuckedNBT()` | 脏话方法名，剥离 `[...]` 块状态后缀 | `stripBlockStateSuffix()` | 无（纯逻辑） |
|  — 调用点 | `WarehouseManager.java:169,191,215,230,250,273`（6）+ `Inventory.java:41,67,84`（3），共 **9 处**；`AsyncTransformExecutor.java:90,247` 还内联了同逻辑未走该方法 | 顺手收口到该方法 | — |
| `foundation/ui/bubble/IBubbleTextProvider` | 全模组唯一 `I` 前缀接口（C# 风格） | `BubbleTextProvider` | 无 |
| `api/NpcBindingItem` / `api/MageWandItem` | 带 `Item` 后缀实为交互钩子接口，易被误读为物品 | `NpcSneakInteractHook` / `NpcInteractHook` | 无 |
| `content/items/network/GuideTestPacket`（channel `guide_test`） | 调试用 "test" 包注册进生产网络 | 删或改名 `GuideDocOpenPacket` 并入正式链路 | 网络 id |
| `content/colony/service/StatsService` | 空壳 TODO 骨架注册订阅（死代码） | 删 | 无 |
| `content/task/scheduler/SystemBlueprintSystem` | "System" 重复两次 | `SystemBlueprintExecutor` / `SystemTaskDriver` | 无 |
| building/** 全域（53 文件） | `import ...content.task.ecs.World;` 死 import ≥50 处（IDE 自动补全污染） | 批量删死 import（随 §1.2 World 改名根治） | 无 |
| `content/npc/HostileTargetingHandler` | 事件订阅者落根包，其余 Handler 在 guard/ 或 internal/ | 挪包统一（顺带） | 无 |
| `content/tourist/internal/TavernApiImpl` | 酒馆 API 实现落在 tourist 包，与职责错位 | 挪 content/colony 或新建 content/tavern | 无 |

---

## 五、字符串面风险总表（改之前必看）

> 开发期不承诺存档兼容，但「断档」与「纯 Java 改名」要区分对待：**纯 Java 改名永远安全**，动串要先 grep 全量命中再拍板。

### A. 纯 Java / 无持久化面（安全，先做）
`System→EcsSystem`、`AttributeModifier→NpcAttributeModifier`、`EquipmentSlot 删除`、`I18n→Localization`（类名）、`Inventory→NpcInventory`、`Position→GridPosition`、`World→EcsWorld`、`SplineVec3→SplineVector3`、`WandscapeEngine`、`CoreBootstrap/EngineBootstrap`、`EnqueueHelper 拆分`、`CraftRecipeView`、`MiscRecipe`（仅类名）、`ProductionEligibility`、`WarehouseManager`、`RaidTownHall`、`AchievementService`、`ChunkLeaseManager`、`TaskExecutor`、`NpcData`、`ReviveHandler`、`ElementMaps`、`ParamTypeInfo`、`GuideTestScreen`、`LessButton/MoreButton`、`IBubbleTextProvider`、`NpcBindingItem/MageWandItem`、op/Step 词族、`*Ops→*Port`、`ScepterKind→ScepterMode`（保留常量）、`RingTier→OathRingTier`、`ColonyVisitor`、Scanner 类名（不动 id）、gizmo/投影前缀、`WarehouseOpenPacket`、`getFuckPureResourceId`、SystemBlueprintSystem、死 import、command 命名。

### B. 只影响注册 id / 网络通道（运行期，非持久，改需两端同步）
`npc_*` 网络包 id、`open_warehouse`→`warehouse_open`、`colony_stats_sync`/`stats_sync`、`task_management_*`/`task_panel_*`、`road_studio_enter`/`spline_editor_enter`、`GuideTestPacket` channel。

### C. 影响存档 / 玩家可见 / datapack（断档决策点，逐条拍板）
1. 实体 id `wandscape_npc`→`wandscape_mage`（+ 刷怪蛋 + curios datapack + lang + AllyKind）——**旧档失联**。
2. NBT `npc_id`/`npcId`/`npcLevel`、SavedData `wandscape_npc_deaths`。
3. lang `gui.wandscape.npc.*`（en+zh 各 42 键）→ `gui.wandscape.mage.*`。
4. `magic_id` NBT 键（SpellItem CUSTOM_DATA + craft_recipes JSON + altar_cast 蓝图 + MagicStationPacket + ElementRecipeCollector）→ `spell_id`。
5. 物品 id `*_wand`→`*_scepter`（+ recipes/models/lang/tag）。
6. `Activity` 枚举常量改名（blockstate 属性值 + NBT currentActivity + 扫描器 JSON）。
7. 建筑 type id `townhall1`→`town_hall_1`（datapack + 已存建筑 NBT type + lang）。
8. `ModifierOperation` 枚举常量（wand preset JSON `operation`）。
9. `ScepterKind` 枚举常量（OmniScepterItem CUSTOM_DATA `"mode"` 值）。
10. config record 群 NBT/JSON key。
11. `first_visitor`/`guest_of_honor`/`overnight_guest`/`a_wizards_interest` 成就 id + lang。
12. `tourist_name` JSON 字段、`tourist_role_citizen` lang。

---

## 六、执行顺序建议

1. **第一批（纯 Java，零风险）**：撞名 7 件（`System`/`AttributeModifier`/`EquipmentSlot` 删/`I18n`/`Inventory`/`Position`）+ 近似（`World`/`SplineVector3`）+ 角度 4 全部（含 `getFuckPureResourceId` 收口、死 import、`IBubbleTextProvider`）。一个 commit 一件，`./gradlew compileJava` 保绿。
2. **第二批（词族、无串面）**：op/Step 词族、`*Ops→*Port`、`ScepterKind→ScepterMode`、`RingTier→OathRingTier`、`CraftRecipeView`、`MiscRecipe` 类名、`EnqueueHelper` 拆分、投影/gizmo 前缀、`NpcData`/`TaskExecutor`/`ReviveHandler` 等。
3. **第三批（含网络通道 id，运行期低危）**：`OpenWarehousePacket`、stats 双包、TaskManagement/TaskPanel、RoadStudio/SplineEditor 二合一、`npc_*` 网络包 id。
4. **第四批（断档决策，逐条拍板后单独 commit）**：§五 C 全部——先出每条的 grep 全量命中清单，确认覆盖所有玩家可见/存档层引用，再改。
5. **贯穿**：每批三查（grep 旧名零命中、字符串 id 与代码一致、行数净减）；改动后 `./gradlew build` + `./gradlew test`（纯逻辑处）。
6. **测试不搬**：main 稳定后 src/test 单独大删（既有决策）。
