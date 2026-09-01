# Wandscape 认知地图（newplan/packages.md — tier0 临时）

> tier0 可信全项目文档摸底产物，**只读真实代码核实**，旧 `docs/` 仅在「坑」条对照标错、不作真相。
> 与真实顶层包 **29 个**对齐（plan.md 所述 30 为旧数，实际 29，已以代码为准；含 `scepter/`）。
> 这份地图**注定会删**：只为这次重构服务，重构落定随 `newplan/` 一并删。**简写优先，宁缺勿繁。**
> 规则：一个概念全地图只定义一次，其它节交叉引用；填不出的标「未探明」，绝不编。
> 本文档由批 1–8 节稿合并，已修正批间一致性（见文末【合并勘误】）；文末【全局结论表】= content/ 分包依据。
> 所有资源/核心数字经 grep + 实测核对，除注明外都逐字命中。

---

## 关键纠正（相对 plan.md / status.md 的过时断言）

1. **`core/types/NpcAttributes` 不是死码**：被 `core/CoreBootstrap`、`npc/EntityComponentBridge.defaults()` 引用。plan/status 的「0 引用死码」cite 已过期，Tier 1 删除候选**须避开 NpcAttributes**。
2. **`magic/internal/CastBrain` 不是死码**：被 `guard/GuardCombat`、`magic/SpellcastingApiImpl`、`npc/entity/*`、`compat` 等 12 文件引用，CastBrainTest 30+ 断言。plan「CastBrain 死代码」错误，误删会废掉守卫施法决策。
3. **`core/types/EquipmentPreset` 才是真死码**：全仓 0 引用（仅自身文件），Tier 1 删。
4. **`task/runtime/InterruptRecord` 活、`shared/data/InterruptRecord` 死**：同名双 record 别删错（后者是死码，前者被 `GlobalTask.interruptHistory` 用）。
5. **`magic` 不消费经济元素**：全 magic 包 grep 无 `shared.data.ElementType`（耗的是魔力/冷却）。plan「magic 接收 element」假设为假。
6. **`building`+`road` 等旧 docs 的「路由已删」「CastBrain 死」说法全与代码相悖**，见对应节。

---

# 全局结论表（content/ 分包依据）

| 顶层包 | 归属 | 一句话依据 |
|--------|------|-----------|
| building | 独立 `content/building`（含 scanner 子域） | BuildingSavedData 枢纽 + BuildingApiImpl 门面；重度依赖 engine/core/op/task/shared 桥层（重构清理点） |
| projection | 并入 building（placement 子域） | 客户端工具层深度依赖 building；唯一反向依赖是放错包的纯函数 BuildingRotation |
| overview | 独立 colony 管理面 / foundation-client-ui（**不并入 building**） | 跨 building/projection/road/npc/tourist 客户端总控；只被 Wandscape+Mixin 消费 |
| raid | 并入 guard/防御域（**不并入 building**） | import guard.GuardZone；只在市政厅定位时经 BuildingApi 碰 building；与 npc 零关系 |
| stats | 独立 `content/colony-stats` / 入 colony 管理面（**不并入 building**） | 纯事件驱动（ColonyEvaluation/Tourist\*/DailySettlement），零 building import（实测） |
| npc | 独立 `content/npc`（殖民地自动化核心） | WandscapeNpc 是核心实体（84 文件引用、63 包外）；**NPC 属性全套规则收敛进 npc 域唯一 `NpcAttributes`** |
| guard | 并入 npc（combat/defense 子包）或独立 `content/defense` | 14 类全以 WandscapeNpc 为对象、无实体/注册 id；Executor 注册进 world.opExecutors |
| scepter | 物品→items；系统层并入 npc（战斗 targeting 子模块） | ScepterApiImpl 被 npc isFriendlyForce + guard forcedHostile/isShelteredForAny 消费 |
| ring | 整包进 items（或极小 ring 模块） | OathRingService/SavedData 包外仅 Wandscape.java 初始化引用，无跨域消费 |
| tourist | 独立 content 域（经济/服务） | 纯消费→喂 colony（商利/元素产出/exp/mage 简历），自身不建不产；真移动状态机是 `TouristMoveGoal.MoveMode`，勿误当 `TouristState` |
| element | 独立基础域（元素值数据/查询层） | 不归 magic；「7元素」枚举 `ElementType` 在 shared/data，搬它得连搬 |
| production | 独立 content 域（配方/craft 层） | 元素经济的 craft 门面；钉在建筑任务流上（喂 `WorkItem`），不 import npc/task |
| road | 独立 content 域（保留） | 真图内核+样条+编辑器+持久化；深度绑 engine/task/warehouse/building 建造管线。**点/向量自造族保留非 vanilla**（Tier 3 裁决口，见 road 节） |
| wand | 并入 items（WandApi 面归 api/） | 纯数据驱动 item+preset，无系统内核（3 文件，无 tick/持久化） |
| compass | 并入 items（或留 colony 下） | 薄（静态 CompassService，无 SavedData/仿真/状态机）；代价 items 背 `raid.RaidTownHall` 单向只读依赖 |
| guidebook | 并入 items（UI→foundation/ui） | 极简零耦合，无系统内核（合并自批 4 与批 8 节稿，一致） |
| task | 独立 content 域 | 自动化引擎，纯逻辑零 MC 可单测，域内已按功能块切 |
| op | 并入 task（作 task/op 或 task/atomic） | 执行词汇只有任务引擎语境下有意义；执行器分散在 op/building/guard/engine.boundary，须收敛 |
| magic | 独立 content 域 | 施法系统(CastBrain/Caster/Beam)；不接经济元素；CastBrain 非死码 |
| warehouse | 独立 content 域 | 经济存储；兼 WarehouseApi 契约+ColonyResourceAccess 核心边界适配+双标签 GUI |
| shared | 拆散：log/network/ui(控件+markdown)/registry/data→foundation；面板/overlay/client/api 实现→随域或内联；死码删 | 桥层，混真公共基建+纯功能实现+搭桥死码；判定靠引用计数 |
| core | 拆散但不删活骨架：纯值类型+op/task 运行逻辑→各 content 域；活 ECS(World/System/6组件)→foundation 或 task 域（拆 god-object 职责，不删）；boundary 接口→保留接缝但跟运行内核走（非独立 core 层）；`ComponentStore`/`HashMapComponentStore`/`SuspensionContext`→内联；`EquipmentPreset`→删 | 零 MC 为真，但 World 直接耦合 task/op——core 是运行内核的"框"不是独立层；ECS 活且承重 |
| engine | 拆散：真 MC 适配(4 Ops 实现+2 ECS system)保留；跨域服务(sounds/nav/attributes/transport/chunkload)→各自域或 foundation；`WandscapeEngine`+`EngineBootstrap` 定位器桥→dissolve；`engine/boundary` 目录名→随归类删 | 纯 MC、只有 ~6 个真适配器，其余是装配/服务；定位器是 getXxx() 搭桥本体 |
| client(顶层) | 仅 1 文件 `TransportItemEntityRenderer`→挪 `engine/transport/client` | 无全局 client 层，client 早已按域分散（17 个 `*/client` 子包） |
| compat | 独立顶层包（保留） | 三集成全 compileOnly、门禁/插件发现隔离良好；唯一泄漏 `WandscapeNpc→IMagicSummon` 走接缝 |
| command | 各命令归各自域；debug 命令大清理 | 无基类/注册表，18 命令 12+ 是 debug 工具 |
| dataconfig | foundation | datapack JSON 注册/重载基建(WandscapeDataLoader) |
| mixin | 各归各自域(building/raid/overview/road) | 4 个全功能钩子、无一可删、已 common/client 分置 |
| gametest | 测试/工具桶 | ElementAuditRunner，构建期元素审计 |
| resources | element_mappings 单点泛滥；lang 分文件；guide 是 md | data 87% 在 element_mappings；lang 每语言 2031/2032 一坨；guide 52 md |

**跨批耦合事实（勿重定义，各节交叉引用）**：
- Mage/Npc 命名分裂波及全模组：`WandscapeNpc` 63 处包外引用 + 注册 id（`wandscape_npc`/`evil_mage`/刷怪蛋）+ lang key ~241 条 + SavedData 名（`wandscape_scepter_marks`/`wandscape_oath_rings`/`wandscape_npc_deaths`）——Tier 2 改名移动成本最高的一环，需先定统一词四层同步改。
- scepter/ring/guard/npc 的事件处理器、SavedData 初始化、OpExecutor、server-tick 循环**集中在 `Wandscape.java` 显式接线**（bootstrap / onServerTick / SavedData），不是自动装配——拆包时接线点跟着迁。

---

# 批 1 建筑域（building projection overview raid stats）

