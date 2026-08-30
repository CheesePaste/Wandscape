# 批 1 / 批 2 归属摸底（tier0，临时）

> 本文件是 **tier0 可信全项目文档摸底** 下批 1（建筑域）、批 2（法师/市民域）的节稿——与 `packages.md` 同制式，只因跨 AI 并行写 `packages.md` 有冲突风险，改由本文件单独承载，由协调者并入主文件。
> 判据：只读真实代码，旧 `docs/` 全不作真相、仅在「坑」条对照标错。一个概念全地图只定义一次，其它节/文件交叉引用。
> 与批 3/4/5/6 等其它批的耦合事实，行文中标注并交叉引用，不重定义。

---

# 批 1 建筑域（building projection overview raid stats）

## 1.1 building
- **职责**：殖民地**建筑内核**——注册/注销、持久化、空间索引（pos/chunk）、贡献/评估、任务队列、右键交互分流、建造/拆除生命周期。数据**完全数据驱动**：`BuildingConfig` 每类型一个 JSON（`data/wandscape/buildings/<id>.json`），`category` 决定适用配置段（basic/node/decoration/wonder/shop/service/relax/atm/workstation/crafting_station/magic_station）——**不是每类一个类**。category 的 comfort/magic/wonder 即该建筑的「元素值」贡献。
- **改它先看**：`building/internal/BuildingSavedData`（1109 行，状态/三索引/贡献注册表/持久化唯一枢纽）+ `building/internal/BuildingApiImpl`（对外唯一门面，实现 `shared.api.BuildingApi`）。
- **数据流**：IN——放置工具（projection 客户端 / scanner 导出 / command）→ `EnqueueHelper.registerIfAbsent` → 注册进 BuildingSavedData；NPC 建造引擎（`engine` 的 BuildingTaskSource）读其任务队列。OUT——任务队列 `WorkItem` → engine/op/task；`BuildingContributionRegistry` 在整建筑 0↔1 翻转发 `ColonyEvaluationChangedEvent` → stats/engine；`BuildingApiImpl` 暴露 `BuildingData` 给 npc/tourist/production/road/projection；右键 `BuildingInteractHandler` 发各 GUI 包给客户端。
- **依赖**：吸干 `engine.*`（BuildingTaskSource/ResourceSupplySystem/ColonyActivation/…）、`core.ecs.World`/`core.component.ColonyMember`/`core.types.*`、`op.api.AtomicOp`/`op.executor.OpExecutor`、`task.engine.pool.{GlobalTask,TaskRequest}`、`npc.*`、`magic.*`、`production.*`、`warehouse.ColonyItemBank`、`road.network`、`projection.BuildingRotation`、`shared.*`。**building 几乎是三桥层（core/engine/shared）+ op/task 的最大消费方**，这是重构要拆的纠缠点。
- **坑/旧文档矛盾**：① 建筑后端不是每类一个类（就是一张 Config 表）；**只有客户端 Screen 每类一个**（MageHutScreen/ShopScreen/NodeScreen/AltarScreen/TavernScreen/HotelScreen/TownHallScreen…）+ `BuildingInteractHandler` 里大 `switch(category)`——正是 CLAUDE.md「UI 堆积 / 加个按钮改每个」。② building↔projection 成**环**：building 用 `projection.BuildingRotation`（唯一反向依赖），而 BuildingRotation 本是建筑数据旋转数学、放错包。③ 建筑自身未重定义属性/元素公式，但它消费 `shared.data.{MageHutResident,MageHutAttributes,MageAttributeRoller,ShopGoodDef,BuildingData,...}`——「一物多定义」源头在 shared.data，building 是最大受害者/消费者，按【增量归属约束】应收敛进 building。
- **归属**：独立 `content/building`（殖民地建筑内核，非周围域附属）。scanner 子域归入 building（见下）。对 engine/core/op/task/shared 桥层的依赖是重构清理重点。
- **scanner 子域**（building/scanner）：蓝本导出/方块扫描工具，把世界方块扫成 `BuildingConfig` JSON 注册即刻可建（也是作者工具）。产出给 `BuildingConfigLoader.registerFromJson` 与 `RoadPresetLoader`；依赖仅 `building.data.BlockOffset` + 自身 + shared.ui/shared.data，**不碰** building 运行时（不 import BuildingState/BuildingSavedData）。是 building 域内相对独立的创作/扫描子系统，归属 building（可降为 building 下子目录）。

## 1.2 projection
- **职责**：玩家**建造模式**——落点解析（`BuildPlacement.resolve`，纯逻辑可测）、建筑整体旋转（`BuildingRotation`）、中心对齐（`BuildingCentering`）、鬼影预览 + 飞行相机 + 旋转放置（projection client）+ 网络包。就是「把建筑蓝图放进世界的预览/放置」环节。
- **改它先看**：`projection/client/ProjectionClientState`（客户端投影模式唯一状态持有者）+ `projection/BuildingRotation`（被 building 双向引用的纯旋转工具）。
- **数据流**：IN——server `ProjectionEnterResponsePacket` 喂 building slots；`BuildingConfigLoader`（building）给选中建筑配置；`BuildingApiImpl` 是放置后端。OUT——`ProjectionPlacePacket`/`BuildingActionPacket` → 服务端 `BuildingApiImpl.placeBuilding`；客户端 ghost 用 `BuildPlacement`+`BuildingRotation`+`BuildingCentering` 算锚点。
- **依赖**：深度依赖 `building.data.{BlockOffset,BuildingConfig}` + `building.internal.{BuildingConfigLoader,BuildingState,BuildingSavedData,BuildingRepairHandler}`、`engine.service.SoundService`+`engine.sound.WandscapeSounds`、`road.client.RoadPlacementController`、`shared.*`。**是 building 的客户端建造工具层**。
- **坑/旧文档矛盾**：与 building 成环——building（持久化/API）依赖 `projection.BuildingRotation`（BuildingSavedData.java:356/507/952、BuildingApiImpl.java:9）。环的真身只是这一个放错包的纯函数。
- **归属**：**并入 building**（作 building/placement 或 building/projection 子域）。它本质是 building 内容的客户端工具，重度依赖 building Config/State/SavedData；唯一反向依赖（BuildingRotation）本就是建筑数据数学、应下沉到 building.data。客户端/网络子域建议与 building 的 client/network 合并，不必保留独立顶层包。

## 1.3 overview
- **职责**：殖民地**全局鸟瞰控制面板**——一套客户端飞行相机 + 交互总控，把建筑/NPC/游客/道路/工地用右键拉起对应 GUI。`OverviewFlightController` 逐帧驱动 WASD 飞行、鼠标视角、射线挑选目标、点击分发；`OverviewRenderer` 渲染边界/框；`OverviewClientState` 存相机与目标状态。
- **改它先看**：`overview/client/OverviewFlightController`（行为入口）+ `overview/client/OverviewClientState`（相机/目标/激活态唯一状态源）。
- **数据流**：IN——读 `ProjectionClientState`（是否投射/鬼影）、`RoadPlacementState`（是否画路）、`BuildingAreaSyncPacket`/`RoadAreaSyncPacket`（服务端同步的区域框）、`TaskManagementClientState`（跟踪的 Mage）。OUT——`OverviewInteractPacket`/`OverviewEntityInteractPacket` → 服务端 `BuildingInteractHandler`/npc/tourist 响应开 GUI。
- **依赖**：`building.internal`、`projection.client`、`road.client`、`npc.entity.WandscapeNpc`、`tourist.entity.TouristEntity`、`engine.service/sound`、`shared.ui.panel.{WandscapePanelState,WandscapePanelController,...}`。**横跨 building/projection/road/npc/tourist 多域的客户端管理/控制台**。
- **坑/旧文档矛盾**：overview 同时依赖 projection 与 road 客户端模式且与它们互相让路（`if !RoadPlacementState.isProjecting()`、处理 SplineEditor/RoadStudio 抢占）——它不是孤岛，是殖民地面板客户端总闸。只被 Wandscape/WandscapeClient + mixin 消费。
- **归属**：**不建议并入 building**——塞进 building 会让它背 npc/tourist/road 客户端依赖与面板状态。它更接近「殖民地全局管理 UI」。建议：若计划有 `content/colony`（或 `content/overview`）管理面则归入；否则归 **foundation/client-ui**。