## building
- **职责**：殖民地**建筑内核**——注册/注销、持久化、空间索引（pos/chunk）、贡献/评估、任务队列、右键交互分流、建造/拆除生命周期。数据**完全数据驱动**：`BuildingConfig` 每类型一个 JSON（`data/wandscape/buildings/<id>.json`），`category` 决定适用配置段（basic/node/decoration/wonder/shop/service/relax/atm/workstation/crafting_station/magic_station）——**不是每类一个类**。category 的 comfort/magic/wonder 即该建筑的「元素值」贡献。
- **改它先看**：`building/internal/BuildingSavedData`（1109 行，状态/三索引/贡献注册表/持久化唯一枢纽）+ `building/internal/BuildingApiImpl`（对外唯一门面，实现 `shared.api.BuildingApi`）。
- **数据流**：IN——放置工具（projection 客户端 / scanner 导出 / command）→ `EnqueueHelper.registerIfAbsent` → 注册进 BuildingSavedData；NPC 建造引擎（engine 的 BuildingTaskSource）读其任务队列。OUT——任务队列 `WorkItem` → engine/op/task；`BuildingContributionRegistry` 在整建筑 0↔1 翻转发 `ColonyEvaluationChangedEvent` → stats/engine；`BuildingApiImpl` 暴露 `BuildingData` 给 npc/tourist/production/road/projection；右键 `BuildingInteractHandler` 发各 GUI 包给客户端。
- **依赖**：吸干 `engine.*`、`core.ecs.World`/`core.component.ColonyMember`/`core.types.*`、`op.api.AtomicOp`/`op.executor.OpExecutor`、`task.engine.pool.{GlobalTask,TaskRequest}`、`npc.*`、`magic.*`、`production.*`、`warehouse.ColonyItemBank`、`road.network`、`projection.BuildingRotation`、`shared.*`。**building 几乎是三桥层 + op/task 的最大消费方**，重构要拆的纠缠点。
- **坑/旧文档矛盾**：① 建筑后端不是每类一个类（就是一张 Config 表）；**只有客户端 Screen 每类一个**（MageHutScreen/ShopScreen/NodeScreen/AltarScreen/TavernScreen/HotelScreen/TownHallScreen…）+ `BuildingInteractHandler` 里大 `switch(category)`——正是 CLAUDE.md「UI 堆积 / 加个按钮改每个」。② building↔projection 成**环**：building 用 `projection.BuildingRotation`（唯一反向依赖），而 BuildingRotation 本是建筑数据旋转数学、放错包。③ 建筑自身未重定义属性/元素公式，但它消费 `shared.data.{MageHutResident,MageHutAttributes,MageAttributeRoller,ShopGoodDef,BuildingData,...}`——「一物多定义」源头在 shared.data，building 是最大受害者/消费者。
- **归属**：独立 `content/building`（殖民地建筑内核，非周围域附属）。scanner 子域归入 building。对 engine/core/op/task/shared 桥层的依赖是重构清理重点。
- **scanner 子域**（building/scanner）：蓝本导出/方块扫描工具，把世界方块扫成 `BuildingConfig` JSON 注册即刻可建（作者工具）。产出给 `BuildingConfigLoader.registerFromJson` 与 `RoadPresetLoader`；依赖仅 `building.data.BlockOffset` + 自身 + shared.ui/shared.data，**不碰** building 运行时。归属 building（可降为 building 下子目录）。

## projection
- **职责**：玩家**建造模式**——落点解析（`BuildPlacement.resolve`，纯逻辑可测）、建筑整体旋转（`BuildingRotation`）、中心对齐（`BuildingCentering`）、鬼影预览 + 飞行相机 + 旋转放置（projection client）+ 网络包。就是「把建筑蓝图放进世界的预览/放置」环节。
- **改它先看**：`projection/client/ProjectionClientState`（客户端投影模式唯一状态持有者）+ `projection/BuildingRotation`（被 building 双向引用的纯旋转工具）。
- **数据流**：IN——server `ProjectionEnterResponsePacket` 喂 building slots；`BuildingConfigLoader`（building）给选中建筑配置；`BuildingApiImpl` 是放置后端。OUT——`ProjectionPlacePacket`/`BuildingActionPacket` → 服务端 `BuildingApiImpl.placeBuilding`；客户端 ghost 用 `BuildPlacement`+`BuildingRotation`+`BuildingCentering` 算锚点。
- **依赖**：深度依赖 `building.data.{BlockOffset,BuildingConfig}` + `building.internal.{BuildingConfigLoader,BuildingState,BuildingSavedData,BuildingRepairHandler}`、`engine.service.SoundService`+`engine.sound.WandscapeSounds`、`road.client.RoadPlacementController`、`shared.*`。**是 building 的客户端建造工具层**。
- **坑/旧文档矛盾**：与 building 成环——building（持久化/API）依赖 `projection.BuildingRotation`（BuildingSavedData/BuildingApiImpl）。环的真身只是这一个放错包的纯函数。
- **归属**：**并入 building**（作 building/placement 或 building/projection 子域）。本质是 building 内容的客户端工具，重度依赖 building Config/State/SavedData；唯一反向依赖（BuildingRotation）本就是建筑数据数学、应下沉到 building.data。客户端/网络子域与 building 的 client/network 合并，不必保留独立顶层包。

## overview
- **职责**：殖民地**全局鸟瞰控制面板**——一套客户端飞行相机 + 交互总控，把建筑/NPC/游客/道路/工地用右键拉起对应 GUI。`OverviewFlightController` 逐帧驱动 WASD 飞行、鼠标视角、射线挑选目标、点击分发；`OverviewRenderer` 渲染边界/框；`OverviewClientState` 存相机与目标状态。
- **改它先看**：`overview/client/OverviewFlightController`（行为入口）+ `overview/client/OverviewClientState`（相机/目标/激活态唯一状态源）。
- **数据流**：IN——读 `ProjectionClientState`（是否投射/鬼影）、`RoadPlacementState`（是否画路）、`BuildingAreaSyncPacket`/`RoadAreaSyncPacket`（服务端同步的区域框）、`TaskManagementClientState`（跟踪的 Mage）。OUT——`OverviewInteractPacket`/`OverviewEntityInteractPacket` → 服务端 `BuildingInteractHandler`/npc/tourist 响应开 GUI。
- **依赖**：`building.internal`、`projection.client`、`road.client`、`npc.entity.WandscapeNpc`、`tourist.entity.TouristEntity`、`engine.service/sound`、`shared.ui.panel.{WandscapePanelState,WandscapePanelController,...}`。**横跨 building/projection/road/npc/tourist 多域的客户端管理/控制台**。
- **坑/旧文档矛盾**：overview 同时依赖 projection 与 road 客户端模式且与它们互相让路（`if !RoadPlacementState.isProjecting()`、处理 SplineEditor/RoadStudio 抢占）——它不是孤岛，是殖民地面板客户端总闸。只被 Wandscape/WandscapeClient + mixin 消费。
- **归属**：**不建议并入 building**——塞进 building 会让它背 npc/tourist/road 客户端依赖与面板状态。更接近「殖民地全局管理 UI」。建议归 `content/colony`（或 `content/overview`）管理面；否则归 **foundation/client-ui**。

## raid
- **职责**：殖民地**防御**——把原版 `Raid` 触发/接入殖民地。`RaidTriggerScanner`（玩家带不祥之兆靠近非停摆建筑 10 格 → 市政厅中心创建原版袭击）、`RaidTownHall`（市政厅定位 + 让原版 `ServerLevel.isVillage` 在市政厅范围内 true，配合 MixinServerLevel）、`ColonyRaidTracker`（轮询原版 Raid、胜利时广播 `ColonyRaidVictoryEvent`）。三文件共 ~200 行，都很小。
- **改它先看**：`raid/RaidTriggerScanner`（触发源）+ `raid/RaidTownHall`（市政厅锚点，防御中心）。
- **数据流**：IN——`WandscapeApis.getBuildingApi()`（取市政厅/建筑）+ `guard.GuardZone`（防御区）+ Config。OUT——发 `ColonyRaidStartedEvent`/`ColonyRaidVictoryEvent`（shared.event）；被 compass 与 mixin 引用。
- **依赖**：`guard.GuardZone`、`engine.service.ParticleService`、`shared.data.BuildingData` + `shared.event.{ColonyRaidStartedEvent,ColonyRaidVictoryEvent}` + `shared.registry.{WandscapeApis,WandscapeConstants}`、Config。**注意：不 import npc**（实测仅 import guard.GuardZone 一个对外类）。
- **坑/旧文档矛盾**：假说「raid 归 npc/guard 还是 building」——代码给答案：**import guard（防御域）**，只在市政厅定位时经 `WandscapeApis.getBuildingApi` 碰 building；与 npc 零关系。袭击本体持久化由原版 `Raids` SavedData 负责，本包只做事件/触发侧跟踪。
- **归属**：**并入 guard/防御域**（raid 是防御触发层），置信度中。与 building 耦合仅「找市政府」一行，靠 `shared.api.BuildingApi` 保持松耦合，无需并入 building。若计划有 `content/defense`，raid 是它的触发层。

## stats
- **职责**：殖民地**报表/统计层**。`StatisticsCollector` 订阅域事件、维护每殖民地当日游客流量与评估值，在结算边界写入 `ColonyDailySnapshot`，并把 `StatsSyncPacket` 推给打开面板的玩家。`StatisticsData` 是持久化 SavedData，存 30 天滚动快照。
- **改它先看**：`stats/internal/StatisticsCollector`（事件聚合+推送逻辑）+ `stats/internal/StatisticsData`（快照存储）。
- **数据流**：IN（只靠事件，无直接 import）——`DailySettlementEvent`、`TouristArrivedEvent`、`TouristDepartedEvent`、`ColonyEvaluationChangedEvent`（后者正是 building 的 `BuildingContributionRegistry` 发的：building→event→stats；`Tourist*` 来自 tourist 域）。OUT——`ColonyStatsSummary` → `StatsSyncPacket` 推殖民地面板玩家。
- **依赖**：`shared.api.ColonyApi`、`shared.event.*`、`shared.network.PanelStateTracker`、`shared.registry.WandscapeApis`、`shared.ui.panel.WandscapePanelState`、own `stats.data/network`。**零 `building` import（实测 grep 空，这是最重要的事实）**。
- **坑/旧文档矛盾**：假说「stats 归 building」**不成立**——与 building 无代码级 import，只有事件级耦合；它是纯粹的「殖民地级分析/上报」子系统，整个包只被 `Wandscape.java` 注册一次（反向引用仅 1 文件）。
- **归属**：独立 `content/colony-stats`（或并入 colony/overview 管理面），置信度高。塞进 building 会让 building 背上报表持久化；它订阅 building 与 tourist 事件、用 ColonyApi 取数据，是殖民地横切报表层。

---

# 批 2 法师/市民域（npc guard ring scepter）

## npc
- **职责**：殖民地**NPC 实体域**。核心 `WandscapeNpc`（`extends PathfinderMob implements PlayerLike`，1922 行）——殖民地法师的**外观/寻路/NBT 壳**，全部逻辑（魔力/调度/任务执行）经 `EntityComponentBridge` 桥进 ECS 的 core/engine。另有敌对测试法师 `EvilMage`（`extends WandscapeNpc implements Enemy`，`isColonyNpc()=false`，不进 ECS/不留死亡记录/不进小镇）与施法 goal `EvilMageCastGoal`。周边：`internal`（EntityComponentBridge 单例桥、NpcApiImpl、NpcDeathHandler 死亡快照、ReviveHandler 复活/全灭保底、ColonyDeathRegistry 死亡留存）、`data`（`DeathRecord` 纯 record、NpcDataImpl）、`network`（7 个包）、`NpcMenu`/`NpcStrategyMenu`（装备/策略菜单）、`client`（NpcScreen/NpcStrategyScreen/渲染/模型/帽子层）。
- **改它先看**：`npc/entity/WandscapeNpc`（实体本体）+ `npc/internal/EntityComponentBridge`（npc↔ECS 唯一桥）。
- **数据流**：IN——引擎（ECS 任务/调度）、玩家右键/菜单（`mobInteract`，WandscapeNpc.java:1372-1401）、guard 战斗（仇恨/跟随攻击/自防御标记）。OUT——EntityComponentBridge ↔ 唯一 `World`（ECS）；`NpcApi`（NpcApiImpl）；ColonyDeathRegistry → ReviveHandler；NpcDataPacket → 客户端屏幕。
- **依赖**：极广——`core.*`（component/ecs/types/attributes）、`engine.*`（WandscapeEngine/WandscapeAttributes/WandscapeNavigation/...）、`magic.*`、`task.*`、`building.internal`（ReviveHandler 用 BuildingSavedData/BuildingState）、`warehouse.ColonyItemBank`、`shared.api/entity/data/registry/log`、`compat.ironspellbooks`、`wand.item.WandItem`。
- **坑/旧文档矛盾**：① **命名灾难实锤**：NpcXxx（NpcMenu/NpcDataImpl/NpcDeathHandler/ColonyDeathRegistry...）与 MageXxx（`shared.api.MageWandItem`、`shared.data.MageHutAttributes`/`MageHutResident`、`EvilMage`/`EvilMageCastGoal`）共存无规律；类 `WandscapeNpc` 自己混用 "Wandscape"+"Npc"。② 注册 id 分裂：实体 `wandscape_npc`（Wandscape.java:228）vs `evil_mage`（:236）；刷怪蛋分裂；lang key 分裂。③ **`core/types/NpcAttributes` 不是死码**（见【关键纠正】）——plan/status 的「0 引用死码」与代码矛盾，属文档漂移，Tier 1 删除候选要避开。
- **🔴 NPC 属性全地图唯一收敛点（审核重点，勿拆散）**：NPC 属性全套规则目前**散在 5 个类**且**已漂移**，必须收敛进 **npc 域唯一命名类 `NpcAttributes`**（CLAUDE.md「一个概念的全套规则/常量/公式收敛进该功能域唯一一个命名类」）：
  - **属性种类**：`core/types/AttributeType`（9 枚举：MAX_HP/MOVE_SPEED/.../MANA_REGEN）。
  - **基础上下界 + 每级加成 + 训练步进 + 升级公式**：`shared/data/MageHutAttributes`（`SPECS` 的 `AttrSpec(lower,upper,perLevel,trainStep)`；`computeEffective = base + perLevel×(level−1) + equipBonus`；含 TRAIN/UPGRADE 成本模型、TRAIN_ELEMENTS）。
  - **默认值**：`core/types/NpcAttributes.defaults()` = (30, 0.3, 1, 1, 1, 5, 200)，与 SPECS 中点不一致（第三套源）。
  - **基础属性 roll**：`shared/data/MageAttributeRoller.roll(level, random) → RecruitmentCandidate`，**重新硬编码了 SPECS 数字**。
  - **招募计算**：`shared/data/RecruitmentCandidate`（纯 record）+ roll 生成；`MageResume`/`TavernRecruitStorage`/`TavernApiImpl` 消费。
  - **MC 桥**：`engine/attribute/WandscapeAttributes`（注册 6 个 MC attribute + 桥 core.AttributeType↔vanilla Holder）——这是「纯逻辑↔MC」适配层，**单独保留合法**，不算重复。
  - **⚠️ 已发生的漂移**：`MageAttributeRoller` 给 MOVE_SPEED 每级 +0.02、ARMOR_VALUE 每级 +0.5，但 `MageHutAttributes.SPECS` 声明这两项 `perLevel=0`——**同一「每级加成」两处不同**。这正是拆多文件必然漂移的实证，是收敛的硬理由。
  - **收敛动作**：全套规则归 `NpcAttributes`；`MageAttributeRoller.roll` 改为调用 NpcAttributes 的 lower/upper/perLevel 组合（**招募计算=基础 roll + 升级提升，勿独立成类**）；`AttributeType`、`MageHutAttributes`、`MageAttributeRoller`、`RecruitmentCandidate` 全部并入 npc 域，同域别处只引用不清写。
  - **注意**：`MageHutAttributes`/`MageAttributeRoller`/`MageHutResident` 现状在 `shared/data`，被 building/tourist/production 等消费——收敛时须把这些消费方的调用点一并改到 npc 域的 NpcAttributes（增量归属约束：不许在 shared 新写，且旧 shared 只清不增）。
- **归属**：独立顶层 `content/npc`（殖民地自动化核心）。`WandscapeNpc` 是**已证实核心实体**：84 文件引用它、其中 63 在 npc 包外（building/tourist/task/magic/warehouse/compat/engine 全依赖）→ 砍/改名 = 全模组波及。客户端/网络/菜单按目标形态归 `foundation/ui` + `foundation/networking`，但当前留在 npc 域。

## guard
- **职责**：**NPC 战斗/防御行为层**，全部以 `WandscapeNpc` 为对象做战斗，**无独立实体、无注册 id**。事件钩子（NpcSpellPowerHandler 伤害倍率、SelfDefenseHandler 受击记仇+环境伤害传送、FollowAttackHandler 玩家攻击→标记跟随战斗目标）、行为（ProjectileDodge 投掷物躲避、NpcEscapeTeleport 传送逃生、GuardScanner/GuardZone 建筑包围盒扩展守卫区）、任务（GuardTaskSource 扫攻击区发 `guard:attack`、GuardBlueprints 注册蓝图、GuardCommand 调试）、战斗引擎（GuardCombat 共用 + GuardAttackExecutor + SelfDefenseExecutor 两条循环）。
- **改它先看**：`guard/executor/GuardCombat`（共用战斗引擎，静态）+ `guard/executor/GuardAttackExecutor`（守卫循环，OpExecutor&lt;AttackMonsterOp&gt;）。
- **数据流**：IN——`Wandscape.onServerTick` 调 `guardExec.tickAll()`/`selfDefenseExec.tick()`/`ProjectileDodge.tick()`；`EngineBootstrap` 加 `GuardTaskSource` + 注册两个 OpExecutor；事件总线注册钩子。OUT——`guard:attack`/`self_defense` 任务包 → TaskExecutor/NpcTaskQueue；对 `WandscapeNpc` 施加战斗行为/仇恨/走位；读 scepter 的 `forcedHostile`/`isShelteredForAny`。
- **依赖**：`npc.entity.WandscapeNpc`+`npc.internal.EntityComponentBridge`、`core.ecs.World`+`core.component.{NavigationState,TaskExecutor,NpcTaskQueue}`、`task.engine.pool/dsl/runtime`、`op.api.AtomicOp`+`op.executor.OpExecutor`、`magic.*`（MagicBeamEntity/MagicCaster/CastBrain/MagicSpellExecutors/SpellbookLoader）、`building`（经 `shared.api.BuildingApi`+`shared.data.BuildingData`，GuardScanner 用）、`shared.registry.WandscapeApis`、`shared.log.Log`、Config。
- **坑/旧文档矛盾**：① `GuardCombat`/执行器是 **MC 绑定**（依赖 Level/MagicBeamEntity/Enemy/ECS World），**不是**可单测的纯逻辑战斗引擎；仅 `GuardZone`、`ProjectileDodge.willHit`、`GuardCombat` 部分几何是纯数学段（已有 Test）。② guard 任务 `colonyId=null`（GuardTaskSource.java:76）——守卫区是「全殖民地建筑包围盒并集」，可能横跨多镇，由最近真实 NPC 接取。这解释了 guard 为何**跨建筑与 npc 两域**。
- **归属**：**并入 npc**（作 npc/combat 或 npc/defense 子包），或升独立 `content/defense`（若想拉开「殖民防守」声量）。14 类几乎全 import WandscapeNpc/EntityComponentBridge，是 npc 的行为扩展，非独立实体。