## 1.4 raid
- **职责**：殖民地**防御**——把原版 `Raid` 触发/接入殖民地。`RaidTriggerScanner`（玩家带不祥之兆靠近非停摆建筑 10 格 → 市政厅中心创建原版袭击）、`RaidTownHall`（市政厅定位 + 让原版 `ServerLevel.isVillage` 在市政厅范围内 true，配合 MixinServerLevel）、`ColonyRaidTracker`（轮询原版 Raid、胜利时广播 `ColonyRaidVictoryEvent`）。
- **改它先看**：`raid/RaidTriggerScanner`（触发源）+ `raid/RaidTownHall`（市政厅锚点，防御中心）。三文件共 ~200 行，都很小。
- **数据流**：IN——`WandscapeApis.getBuildingApi()`（取市政厅/建筑）+ `guard.GuardZone`（防御区）+ Config。OUT——发 `ColonyRaidStartedEvent`/`ColonyRaidVictoryEvent`（shared.event）；被 compass 与 mixin 引用。
- **依赖**：`guard.GuardZone`、`engine.service.ParticleService`、`shared.data.BuildingData` + `shared.event.{ColonyRaidStartedEvent,ColonyRaidVictoryEvent}` + `shared.registry.{WandscapeApis,WandscapeConstants}`、Config。**注意：不 import npc**。
- **坑/旧文档矛盾**：假说「raid 归 npc/guard 还是 building」——代码给了答案：它**import guard（防御域）**，只在市政厅定位时经 `WandscapeApis.getBuildingApi` 碰 building；与 npc 零关系。袭击本体持久化由原版 `Raids` SavedData 负责，本包只做事件/触发侧跟踪。
- **归属**：**并入 guard/防御域**（raid 是防御触发层），置信度中。与 building 的耦合仅是「找市政府」这一行，靠 `shared.api.BuildingApi` 即可保持松耦合，无需并入 building。若计划有 `content/defense`，raid 是它的触发层。

## 1.5 stats
- **职责**：殖民地**报表/统计层**。`StatisticsCollector` 订阅域事件、维护每殖民地当日游客流量与评估值，在结算边界写入 `ColonyDailySnapshot`，并把 `StatsSyncPacket` 推给打开面板的玩家。`StatisticsData` 是持久化 SavedData，存 30 天滚动快照。
- **改它先看**：`stats/internal/StatisticsCollector`（事件聚合+推送逻辑）+ `stats/internal/StatisticsData`（快照存储）。
- **数据流**：IN（只靠事件，无直接 import）——`DailySettlementEvent`、`TouristArrivedEvent`、`TouristDepartedEvent`、`ColonyEvaluationChangedEvent`（其中 `ColonyEvaluationChangedEvent` 正是 building 的 `BuildingContributionRegistry` 发的：building→event→stats；`Tourist*` 来自 tourist 域）。OUT——`ColonyStatsSummary` → `StatsSyncPacket` 推殖民地面板玩家。
- **依赖**：`shared.api.ColonyApi`、`shared.event.*`、`shared.network.PanelStateTracker`、`shared.registry.WandscapeApis`、`shared.ui.panel.WandscapePanelState`、own `stats.data/network`。**零 `building` import**（这是最重要的事实）。
- **坑/旧文档矛盾**：假说「stats 归 building」**不成立**——与 building 无代码级 import，只有事件级耦合；它是纯粹的「殖民地级分析/上报」子系统，整个包只被 `Wandscape.java` 注册一次（反向引用仅 1 文件）。
- **归属**：独立 `content/colony-stats`（或并入 colony/overview 管理面），置信度高。塞进 building 会让 building 背上报表持久化；它订阅 building 与 tourist 事件、用 ColonyApi 取数据，是殖民地横切报表层。

# 批 2 法师/市民域（npc guard ring scepter）

## 2.1 npc
- **职责**：殖民地**NPC 实体域**。核心 `WandscapeNpc`（`extends PathfinderMob implements PlayerLike`，1922 行）——殖民地法师的**外观/寻路/NBT 壳**，全部逻辑（魔力/调度/任务执行）经 `EntityComponentBridge` 桥进 ECS 的 core/engine。另有敌对测试法师 `EvilMage`（`extends WandscapeNpc implements Enemy`，`isColonyNpc()=false`，不进 ECS/不留死亡记录/不进小镇）与施法 goal `EvilMageCastGoal`。周边：`internal`（EntityComponentBridge 单例桥、NpcApiImpl、NpcDeathHandler 死亡快照、ReviveHandler 复活/全灭保底、ColonyDeathRegistry 死亡留存）、`data`（`DeathRecord` 纯 record、NpcDataImpl）、`network`（7 个包）、`NpcMenu`/`NpcStrategyMenu`（装备/策略菜单）、`client`（NpcScreen/NpcStrategyScreen/渲染/模型/帽子层）。
- **改它先看**：`npc/entity/WandscapeNpc`（实体本体）+ `npc/internal/EntityComponentBridge`（npc↔ECS 唯一桥）。
- **数据流**：IN——引擎（ECS 任务/调度）、玩家右键/菜单（`mobInteract`，WandscapeNpc.java:1372-1401）、guard 战斗（仇恨/跟随攻击/自防御标记）。OUT——EntityComponentBridge ↔ 唯一 `World`（ECS）；`NpcApi`（NpcApiImpl）；ColonyDeathRegistry → ReviveHandler；NpcDataPacket → 客户端屏幕。
- **依赖**：极广——`core.*`（component/ecs/types/attributes）、`engine.*`（WandscapeEngine/WandscapeAttributes/WandscapeNavigation/...）、`magic.*`、`task.*`、`building.internal`（ReviveHandler 用 BuildingSavedData/BuildingState）、`warehouse.ColonyItemBank`、`shared.api/entity/data/registry/log`、`compat.ironspellbooks`、`wand.item.WandItem`。
- **坑/旧文档矛盾**：① **命名灾难实锤**：NpcXxx（NpcMenu/NpcDataImpl/NpcDeathHandler/ColonyDeathRegistry...）与 MageXxx（`shared.api.MageWandItem`、`shared.data.MageHutAttributes`/`MageHutResident`、`EvilMage`/`EvilMageCastGoal`）共存无规律；类 `WandscapeNpc` 自己混用 "Wandscape"+"Npc"。② **注册 id 分裂**：实体 `wandscape_npc`（`Wandscape.java:228`）vs `evil_mage`（`:236`）；刷怪蛋 `wandscape_npc_spawn_egg` vs `evil_mage_spawn_egg`；lang key 分裂（`entity.wandscape.wandscape_npc` vs `entity.wandscape.evil_mage`）。③ **CLAUDE.md 称 `core/types/NpcAttributes` 是 0 引用死代码——与代码矛盾**：`EntityComponentBridge.java:11,163` 实际 import 并调用 `NpcAttributes.defaults()`，`CoreBootstrap.java:8,96` 亦引用。属文档漂移，作 Tier 1 删除候选时要避开（NpcAttributes 是活的）。
- **归属**：独立顶层 `content/npc`（殖民地自动化核心）。`WandscapeNpc` 是**已证实核心实体**：84 文件引用它、其中 63 在 npc 包外（building/tourist/task/magic/warehouse/compat/engine 全依赖）→ 砍/改名 = 全模组波及。客户端/网络/菜单按目标形态归 `foundation/ui` + `foundation/networking`，但当前留在 npc 域。