## scepter
- **职责**：玩家**权杖**物品域，同时含一个**真实持久化系统**。物品层：`ScepterItem`（单模式：和平/跟随/庇护/敌对四把）、`OmniScepterItem`（一杖四模式 shift+右键循环）、`ScepterKind`（四模式枚举 + 物品 id + 头部主题色）。系统层：`ScepterMarks`（纯存储，按殖民地存「庇护名单 + 单槽强制仇恨」）、`ScepterMarksSavedData`（SavedData `wandscape_scepter_marks`）、`ScepterService`（服务端业务：应用右键命令 + 殖民地归属校验）、`ScepterInteractHandler`（EntityInteract，非法师生物）、`ScepterDeathHandler`（LivingDeath，目标死亡清标记）、`ScepterApiImpl`（实现 `ScepterApi`）。
- **改它先看**：`scepter/internal/ScepterService`（命令应用主逻辑）+ `scepter/internal/ScepterMarks`（状态本体，纯 Java 可单测）。
- **数据流**：IN——玩家右键物品（`ScepterItem.onInteractNpc`/`OmniScepterItem.onInteractNpc`/`ScepterInteractHandler.onEntityInteract`）→ `ScepterService`。OUT——`ScepterMarksSavedData`（持久化）→ 经 `ScepterApi` 被 **npc** 与 **guard** 读取：`WandscapeNpc.isFriendlyForce`→`isSheltered`（WandscapeNpc.java:261）、`GuardTaskSource.findThreat`→`isShelteredForAny`（GuardTaskSource.java:90）、`GuardAttackExecutor`/`SelfDefenseExecutor`→`forcedHostile`。**它是玩家指挥殖民地战斗的 targeting 子系统**。
- **依赖**：`npc.entity.WandscapeNpc`、`shared.api.{MageWandItem,NpcBindingItem,ScepterApi}`、`shared.registry.WandscapeApis`（getColonyApiSilently 校验殖民地）、`shared.log.Log`、`net.minecraft.nbt.*`。
- **坑/旧文档矛盾**：① `ScepterMarks` 注释自称「纯 Java 零 MC 依赖可单测」，但**实 import `net.minecraft.nbt.*`**——NBT 是 MC 类；若按「纯逻辑不 import MC」硬边界是轻微违规（NBT 是纯序列化、单测仍可行，但注释言过其实）。② 命名混用起点：`OmniScepterItem` 同时实现 `MageWandItem`（Mage）+ `NpcBindingItem`（Npc）两套命名接口。
- **归属**：物品类（`ScepterItem`/`OmniScepterItem`/`ScepterKind`）→ **items**。系统层是**真实系统**（持久化 SavedData + 被 npc/guard 战斗逻辑消费）→ **并入 npc**（玩家指挥殖民地战斗的 targeting 子模块）。**不能进 items**——否则 npc/guard 会反向依赖 items 域 = 反模式。

## ring
- **职责**：**盟誓戒指**物品域——把法师 NPC 收入固定槽、右键放出，是「只服务物品本身」的自成一体的 capture/release 系统。物品：`OathRingItem`（shift+右键收法师、右键地面/空气放、三档 `RingTier`）。系统：`OathRingStorage`（纯逻辑固定槽 0~3，存整份法师实体 NBT）、`OathRingSavedData`（SavedData `wandscape_oath_rings`，按玩家 UUID 键控）、`OathRingService`（存取 + 殖民地校验 + 安全落点搜索，用 `Wandscape.WANDSCAPE_NPC.get().create().load(nbt)` 复活法师）、`OathRingSyncHandler`（登录推占用掩码）、`OathRingClientData`（客户端掩码缓存）、`OathRingDataPacket`（net）。
- **改它先看**：`ring/internal/OathRingService`（存取业务）+ `ring/internal/OathRingStorage`（槽位语义，纯逻辑可单测）。
- **数据流**：IN——玩家右键 `OathRingItem` → `OathRingService.tryStore/tryRelease`。OUT——`OathRingSavedData`（持久化，玩家→固定槽法师 NBT）→ **仅回给 `OathRingItem`（tooltip 数量，经 OathRingClientData）与登录同步**。**没有任何其它域读取此存储**（包外仅 `Wandscape.java` 初始化引用）。仅存/取整份法师 NBT，不解析内容。
- **依赖**：`npc.entity.WandscapeNpc`、`Wandscape.WANDSCAPE_NPC`、`shared.api.NpcBindingItem`、`shared.registry.WandscapeApis`、`shared.log.Log`、`net.minecraft.nbt.*`、own `ring.client`/`ring.network`。
- **坑/旧文档矛盾**：同 scepter 命名混用（`OathRingItem` 实现 `NpcBindingItem`，Npc 命名）。ring 系统**不指挥 NPC 行为、只是实体搬运**——与 scepter 的「指挥战斗」本质不同。
- **归属**：`OathRingItem`/`RingTier` → **items**。系统层是**纯物品配套**（只服务 `OathRingItem`，无跨域消费）→ **整包并入 items** 或收敛为 `content/` 下一个极小 ring 模块。**与 scepter 不同**：ring 不宜并入 npc 行为层（它只是存取实体，不是指挥战斗）。

---

# 批 3 游客/经济域（tourist element production）

## tourist
- **职责**：短居访客经济域——非居民（无 Profession/Bed/Workplace/Home/StoredCitizen，仅 `isMage()`+外观区分）。沿 `road` 出行 → 访 shop/service/relax/atm/hotel 建筑 → 补三条状态条（Comfort/Magic/Wonder）→ 花钱/取钱 → 酒店过夜 → 离城；满足离城 +colony exp；mage 游客(5%)存档简历供 tavern 招募。
- **改它先看**：`tourist/internal/TouristSimSystem.java`（枢纽，同时驱动 loaded 实体与 shadow）+ `TouristSimulation.java`（共享经济逻辑，操作 `TouristStateHost`）。`TouristSpawnSystem` 是喂料器；`TouristMoveGoal` 驱动 loaded 实体移动 AI。
- **数据流**：IN——`TouristSpawnSystem` 每日计划：`BuildingApi.getColonyBuildings`（完好 shop/service/relax/atm 目标）+ `RoadApi` 网络端点 + `ColonyApi`/`ColonyLevelManager`（等级→spawn 数量与分布）；无玩家观察时 `TouristSimSystem` 推进 shadow。OUT——`TouristSimulation.performShopInteraction`→`ShopInteractionHandler`/`ShopStockManager`→`ColonyItemBank`（商店利润）；`performServiceInteraction`→`cfg.service().elementOutput()`→`ColonyItemBank.addElement`；离城 `grantExperience`→`ColonyLevelManager`；`storeMageResume`→`TavernApi.receiveMageResume`(`TavernRecruitStorage`)；`TouristApiImpl.registerDeparture`→`TouristDepartedEvent`。
- **依赖**：`building.internal` + `ShopInteractionHandler`/`ShopStockManager` + `building.scanner.InteractSpotMarkerBlock`；`engine.{WandscapeEngine,ColonyActivation,ColonyLevelManager,ChunkLoadManager}` + `engine.nav.WandscapeNavigation` + `engine.service.ParticleService`；`road.core` + `road.engine.WandscapeTags`；`projection.BuildingRotation`；`warehouse.ColonyItemBank`；`shared.*`。**不直接 import npc/task/magic/element**（实测 grep 空；只经 warehouse + service `elementOutput` + `TavernApiImpl` 触元素）。
- **坑/旧文档矛盾**：(a) `TouristState` 确只是移动标签（VISITING/EXPLORING/WANDERING/IDLE/SLEEPING，是 `TouristMoveGoal.mapModeToState` 的单向镜像）——「无状态机」只在 `TouristState` 层成立；**真正的移动状态机是 `TouristMoveGoal.MoveMode`**（VISITING_BUILDING/EXPLORING_POI/WANDERING），IDLE/SLEEPING 无 MoveMode 对应。仍无常驻市民概念。(b) 「离线影子仿真」并非仅离线：`TouristSimSystem` 每 tick 在线运行，无玩家观察（模拟距离内）就驱动 shadow，刻意忽略 chunk 状态（spawn chunk 玩家远离仍常驻）。shadow 在 chunk 卸载后存活，持久化 `TouristSimRegistry`(SavedData `wandscape_tourist_sim`)。「离线」只体现为 `ColonyActivation.isColonyActive` 冻结门（创始人离线→殖民地冻结）+ `offlineIncomeMultiplier` 削商利/服务产出/exp。(c) 旧 `TouristApiImpl.spawnTourist` 是 stub（"Phase C"占位）——实际 spawn 只在 `TouristSpawnSystem`。
- **归属**：纯消费/服务经济域，向外喂 colony（商利、服务元素产出、colony exp、mage 简历），向内消耗 colony 货物/元素/酒店容量——自身不建不产，独立但重度耦合 building+warehouse+engine+road。