## 2.2 guard
- **职责**：**NPC 战斗/防御行为层**，全部以 `WandscapeNpc` 为对象做战斗，**无独立实体、无注册 id**。事件钩子（NpcSpellPowerHandler 伤害倍率、SelfDefenseHandler 受击记仇+环境伤害传送、FollowAttackHandler 玩家攻击→标记跟随战斗目标）、行为（ProjectileDodge 投掷物躲避、NpcEscapeTeleport 传送逃生、GuardScanner/GuardZone 建筑包围盒扩展守卫区）、任务（GuardTaskSource 扫攻击区发 `guard:attack`、GuardBlueprints 注册蓝图、GuardCommand 调试）、战斗引擎（GuardCombat 共用 + GuardAttackExecutor + SelfDefenseExecutor 两条循环）。
- **改它先看**：`guard/executor/GuardCombat`（共用战斗引擎，静态）+ `guard/executor/GuardAttackExecutor`（守卫循环，OpExecutor&lt;AttackMonsterOp&gt;）。
- **数据流**：IN——`Wandscape.onServerTick` 调 `guardExec.tickAll()`/`selfDefenseExec.tick()`/`ProjectileDodge.tick()`；`EngineBootstrap` 加 `GuardTaskSource` + 注册两个 OpExecutor；事件总线注册钩子。OUT——`guard:attack`/`self_defense` 任务包 → TaskExecutor/NpcTaskQueue；对 `WandscapeNpc` 施加战斗行为/仇恨/走位；读 scepter 的 `forcedHostile`/`isShelteredForAny`。
- **依赖**：`npc.entity.WandscapeNpc`+`npc.internal.EntityComponentBridge`、`core.ecs.World`+`core.component.{NavigationState,TaskExecutor,NpcTaskQueue}`、`task.engine.pool/dsl/runtime`、`op.api.AtomicOp`+`op.executor.OpExecutor`、`magic.*`（MagicBeamEntity/MagicCaster/CastBrain/MagicSpellExecutors/SpellbookLoader）、`building`（经 `shared.api.BuildingApi`+`shared.data.BuildingData`，GuardScanner 用）、`shared.registry.WandscapeApis`、`shared.log.Log`、Config。
- **坑/旧文档矛盾**：① `GuardCombat`/执行器是 **MC 绑定**（依赖 Level/MagicBeamEntity/Enemy/ECS World），**不是**可单测的纯逻辑战斗引擎；仅 `GuardZone`、`ProjectileDodge.willHit`、`GuardCombat` 部分几何是纯数学段（已有 Test）。② guard 任务 `colonyId=null`（`GuardTaskSource.java:76`）——守卫区是「全殖民地建筑包围盒并集」，可能横跨多镇，由最近真实 NPC 接取。这解释了 guard 为何**跨建筑与 npc 两域**。
- **归属**：**并入 npc**（作 npc/combat 或 npc/defense 子包），或升独立 `content/defense`（若想拉开「殖民防守」声量）。14 类几乎全 import WandscapeNpc/EntityComponentBridge，是 npc 的行为扩展，非独立实体。