## element
- **职责**：方块/物品→元素映射 + **元素值数据层**（非仿真/状态机）。定义每方块/物品在 7 元素上的 worth（`build_cost`），驱动建筑成本（`EnqueueHelper`）、工作站分解/合成、商店售出利润、`element_<id>` 物品 token。经济的 consume/produce 在 `warehouse.ColonyItemBank` / `building.EnqueueHelper` / `production.executeSynthesize` / `engine`。
- **改它先看**：`element/internal/ElementMappingLoader.java`（持全部配置注册表 + 查询；`ElementApiImpl` 只是委托它）+ `Wandscape.java:198-199,531` 接线（`ELEMENT_MAPPING_LOADER`/`ELEMENT_API`）。`ElementValueGenerator`/`ElementAuditor` 是 dev/gametest 工具，非运行时。
- **数据流**：IN——`dataconfig.internal.WandscapeDataLoader` 注册 `element_mappings` 类别（`ElementMappingLoader` ctor）；`ElementValueGenerator` 反向从 MC `RecipeManager` + `element_seeds.json` 推导值（由 `command.GenerateElementMappingsCommand` 驱动）。OUT——`shared.api.ElementApi`（经 `WandscapeApis.getElementApi()` 发布）；`ElementItem.inventoryTick` → `WarehouseApi.addElement`（玩家持它且在该殖民地内）。
- **依赖**：`dataconfig.internal.WandscapeDataLoader`、`shared.registry.WandscapeDataRegistry`、`shared.api.{ElementApi,ColonyApi,WarehouseApi}`、`shared.data.ElementType`、`engine.service.SoundService`/`engine.sound.WandscapeSounds`（仅 `ElementItem` 的声效）。**不直接 import magic/production/building/warehouse**。
- **坑/旧文档矛盾**：「7 元素」归 `shared.data.ElementType`（枚举 EARTH/WOOD/WATER/FIRE/METAL/WIND/DARK）所有，**不在 element 包**——若把 element 并入 magic 得连这个 shared.data 枚举一起搬。`ElementMappingLoader.getAllConfigs()` 过滤 `disabled` 映射（排除出合成/分解/审计）。`ElementValueGenerator` 读 MC `RecipeType` 来**推导**值——是开发期值生成器，非线上玩法逻辑。**命名撞车（交叉引用 magic 节）**：magic 有自己 `Element`/`ElementType`（法阵绘图形状 RING/ARC/POLYGON/STAR/GLYPH，见 `MagicCircleSpec`），与经济 `shared/data/ElementType` **同名但无关，勿合并**；且 magic 不消费经济元素（grep 无 `shared.data.ElementType`）。
- **归属**：真正的跨切数据/查询 + 工具层（被 building/road/engine/tourist/production 经 `ElementApi` 消费），独立于 magic——**保持自身基础域，不归 magic**。

## production
- **职责**：配方式生产——按建筑配方式收成物品。`craft_recipes` JSON 配方（wand/potion/spell/misc）+ 运行时由元素映射推导的合成配方，被殖民地级解锁（`RecipeUnlockRequirement`）与可负担性（`ProductionAffordability`）把关，前台是 workstation/crafting-station/magic-station GUI + 网络包。
- **改它先看**：`production/ProductionRecipeLoader.java`（持两个配方注册表 + 合成推导）。真正执行在 `engine.boundary.WandscapeBlockInteractExecutor.executeSynthesize/executeCraftWand/executeCraft`；任务请求入口 `production/network/RequestProductionTaskPacket.handleServer`。
- **数据流**：IN——`dataconfig.WandscapeDataLoader`（`craft_recipes`）+ `element.ElementMappingLoader`（合成推导）。OUT——`WorkItem` → `BuildingApi.enqueueWork(buildingId, work)`（进建筑任务队列）；GUI S→C 经 `CraftingStationPacket`/`MagicStationPacket`/`WorkstationDataPacket`；意图 C→S 经 `RequestProductionTaskPacket`。运行时对 `warehouse.ColonyItemBank` 结算。
- **依赖**：`element.internal.{ElementMappingConfig,ElementMappingLoader}`；`dataconfig`；`building.internal.{BuildingSavedData,BuildingState}` + `shared.api.BuildingApi` + `shared.data.WorkItem`（解析站点建筑 & 入队）；`warehouse.ColonyItemBank`；`engine.WandscapeEngine`（`RecipeUnlockChecker.isUnlocked`）；`shared.data.{ElementType,ItemKey}`。**不 import npc/task**——执行委托给 `block_interact` op（被 task/NPC 管线消费）；它只喂一个 `WorkItem`。
- **坑/旧文档矛盾**：`docs/modules/production.md` 过时。列蓝图动作 `craft_wand`/`brew_potion`；代码是单一 action `"craft"` → blueprint `production:craft`（RequestProductionTaskPacket.java:73-82），`CraftRecipeView.resolve` 再区分 wand/misc/potion。doc 说 `WORKSTATION_CRAFT_TICKS_PER_UNIT=10`；实际 `5`（WandscapeConstants.java:36）。`brew_potion` `default -> 120` 频道 ticks 分支近乎死码（RequestProductionTaskPacket.java:146），因客户端对 crafting station 只发 `craft`。
- **归属**：独立的配方/生产层，是元素经济的「craft 门面」——钉在建筑任务流 + 元素成本上的成本/定义层，非独立自洽经济。

---

# 批 4 道路/玩家工具域（road wand compass guidebook）

## road
- **职责**：殖民地道路网络域——玩家手动铺路/填平/销毁、样条编辑器造路、道路方块建造任务、以及**路网图路由**（`RoadRouter.plan` 用 Dijkstra 规划运输/行走路线）。核心数据模型纯 Java（零 MC import），MC 适配在 `road/engine/`。
- **改它先看**：`road/algorithm/RoadRouter.java`（`plan(network,start,end,...)`，路由心脏，pure + 有单测）；客户端 `road/client/RoadPlacementController`（REPLACE/FILL/DESTROY_FILL）+ `SplineEditorController`/`SplineEditorInputHandler`（样条编辑器）；服务端权威入口 `road/engine/RoadSavedData.getOrCreate(level)` + `RoadApiImpl`。
- **数据流**：IN——客户端 `RoadPlacePacket`/`SplineBuildPacket`/`FillBoxPacket`/`DestroyFillPacket` → 服务端建 `RoadEdge` 入 `RoadSavedData` + 发 `TaskRequest` 进 engine 任务池 → 建好后 `CustomEvent("road_segment_complete")` → `RoadSegmentListener.onSegmentComplete` 置 edge COMPLETE。OUT——`RoadRouter.plan` → `TransportRoute(List<SplineLeg>)` 供 engine/transport + NPC/游客移动消费；`RoadApiImpl.getNetwork` → `RoadApi`；`RoadSiteData.fromEdge` → `ConstructionSiteDataPacket`（工地 UI）；`RoadAreaSyncPacket` 广播刷新客户端 ghost。
- **依赖**：`core.types.GridPos`、`core.event.CustomEvent`、`engine.WandscapeEngine`/`ResourceSupplySystem`/`service.SoundService`/`WandscapeSounds`、`building.network.ConstructionSiteDataPacket`、`warehouse.ColonyItemBank`、`task.engine.pool.TaskRequest`、`task.source.PlayerManualSource`、`shared.api.RoadApi`、`shared.network/*`+`shared.ui/*`+`shared.registry/*`+`shared.log.Log`。**深度绑进 engine/task/warehouse/building 的建造管线，非自洽孤岛。**
- **坑/旧文档矛盾**：`docs/modules/road.md` 宣称「无路网图路由 / RoadRouter/TransportRoute/SplineLeg 已整体删除 / RoadNetwork 仅元数据不用于寻路」——**全与代码相悖**。`RoadRouter`（Dijkstra、T 字路口、野路 hop、sweep-line X 索引、`MAX_SEARCH_STEPS=500`）完整存在且有 `RoadRouterTest`/`RoadRouterStressBenchmark`。旧文档描述的 O(B²) 看门狗杀服根因，已被 AABB 预剔除 + sweep-line + 步数上限修掉。
- **归属**：**独立 content 域，保留**；但须接受与 engine/task/warehouse/building 的横向耦合（不是自洽孤岛）。

**「点/向量自造族」Tier 3 裁决口（本包落锤，⚠️ 清单已由审核修正为 5 个）**：自造族共 **5 个**——`SplineVec3`(double x,y,z)、`PathPoint`(int x,y,z)、`XZPoint`(int x,z)、`core.types.GridPos`(int x,y,z)、**`building.data.BlockOffset`(int x,y,z)**。其中 `GridPos`/`PathPoint`/`BlockOffset` 是**三个同构 int 三元组**（审核补充——packages34 原列 4 个漏了 BlockOffset）。`SplineVec3` 是 vanilla `Vec3` 的裁剪重写（同 `final double x,y,z` + `ZERO`/`add`/`subtract`/`scale`/`length`/`normalize`/`dot`；唯一差异 normalize 阈值 `1e-9` vs vanilla `1.0E-4`，并剪掉 `cross`/`distanceTo` 等）。road/core + road/algorithm **zero** `net.minecraft` import，且有 `SplineModelTest`/`RoadRouterTest` 直接构造它们。**裁决：保留自造族，不换 vanilla**——换 `Vec3` 虽不破坏测试（MC 在 test classpath），但①失去「核心零 MC 依赖」纯度承诺；②单测开始依赖 MC 类；③`PathPoint`/`XZPoint`/`GridPos`/`BlockOffset` 无干净 vanilla 等价物（`BlockPos` 是 `Vec3i` 系非 record）。**若 Tier 3 坚持收敛，首选方案是 `GridPos`+`PathPoint`+`BlockOffset`+`XZPoint` 合并为一个 int 三元组/二元组点类（而非改 vanilla）**。

## wand
- **职责**：NPC 法师法杖的物品载体——数据驱动：预设 JSON → 属性加成 + tooltip/颜色/NBT。无玩家施放行为；属性只对 NPC 主手生效（玩家手持返回 `ItemAttributeModifiers.EMPTY`，避免玩家吃到加成，顺带避开 bastion 法杖负移速卡走）。
- **改它先看**：`wand/item/WandItem.java`（一个 `Item`，`getDefaultAttributeModifiers` 返 EMPTY）+ `wand/internal/WandPresetLoader.java`（注册 `craft_recipes` 类别，`WandPreset.fromJson` 过滤 `type!="wand"`）。
- **数据流**：IN——`dataconfig.internal.WandscapeDataLoader.register("craft_recipes",...)` 读 JSON → `WandPreset(id, displayName, defaultColor, nbt, attributes)`；`WandItem.appendHoverText` 读 `WandApi.getWandPresetId(stack)`。OUT——`WandApiImpl.getWandColor/getWandPresetId/getWandModifiers`（读 `CUSTOM_DATA` 的 `wand_color`/`preset_id`）→ `shared.api.WandApi`，供 `npc.WandscapeNpc.syncWandAttributes` 桥接 NPC 装备槽。
- **依赖**：`shared.api.WandApi`、`shared.registry.{WandscapeApis,WandscapeDataRegistry}`、`core.types.{AttributeModifier,AttributeType,ModifierOperation}`、`engine.attribute.WandscapeAttributes`、`dataconfig.internal.WandscapeDataLoader`。
- **坑/旧文档矛盾**：`docs/modules/wand.md` 称 tooltip「显示预设名 + 逐条属性加成（负数标红）」——**不符**。当前 `WandItem` 只 add 一条 `craft_recipe.wandscape.<presetId>`（WandItem.java:48），属性列表已不再渲染。
- **归属**：纯数据驱动 item + preset 数据 + 薄 API 实现，**无 system/state/SavedData/仿真内核**（仅 3 文件，无任何 tick/持久化逻辑）→ 可并入 `items`（WandApi 面归 `api/`，见 Tier 2e）。

## compass
- **职责**：玩家侧「魔法指南针」三档物品——指针指向自己殖民地市政厅；高级/终极 tooltip 显坐标；终极右键传送到市政厅安全落点。
- **改它先看**：`compass/CompassService.java`（服务端静态真业务：`resolveTownHall`/`syncFor`/`teleportToTownHall`，含垂直 + 螺旋 `findSafeSpawn` 安全落点）+ `compass/MagicCompassItem.java`（持有 `CompassTier`，`use`/`useOn`/`inventoryTick`(每100tick) 路由）。
- **数据流**：IN——`CompassSyncHandler`(`@SubscribeEvent` login/dimension) + `MagicCompassItem.inventoryTick` → `CompassService.syncFor`。OUT——`resolveTownHall` → `CompassTargetPacket`(S→C) → 客户端 `CompassTargetClientCache`（`angle` item property + tooltip 消费）。传送 `player.teleportTo(overworld, ...)`。
- **依赖**：`raid.RaidTownHall.findTownHall(UUID)`、`shared.registry.WandscapeApis`(`getColonyApiSilently`)、`shared.log.Log`。无 engine/building 依赖。Curios 兼容实际在 `compat/curios/`（compass 包内无）。
- **坑/旧文档矛盾**：`docs/modules/compass.md` 与代码高度吻合。唯一注意：compass 包内无 Curios 代码，Curios 走外部 compat 层（不在本域）。
- **归属**：薄——`CompassTier` 是枚举、`CompassService` 是静态类且只被物品/登录事件调用，无 SavedData/无 tick 仿真/无内聚状态机。游戏性（市政厅定位+传送）是物品行为的外延 → 可并入 `items`；**但**会让 items 域背上 `raid.RaidTownHall` 单向只读依赖（可接受，或留 `colony` 下）。

## guidebook
- **职责**：指南书物品——右键直接打开模组教程首页（`index_guide`）。
- **改它先看**：`guidebook/item/GuideBookItem.java`（`use()` 发包）+ `guidebook/network/GuideBookOpenPacket.java`（S→C，负载 `docPath`）。
- **数据流**：IN——玩家右键 → `PacketDistributor.sendToPlayer(GuideBookOpenPacket(INDEX_DOC))`。OUT——客户端 `handleClient` → `DocumentLoader` 按语言加载并打开阅读器（服务端不读资源）。
- **依赖**：**无任何跨域 import**（只引 `guidebook.network` + MC/NeoForge）。四包中唯一真正零耦合的域。
- **坑/旧文档矛盾**：无。`static setClientHandler` 是客户端 handler 注入点，真正打开逻辑在 `shared/ui`（指南书只是 `String docPath` 触发器）。
- **归属**：极简、无系统内核 → **并入 `items`**（本节合并自批 4 与批 8 节稿——批 8 说「item→items，UI→foundation/ui」，与批 4 一致，两处同源，此处为唯一节）。

---

# 批 5 任务/施法域（task op magic）

## task（任务自动化引擎）
- **职责**：殖民地自动化的大脑——蓝图 DSL 描述「NPC 依序执行的一串原子操作」，中央任务池照优先级/审批/资源等待/触发订阅持有全局任务，调度器把任务派给空闲 NPC，执行系统逐 NPC 驱动其操作序列（导航、异步 future、资源短缺、吞并/恢复）。建造/采集/合成/守卫都靠它把「蓝图 → NPC 干活」串起来。
- **改它先看**：`task/engine/pool/GlobalTaskPool`（任务生命周期唯一入口：addTask/assignLight/completeTask/cancelTask）、`task/scheduler/TaskExecutionSystem`（逐 NPC 驱动 op 序列）、`task/engine/dsl/BlueprintInterpreter`（DSL 解析）。
- **数据流**：来源(task/source: 事件驱动/玩家手输/工作台) → `TaskRequest` → GlobalTaskPool(编译蓝图→GlobalTask, 按 priority 入 assignableSet) → `SchedulerSystem`(打分派给空闲 NPC) → 入 NPC 的 NpcTaskQueue → TaskExecutionSystem 逐 op 查 OpExecutor 执行 → 调 core 边界(blockOps/movementOps/colonyResources) → 完成/告警。建筑侧经 `BuildingTaskPool` 保证每建筑只队头任务进全局池。
- **依赖**：重度 `core.*`（ecs/component/boundary/types/event）+ `op.api.AtomicOp` + `op.executor` + `shared.log`。**不 import 任何 net.minecraft / net.neoforged**。纯逻辑可单测（已有 BlueprintInterpreterTest/GlobalTaskPoolTest/TaskExecution 系列/Scheduler 系列）。执行器实体由 engine.boundary/guard/building 提供。
- **坑/旧文档矛盾**：① task/runtime `InterruptRecord(long npcId, timestamp, atStepIndex)` 是**活的**（GlobalTask.interruptHistory 用）；死的是 `shared/data/InterruptRecord(UUID, timestamp)`（见批 6）。同名双 record 别删错。② task 是 core 边界（ECS/组件）最重消费方之一，拆桥层时任务引擎要连带处理。
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
- **坑/旧文档矛盾**：① warehouse 是 **core 边界 `ColonyResourceAccess` 的唯一实现方**——任务系统经它取料；Tier 4 边界内联时它是最重的适配器之一，移动前后要保住「资源存取语义不变」。② `ResourceId.getFuckPureResourceId_NotContainFuckedNBT()`——改名残留带脏话的临时方法（在 WarehouseManager），重构顺手清。③ `WarehouseMenu` 直接 import `engine.service`/`engine.sound`，拆 engine 时菜单调用点随功能域走。④ **NPC 属性收敛的消费方之一**：warehouse 消费 `shared.data.{MageHutResident,MageResume,...}`（属性定义处见 npc 节），收敛时调用点一并改。
- **归属**：独立 content 域 `warehouse`（经济存储域）。同时扮「对外契约(WarehouseApi) + 核心边界适配(ColonyResourceAccess) + 双标签 GUI」，纯功能域，符合 content/ 形态。