## 2.3 scepter
- **职责**：玩家**权杖**物品域，同时含一个**真实持久化系统**。物品层：`ScepterItem`（单模式：和平/跟随/庇护/敌对四把）、`OmniScepterItem`（一杖四模式 shift+右键循环）、`ScepterKind`（四模式枚举 + 物品 id + 头部主题色）。系统层：`ScepterMarks`（纯存储，按殖民地存「庇护名单 + 单槽强制仇恨」）、`ScepterMarksSavedData`（SavedData `wandscape_scepter_marks`）、`ScepterService`（服务端业务：应用右键命令 + 殖民地归属校验）、`ScepterInteractHandler`（EntityInteract，非法师生物）、`ScepterDeathHandler`（LivingDeath，目标死亡清标记）、`ScepterApiImpl`（实现 `ScepterApi`）。
- **改它先看**：`scepter/internal/ScepterService`（命令应用主逻辑）+ `scepter/internal/ScepterMarks`（状态本体，纯 Java 可单测）。
- **数据流**：IN——玩家右键物品（`ScepterItem.onInteractNpc`/`OmniScepterItem.onInteractNpc`/`ScepterInteractHandler.onEntityInteract`）→ `ScepterService`。OUT——`ScepterMarksSavedData`（持久化）→ 经 `ScepterApi` 被 **npc** 与 **guard** 读取：`WandscapeNpc.isFriendlyForce`→`isSheltered`（WandscapeNpc.java:261）、`GuardTaskSource.findThreat`→`isShelteredForAny`（GuardTaskSource.java:90）、`GuardAttackExecutor`/`SelfDefenseExecutor`→`forcedHostile`。**它是玩家指挥殖民地战斗的 targeting 子系统**。
- **依赖**：`npc.entity.WandscapeNpc`、`shared.api.{MageWandItem,NpcBindingItem,ScepterApi}`、`shared.registry.WandscapeApis`（getColonyApiSilently 校验殖民地）、`shared.log.Log`、`net.minecraft.nbt.*`。
- **坑/旧文档矛盾**：① `ScepterMarks` 注释自称「纯 Java 零 MC 依赖可单测」，但**实 import `net.minecraft.nbt.*`**（第 3-5 行）——NBT 是 MC 类；若按「纯逻辑不 import MC」硬边界是轻微违规（NBT 是纯序列化、单测仍可行，但注释言过其实）。② 命名混用起点：`OmniScepterItem` 同时实现 `MageWandItem`（Mage）+ `NpcBindingItem`（Npc）两套命名接口。
- **归属**：物品类（`ScepterItem`/`OmniScepterItem`/`ScepterKind`）→ **items**。系统层是**真实系统**（持久化 SavedData + 被 npc/guard 战斗逻辑消费）→ **并入 npc**（玩家指挥殖民地战斗的 targeting 子模块）。**不能进 items**——否则 npc/guard 会反向依赖 items 域 = 反模式。

## 2.4 ring
- **职责**：**盟誓戒指**物品域——把法师 NPC 收入固定槽、右键放出，是「只服务物品本身」的自成一体的 capture/release 系统。物品：`OathRingItem`（shift+右键收法师、右键地面/空气放、三档 `RingTier`）。系统：`OathRingStorage`（纯逻辑固定槽 0~3，存整份法师实体 NBT）、`OathRingSavedData`（SavedData `wandscape_oath_rings`，按玩家 UUID 键控）、`OathRingService`（存取 + 殖民地校验 + 安全落点搜索，用 `Wandscape.WANDSCAPE_NPC.get().create().load(nbt)` 复活法师）、`OathRingSyncHandler`（登录推占用掩码）、`OathRingClientData`（客户端掩码缓存）、`OathRingDataPacket`（net）。
- **改它先看**：`ring/internal/OathRingService`（存取业务）+ `ring/internal/OathRingStorage`（槽位语义，纯逻辑可单测）。
- **数据流**：IN——玩家右键 `OathRingItem` → `OathRingService.tryStore/tryRelease`。OUT——`OathRingSavedData`（持久化，玩家→固定槽法师 NBT）→ **仅回给 `OathRingItem`（tooltip 数量，经 OathRingClientData）与登录同步**。**没有任何其它域读取此存储**（包外仅 `Wandscape.java` 初始化引用，ScepterMarksSavedData.java:14 一句注释）。仅存/取整份法师 NBT，不解析内容。
- **依赖**：`npc.entity.WandscapeNpc`、`Wandscape.WANDSCAPE_NPC`、`shared.api.NpcBindingItem`、`shared.registry.WandscapeApis`、`shared.log.Log`、`net.minecraft.nbt.*`、own `ring.client`/`ring.network`。
- **坑/旧文档矛盾**：同 scepter 命名混用（`OathRingItem` 实现 `NpcBindingItem`，Npc 命名）。ring 系统**不指挥 NPC 行为、只是实体搬运**——与 scepter 的「指挥战斗」本质不同。
- **归属**：`OathRingItem`/`RingTier` → **items**。系统层是**纯物品配套**（只服务 `OathRingItem`，无跨域消费）→ **整包并入 items** 或收敛为 `content/` 下一个极小 ring 模块。**与 scepter 不同**：ring 不宜并入 npc 行为层（它只是存取实体，不是指挥战斗）。