## shared（公共/桥层·待拆）
- **职责**：重构要拆的「桥层」之一（旧架构为「互不直接引用」搭的桥），但**混着三类**：真公共基建（log/网络包/DTO/公共 UI 控件/markdown 栈）、纯功能实现（各面板/overlay/具体 Overlay、API 内部实现）、搭桥/死码。判定「该拆哪些进 foundation」的对象。
- **改它先看**：`shared/log/Log`（公共日志过滤入口）、`shared/registry/WandscapeApis`（静态注册表、API 面收敛处）、`shared/network`（全局网络包层）。
- **数据流**：`shared/log` 全模组日志过滤；`shared/event` 收 NeoForge/custom 事件供跨系统响应；`shared/network` 服务器↔客户端包收发；`shared/ui` 渲染公共控件、被各域 Screen/Overlay 用；`shared/data` 纯 DTO 与配置 record；`shared/api` 各 XxxApi 接口给跨域/第三方调。
- **依赖**：跨功能域。拆法判定靠引用计数：低层真公共（log/network 基类/公共控件/theme/markdown 栈/真 DTO）→ foundation；具体功能面板/overlay/API 内部实现 → 随各自功能域；0-1 引用搭桥/死码 → 内联或删。
- **坑/旧文档矛盾**：① `shared/data/InterruptRecord(UUID,timestamp)` 是**死码**（批 5 已证 active 的是 task/runtime/InterruptRecord）。② `shared/registry/WandscapeApis` 是静态注册表 + 14 套 get/set 样板（plan.md 要瘦身迁进 api/）。③ shared 里有 `MageHutAttributes`/`MageAttributeRoller`/`MageResume`/`MageHutResident`——NPC 属性定义的一批（详见 npc 节「NPC 属性全地图唯一收敛点」），**必须收敛到 npc 域 `NpcAttributes` 一处**（注意：此处与旧节稿「收敛进 building」的说法矛盾——以 `npc` 域为准，见【合并勘误】）。④ `shared/ui/panel/TaskManagementOverlay`(1094)/`WandscapePanelController`(716)/`shared/ui/component/TaskQueuePanel`(596)/`WandscapePanelState`(536) 是 **UI 堆积**主犯——具体功能面板随功能域走，不进 foundation。⑤ `shared/ui/markdown`（MarkdownParser/GifDecoder/MarkdownRenderWidget/… ~13 文件）是一整个独立子栈，将来可独立成库，归属 foundation/ui。⑥ `shared/client/bubble` + `shared/client/render` 是具体渲染功能（气泡/建筑 ghost），随功能域。
- **归属**：拆散。→ foundation：`shared/log`、`shared/network`（基类+全局包）、`shared/ui`（公共控件+theme+markdown 栈+skin+animation）、`shared/registry`（WandscapeConstants 等常量）、`shared/data`（真 DTO/record）。→ 随功能域：`shared/ui/panel`+`shared/ui/component`（具体功能 Overlay/Panel/TaskQueuePanel）+`shared/ui/guidance`/`guide`+`shared/client`（bubble/render）+`shared/ui/util`（BuildingPreview 等）。→ 内联：`shared/api` 各 XxxApi（内部 use >80% 的删接口、消费方直连实现类，只留 addon 真公开面）。→ 保留/删：`shared/event` 真事件流保留、假事件内联；`shared/data/InterruptRecord` 等死码删。

---

# 批 7 core / engine — 零 MC 运行内核 + MC 适配层（要拆的"桥层"）

- **一句话**：`{core + task + op}` 构成**零 MC 的模拟/任务运行内核**，`engine` 是它上面的 **MC 适配层**。`core` 只是这套运行内核的"框"（ECS/组件/边界/值类型），它**并不独立**——`core/ecs/World` 直接耦合 `task`/`op`。重构的真相判据：**核心不是 core↔engine 两层，而是 运行内核↔MC 一道真缝 + 一个命名误导的 core 框。**
- **改它先看**：`core/ecs/World`（运行内核的容器/god-object）、`engine/bootstrap/EngineBootstrap.bootstrap()`（唯一装配点）、`engine/WandscapeEngine`（全局静态定位器）、`core/CoreBootstrap`（构建 World 并注册 task 各 system）。
- **数据流**（装配）：`Wandscape.java:941` → `EngineBootstrap.bootstrap()`（ServerStartingEvent）→ ①建 BlueprintRegistry/List&lt;TaskSource&gt; ②实例化 4 个 MC 边界实现（`WandscapeBlockOps/EntityOps/RitualOps/MovementOps`）③解析 `ColonyResourceAccess`（真 `WarehouseManager` 或空 stub）④`CoreBootstrap.bootstrap(config)` → 返回 `World` ⑤接 `ResourceAddedListener`/`ResourceShortageHandler` ⑥`world.addSystem(NavigationSystem/ResourceSupplySystem)` ⑦把 World+各 executor 塞进 `WandscapeEngine` 静态字段。
- **数据流**（运行）：`Wandscape.java:1233` 每 tick `world.tick(1.0f)` → 按注册序跑 6 个 `System` → `SimpleEventBus.dispatch()` 派发排队的域事件。`Wandscape.java:1146-1207` 另逐 tick 调 8 个 executor 的 `tickAll()`。
- **依赖**：`core` 零 MC（**47 文件 / 2421 行，0 条 `import net.minecraft/com.mojang`，实测**）；`engine` 纯 MC（`BlockPos/ServerLevel/SavedData/EntityType/DeferredRegister/SoundEvent`）。只有 16/42 engine 文件 import core（实测）。

## core 子包（真实职责，含"命名误导"）
- **core/ecs（4）**：`World`、`System`(`@FunctionalInterface update(World,delta)`)、`ComponentStore`、`HashMapComponentStore`。
- **core/component（11）**：`Position` `TaskExecutor` `Inventory` `ColonyMember` `ColonyMetadata` `NavigationState`（**这 6 个是真·ECS 组件**，在 `CoreBootstrap.bootstrap()` 注册为 store，被 world.get/query 读）+ `MagicState` `EquippedMagicComponent` `CastStrategyComponent`（**不是 ECS 组件**，是 `WandscapeNpc` 直接持有的普通 Java 状态，经 NBT 持久化）+ `NpcTaskQueue`(藏在 `TaskExecutor.npcQueue` 字段里)+ `SuspensionContext`(纯内部 record)。
- **core/types（17）**：值类型。**只见 `EquipmentPreset` 全仓 0 引用（成死码，实测）**；其余 `GridPos/ResourceStack/ResourceId/AttributeType(22 文件)/AttributeModifier/BlockType/EffectId/EntityId/RitualId/EquipmentSlot/InteractAction/ModifierOperation/FriendlyForce/FollowAttackDecision/HostileMarkDecision` 全活。⚠️ **`NpcAttributes` 不是死码**（被 `CoreBootstrap`、`npc/EntityComponentBridge.defaults()`、~9 个测试引用）——plan/status 的"NpcAttributes 0 引用死码"cite 已过期（详见【关键纠正】）。注：`FriendlyForce/FollowAttackDecision/HostileMarkDecision` 各带单测，是"纯逻辑可测"的红线样本。
- **core/boundary（8）**：`BlockOps` `EntityOps` `MovementOps` `RitualOps` `EventBus` `ColonyResourceAccess` `ResourceAddedListener` `ResourceShortageHandler`。**全 8 个都活、且消费方多在 core+engine 之外**（走 `world.blockOps...` 访问，不是 import 接口，所以"import 计数"误判成单次间接——见坑 2）。
- **core/event（4）**：`CustomEvent` `SimpleEventBus` `NarrativeEventTriggered` `TaskCompleted`。**EventBus 是运行时宽的域事件总线**，订阅方在 building/road/task（`BuildCompleteListener`/`DemolishCompleteListener`/`RoadSegmentListener`/`SystemBlueprintRegistry`/`GlobalTaskPool`），不是 core↔engine 缝。
- **core 根**：`CoreBootstrap`、`CoreBootstrapConfig`、`TemplateResolver`(活)。

## engine 子包（真实职责）
- **engine/boundary（9）**：4 个核心边界实现 `WandscapeBlockOps/EntityOps/RitualOps/MovementOps`（**这是仅有的真正 MC↔内核适配点**）+ 3 个 `op.api.OpExecutor` 适配器 `AsyncTransformExecutor`/`WandscapeBlockInteractExecutor`/`ResourceRequestExecutor`（它们实现的是 **op** 的接口，不 import core）+ 2 个不 import core 的工具 `BuildPlacementGuard`/`ProductionEligibility`。**→ "engine/boundary" 作为目录名是误导**：只混了 4 个真适配器 + 3 个 op 适配器 + 2 个无关工具。
- **engine/bootstrap（1）**：`EngineBootstrap.bootstrap()`（唯一装配点）。
- **engine/colony（4）**：`ColonyLevelManager`（`ColonyLevelData` 旁）、`ColonyActivation`、`ColonySavedData` —— level/经验 + 激活/离线倍率 + 存档。
- **engine/nav（3）**：`WandscapeNavigation`（`WandscapeNodeEvaluator`、`RoadWalkPlanner` 旁）—— 自定义 `GroundPathNavigation`（水陆通行 + 开门）。
- **engine/attribute（1）**：`WandscapeAttributes`（注册 6 个自定义 MC attribute + bridge `core.AttributeType`↔vanilla `Holder<Attribute>`）。（NPC 属性收敛时此 MC 桥独立保留，见 npc 节。）
- **engine/sound（2）**：`WandscapeSounds`（全模组自定义 SoundEvent 唯一注册点，13 跨域引用）、`ColonyAmbientSystem`(客户端循环)。
- **engine/transport（3）**：`ItemTransportManager`(send/tickAll)、`TransportItemEntity`、`TransportStartPacket` —— 沿路线的物品飞行动画（仓库↔NPC）。
- **engine/system（2）**：`NavigationSystem`（**全 NPC 移动的唯一驱动器**）、`ResourceSupplySystem`（资源补给重试环）—— 都是 ECS System 实现。
- **engine/source（2）**：`BuildingTaskSource.poll`（把建筑块实体 WorkItem 翻成 TaskRequest，**建筑→engine 任务唯一桥**）+ `source/blueprint/BlueprintConfigLoader`(JSON→BlueprintDefinition AST→注册可执行蓝图)。
- **engine/service（10）**：跨域 MC 服务 `ChunkLoadManager`(区块强载/lease) `ParticleService` `SoundService` `AchievementService` `StatsService`(sub NarrativeEventTriggered/ColonyLevelUpEvent) `GuideProgressService` `GuideServerContext` `ColonyMetricsService` `ChunkLeaseData` + `service/client/ClientSoundHelper`。⚠️ 这类服务常**只被 EngineBootstrap 注册**（事件订阅/静态 register 做活），import 计数低 ≠ 死码。
- **engine 根**：`WandscapeEngine`（**全局静态服务定位器，36 跨域引用——getXxx() 搭桥反模式本体**）、`TaskPoolSavedData`、`ColonyApiImpl`、`HostileTargetingHandler`、`BuildingNoSpawnZoneHandler`。

## 关键坑 / 旧文档矛盾（tier0 核心价值）
1. **"core 是真·纯 Java 独立层" 只对一半**：`core` 零 MC 是真的，但它**不独立**。`core/ecs/World.java:6,8-10` 直接 import `op.executor.OpExecutorRegistry`、`task.engine.dsl.BlueprintRegistry`、`task.engine.pool.BuildingTaskPool`、`task.engine.pool.GlobalTaskPool`；`CoreBootstrap` 注册 `task.scheduler.SchedulerSystem/TaskExecutionSystem/SystemBlueprintSystem` + `task.source.TaskSourcePoller`——**真正干活的是 task/op 的 system，core 只是框**。所以"双层是好抽象"要改成"**运行内核{core+task+op}↔MC 一道缝**"。
2. **boundary 接口不是单次间接桥（此草案曾判错，已修正）**：`BlockOps/EntityOps/RitualOps/MovementOps` 的主消费方是 `op/executor/DefaultOpExecutors` + `task/scheduler/TaskExecutionSystem`（经 `world.blockOps...` 调用，非 import）；`ColonyResourceAccess` 由 `warehouse/WarehouseManager` 实现（core+engine 外唯一实现）+ `DefaultOpExecutors`/`GlobalTaskPool`/`TaskExecutionSystem` 消费；`EventBus` 达 building/road。→ 它们是**真·多调用方运行时接缝**，不是"单次间接→内联"。判"能否内联"要看 `world.xxx` 消费方数量，不能看 `import core.boundary.X`（这计数会漏）。
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

# 批 8 技术层/资源杂项 — client / compat / command / dataconfig / mixin / gametest + resources

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
- `ElementAuditRunner`（`@EventBusSubscriber`，ServerStartedEvent，`wandscape.runAudit` sysprop，由 `elementAudit` run config 设）——加载 element_seeds.json、审计元素映射覆盖、写 build/reports、halt 服务器。（跑审计用 `runElementAudit`，runGameTestServer 无 @GameTest 会崩。）
- **结论**：测试/工具桶（元素域色彩）。

## guidebook（Java，见批 4 guidebook 节，唯一节）
- `guidebook/item/GuideBookItem`(Item，use() 发 `GuideBookOpenPacket(INDEX_DOC)`)+`guidebook/network/GuideBookOpenPacket`(server→client payload)。真正阅读器/渲染在 `shared/ui/markdown/**`+`shared/ui/guide`。
- **结论**：薄域入口。item→items，UI→foundation/ui。

## resources（data / lang / assets —— JSON 泛滥与 lang 灾难真实数字）
- **data/wandscape：1353 文件（1351 json+2 nbt，实测）。`element_mappings` = 1188 json = 整个 data/wandscape 的 88%**（审核更正：原节稿写 1187，实测 1188 个 json 文件）——**JSON 泛滥几乎全在这一类**（逐原版方块→7 元素映射）。其余：buildings 53（+deprecated 14=67）、craft_recipes 32、advancement 33、magic_spells 10、magic_circles 10、recipe 2、loot_table/blocks 2、damage_type 2、narratives 3、structure/road 2 nbt、tags/block 1、根 element_seeds.json。（2026-09-01 数据治理：删 `road_templates/`（corner/straight）旧 schema 孤儿无引用，见 road 节；**`blueprints/` 13 JSON 全部删除，收敛为 Java lambda**（`content/task/engine/dsl/BlueprintDefaults.java`），DSL 解释器栈（BlueprintConfigLoader/BlueprintInterpreter/BlueprintDefinition/StepNode/ExprNode/ParamType）拆除；登记的是 `road_presets` 类别，无本地文件、靠硬编码 `RoadPreset.DEFAULT_PRESETS`，勿误标。）
- **buildings/deprecated（14）是 building 兼容载荷，不可删**（`ProjectionNetwork`：deprecated 建筑隐藏但仍可用）——与 status.md/tier1.md 一致。
- data/curios：5 json。
- **lang：en_us 2031 + zh_cn 2032 = 4063 行，正好一语言一大坨**（实测逐字命中）——「lang 分文件」目标实锤。
- **guide：52 .md（en/zh 各 26），无 json**，~26 页/语言、每语言 ~1000 行。内容 `assets/wandscape/guide/`。
- assets 总 391：models 130（item 126）、textures 193（item 112/gui 43/entity 31）、blockstates 4、particles 2、sounds 7 ogg+sounds.json、lang 2、guide 52。
- 小死数据候选：element_mappings/disabled(1)、blockstates/models 的 `$name` 模板(2)、structure/road↔road_templates 疑似冗余(4)。
- **意义**：JSON 泛滥是**单点**(element_mappings)非处处；lang 是**每语言一坨**；guide 是 **markdown** 非 json。这三条直接圈定 Tier 里 JSON/lang 治理的范围。

---

# 合并勘误（相对原始节稿的修正）

| # | 原稿 | 修正 | 依据 |
|---|------|------|------|
| 1 | packages34 §road「点/向量自造族共 4 个：SplineVec3/PathPoint/XZPoint/GridPos」 | **5 个**，补 `building/data/BlockOffset`（int x,y,z）；Tier 3 收敛应合并 GridPos+PathPoint+BlockOffset+XZPoint | 实测 grep 出 5 个点类；其中 3 个同构 int 三元组 |
| 2 | packages12 §1.1「属性应收敛进 building」vs packages56 §warehouse「收敛到 npc 域」 | 统一为 **npc 域唯一命名类 `NpcAttributes`** | CLAUDE.md 增量归属约束「该功能域唯一一个命名类」（如 NpcAttributes） |
| 3 | guidebook 在批 4 与批 8 各一节 | **去重为一节**（归属一致：item→items/UI→foundation） | 跨批同源重复 |
| 4 | packages12 §2.1「5 文件引用」等个别计数 | 以实测为准（WandscapeNpc 84/63、guard 14、core 47/2421/0、engine 42/16、resources 1368/1188/2031/2032/52/14 全命中） | grep 实测 |
| 5 | packages56/78 未单独核查 NPC 属性散布 | **新增 npc 节「NPC 属性全地图唯一收敛点」**，并揭出已漂移（roll 的 MOVE_SPEED/ARMOR_VALUE 每级加成 vs SPECS perLevel=0 不一致；defaults() 与 SPECS 中点不符） | 读 MageHutAttributes/MageAttributeRoller/NpcAttributes/AttributeType 逐字比对 |

**复核说明**：本表「关键纠正」「全局结论表」「合并勘误」及各处标注「实测」的数字均经真实代码/grep 核实；未逐行验证的细节（如行号、~200 行、17 个 client 子包逐包计数）保留原稿说法，标注不清之处宁缺勿务。

> 内容支撑 content/ 分包 + 合并判断即可；空点未发现（全 29 包覆盖）。重构落定后随 `newplan/` 一并删。