---

## 批 1 / 批 2 归属汇总（供并入主文件结论表）

| 批 | 包 | 归属 | 备注 |
|----|----|------|------|
| 1 | building | 独立 `content/building`（含 scanner 子域） | BuildingSavedData 枢纽 + BuildingApiImpl 门面；重度依赖 engine/core/op/task/shared 桥层（重构清理点） |
| 1 | projection | 并入 building（placement 子域） | 客户端工具层深度依赖 building；唯一反向依赖是放错包的纯函数 BuildingRotation |
| 1 | overview | 独立 colony 管理面 / foundation-client-ui（**不并入 building**） | 跨 building/projection/road/npc/tourist 客户端总控；只被 Wandscape+Mixin 消费 |
| 1 | raid | 并入 guard/防御域（**不并入 building**） | import guard.GuardZone；只在市政厅定位时经 BuildingApi 碰 building；与 npc 零关系 |
| 1 | stats | 独立 `content/colony-stats` / 入 colony 管理面（**不并入 building**） | 纯事件驱动（ColonyEvaluation/Tourist*/DailySettlement），零 building import |
| 2 | npc | 独立 `content/npc`（殖民地自动化核心） | WandscapeNpc 是核心实体（84 文件引用、63 在包外）；命名/注册 id 分裂待 Tier 2 统一 |
| 2 | guard | 并入 npc（combat/defense 子包）或独立 `content/defense` | 14 类全以 WandscapeNpc 为对象、无实体/注册 id；Executor 注册进 world.opExecutors |
| 2 | scepter | 物品→items；系统层并入 npc（战斗 targeting 子模块） | ScepterApiImpl 被 npc isFriendlyForce + guard forcedHostile/isShelteredForAny 消费 |
| 2 | ring | 整包进 items（或极小 ring 模块） | OathRingService/SavedData 包外仅 Wandscape.java 初始化引用，无跨域消费 |

**跨批次耦合事实（由本批发现，供其它批引用，勿重定义）**：
- Mage/Npc 命名分裂波及全模组：`WandscapeNpc` 63 处包外引用 + 注册 id（`wandscape_npc`/`evil_mage`/刷怪蛋）+ lang key ~241 条（npc/mage/scepter/ring/evil 字样）+ SavedData 名（`wandscape_scepter_marks`/`wandscape_oath_rings`/`wandscape_npc_deaths`）——Tier 2 改名移动成本最高的一环，需先定统一词再四层同步改。
- scepter/ring/guard/npc 的事件处理器、SavedData 初始化、OpExecutor、server-tick 循环**集中在 `Wandscape.java` 显式接线**（bootstrap 474-485 / onServerTick 1183-1201 / SavedData 1022-1024），不是自动装配——拆包时接线点要跟着迁。
- `core/types/NpcAttributes` 并非 CLAUDE.md 所断言的 0 引用死代码（npc 的 EntityComponentBridge 在用）——文档漂移实证，Tier 1 删除候选名单需规避。
