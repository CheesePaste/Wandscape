# Wandscape 认知地图 — 批 3/4 节稿（临时，newplan/packages34.md）

> tier0 可信全项目文档摸底 · **批 3（游客/经济域）+ 批 4（道路/玩家工具域）单独成稿**。
> 只读真实代码核实；旧 `docs/` 仅在「坑/旧文档矛盾」条对照，不作真相。
> 本稿为节稿，待各批完成后并入共享 `newplan/packages.md`（其结论表 = content/ 分包依据）。
> 规则：一个概念全地图只定义一次；简写优先，宁缺勿繁；填不出标「未探明」，不为填满而编。

## 批 3 游客/经济域

### tourist
- **职责**：短居访客经济域 —— 非居民（无 Profession/Bed/Workplace/Home/StoredCitizen，仅 `isMage()`+外观区分）。沿 `road` 出行 → 访 shop/service/relax/atm/hotel 建筑 → 补三条状态条（Comfort/Magic/Wonder）→ 花钱/取钱 → 酒店过夜 → 离城；满足离城 +colony exp；mage 游客(5%)存档简历供 tavern 招募。
- **改它先看**：`tourist/internal/TouristSimSystem.java`（枢纽，同时驱动 loaded 实体与 shadow）+ `TouristSimulation.java`（共享经济逻辑，操作 `TouristStateHost`）。`TouristSpawnSystem` 是喂料器；`TouristMoveGoal` 驱动 loaded 实体移动 AI。
- **数据流**：IN —— `TouristSpawnSystem` 每日计划：`BuildingApi.getColonyBuildings`（完好 shop/service/relax/atm 目标）+ `RoadApi` 网络端点 + `ColonyApi`/`ColonyLevelManager`（等级→spawn 数量与分布）；无玩家观察时 `TouristSimSystem` 推进 shadow（模拟距离内）。OUT —— `TouristSimulation.performShopInteraction`→`ShopInteractionHandler`/`ShopStockManager`→`ColonyItemBank`（商店利润）；`performServiceInteraction`→`cfg.service().elementOutput()`→`ColonyItemBank.addElement`；离城 `grantExperience`→`ColonyLevelManager`；`storeMageResume`→`TavernApi.receiveMageResume`(`TavernRecruitStorage`)；`TouristApiImpl.registerDeparture`→`TouristDepartedEvent`。
- **依赖**：`building.internal` + `ShopInteractionHandler`/`ShopStockManager` + `building.scanner.InteractSpotMarkerBlock`；`engine.{WandscapeEngine,ColonyActivation,ColonyLevelManager,ChunkLoadManager}` + `engine.nav.WandscapeNavigation` + `engine.service.ParticleService`；`road.core` + `road.engine.WandscapeTags`；`projection.BuildingRotation`；`warehouse.ColonyItemBank`；`shared.*`。**不直接 import npc/task/magic/element**（只经 warehouse + service `elementOutput` + `TavernApiImpl` 触元素）。
- **坑/旧文档矛盾**：(a) `TouristState` 确只是移动标签（VISITING/EXPLORING/WANDERING/IDLE/SLEEPING，是 `TouristMoveGoal.mapModeToState` 的单向镜像）——「无状态机」只在 `TouristState` 层成立；**真正的移动状态机是 `TouristMoveGoal.MoveMode`**（VISITING_BUILDING/EXPLORING_POI/WANDERING），IDLE/SLEEPING 无 MoveMode 对应。仍无常驻市民概念。(b) 「离线影子仿真」并非仅离线：`TouristSimSystem` 每 tick 在线上运行，无玩家观察（模拟距离内）就驱动 shadow，刻意忽略 chunk 状态（spawn chunk 玩家远离仍常驻）。shadow 在 chunk 卸载后存活，持久化 `TouristSimRegistry`(SavedData `wandscape_tourist_sim`)。「离线」只体现为 `ColonyActivation.isColonyActive` 冻结门（创始人离线→殖民地冻结）+ `offlineIncomeMultiplier` 削商利/服务产出/exp。(c) 旧 `TouristApiImpl.spawnTourist` 是 stub（"Phase C"占位）——实际 spawn 只在 `TouristSpawnSystem`。
- **归属**：纯消费/服务经济域，向外喂 colony（商利、服务元素产出、colony exp、mage 简历），向内消耗 colony 货物/元素/酒店容量——自身不建不产，独立但重度耦合 building+warehouse+engine+road。

### element
- **职责**：方块/物品→元素映射 + **元素值数据层**（非仿真/状态机）。定义每方块/物品在 7 元素上的 worth（`build_cost`），驱动建筑成本（`EnqueueHelper`）、工作站分解/合成、商店售出利润、`element_<id>` 物品 token。经济的 consume/produce 在 `warehouse.ColonyItemBank` / `building.EnqueueHelper` / `production.executeSynthesize` / `engine`。
- **改它先看**：`element/internal/ElementMappingLoader.java`（持全部配置注册表 + 查询；`ElementApiImpl` 只是委托它）+ `Wandscape.java:198-199,531` 接线（`ELEMENT_MAPPING_LOADER`/`ELEMENT_API`）。`ElementValueGenerator`/`ElementAuditor` 是 dev/gametest 工具，非运行时。
- **数据流**：IN —— `dataconfig.internal.WandscapeDataLoader` 注册 `element_mappings` 类别（`ElementMappingLoader` ctor）；`ElementValueGenerator` 反向从 MC `RecipeManager` + `element_seeds.json` 推导值（由 `command.GenerateElementMappingsCommand` 驱动）。OUT —— `shared.api.ElementApi`（经 `WandscapeApis.getElementApi()` 发布）；`ElementItem.inventoryTick` → `WarehouseApi.addElement`（玩家持它且在该殖民地内）。
- **依赖**：`dataconfig.internal.WandscapeDataLoader`、`shared.registry.WandscapeDataRegistry`、`shared.api.{ElementApi,ColonyApi,WarehouseApi}`、`shared.data.ElementType`、`engine.service.SoundService`/`engine.sound.WandscapeSounds`（仅 `ElementItem` 的声效）。**不直接 import magic/production/building/warehouse**。
- **坑/旧文档矛盾**：「7 元素」归 `shared.data.ElementType`（枚举 EARTH/WOOD/WATER/FIRE/METAL/WIND/DARK）所有，**不在 element 包**——若把 element 并入 magic 得连这个 shared.data 枚举一起搬。`ElementMappingLoader.getAllConfigs()` 过滤 `disabled` 映射（排除出合成/分解/审计）。`ElementValueGenerator` 读 MC `RecipeType.CRAFTING/SMELTING/…` 来**推导**值——是开发期值生成器，非线上玩法逻辑。**命名撞车（批 5 核实，跨节交叉引用）**：magic 有自己 `Element`/`ElementType`（法阵绘图形状 RING/ARC/POLYGON/STAR/GLYPH，见 `MagicCircleSpec`），与经济 `shared/data.ElementType` **同名但无关，勿合并**；且 magic 不消费经济元素（耗的是魔力/冷却），全 magic 包 grep 无 `shared.data.ElementType`。
- **归属**：真正的跨切数据/查询 + 工具层（被 building/road/engine/tourist/production 经 `ElementApi` 消费），独立于 magic —— **保持自身基础域，不归 magic**。

### production
- **职责**：配方式生产 —— 按建筑配方式收成物品。`craft_recipes` JSON 配方（wand/potion/spell/misc）+ 运行时由元素映射推导的合成配方，被殖民地级解锁（`RecipeUnlockRequirement`）与可负担性（`ProductionAffordability`）把关，前台是 workstation/crafting-station/magic-station GUI + 网络包。
- **改它先看**：`production/ProductionRecipeLoader.java`（持两个配方注册表 + 合成推导）。真正执行在 `engine.boundary.WandscapeBlockInteractExecutor.executeSynthesize/executeCraftWand/executeCraft`；任务请求入口 `production/network/RequestProductionTaskPacket.handleServer`。
- **数据流**：IN —— `dataconfig.WandscapeDataLoader`（`craft_recipes`）+ `element.ElementMappingLoader`（合成推导）。OUT —— `WorkItem` → `BuildingApi.enqueueWork(buildingId, work)`（进建筑任务队列）；GUI S→C 经 `CraftingStationPacket`/`MagicStationPacket`/`WorkstationDataPacket`；意图 C→S 经 `RequestProductionTaskPacket`。运行时对 `warehouse.ColonyItemBank` 结算。
- **依赖**：`element.internal.{ElementMappingConfig,ElementMappingLoader}`；`dataconfig`；`building.internal.{BuildingSavedData,BuildingState}` + `shared.api.BuildingApi` + `shared.data.WorkItem`（解析站点建筑 & 入队）；`warehouse.ColonyItemBank`；`engine.WandscapeEngine`（`RecipeUnlockChecker.isUnlocked`）；`shared.data.{ElementType,ItemKey}`。**不 import npc/task** —— 执行委托给 `block_interact` op（被 task/NPC 管线消费）；它只喂一个 `WorkItem`。
- **坑/旧文档矛盾**：`docs/modules/production.md` 过时。列蓝图动作 `craft_wand`/`brew_potion`；代码是单一 action `"craft"` → blueprint `production:craft`（`RequestProductionTaskPacket.java:73-82`，`CraftingStationScreen.java:268 action="craft"` 佐证），`CraftRecipeView.resolve` 再区分 wand/misc/potion。doc 说 `WORKSTATION_CRAFT_TICKS_PER_UNIT=10`；实际 `5`（`WandscapeConstants.java:36`）。`brew_potion` `default -> 120` 频道 ticks 分支近乎死码（`RequestProductionTaskPacket.java:146`），因客户端对 crafting station 只发 `craft`（`BrewPotionRecipe` 仍加载/序列化，但 crafting station 列表按 doc 仅 wand）。
- **归属**：独立的配方/生产层，是元素经济的「craft 门面」——钉在建筑任务流 + 元素成本上的成本/定义层，非独立自洽经济。

## 批 4 道路/玩家工具域

### road
- **职责**：殖民地道路网络域 —— 玩家手动铺路/填平/销毁、样条编辑器造路、道路方块建造任务、以及**路网图路由**（`RoadRouter.plan` 用 Dijkstra 规划运输/行走路线）。核心数据模型纯 Java（零 MC import），MC 适配在 `road/engine/`。
- **改它先看**：`road/algorithm/RoadRouter.java`（`plan(network,start,end,...)`，路由心脏，pure + 有单测）；客户端 `road/client/RoadPlacementController`（REPLACE/FILL/DESTROY_FILL）+ `SplineEditorController`/`SplineEditorInputHandler`（样条编辑器）；服务端权威入口 `road/engine/RoadSavedData.getOrCreate(level)` + `RoadApiImpl`。
- **数据流**：IN —— 客户端 `RoadPlacePacket`/`SplineBuildPacket`/`FillBoxPacket`/`DestroyFillPacket` → 服务端建 `RoadEdge` 入 `RoadSavedData` + 发 `TaskRequest` 进 engine 任务池 → 建好后 `CustomEvent("road_segment_complete")` → `RoadSegmentListener.onSegmentComplete` 置 edge COMPLETE。OUT —— `RoadRouter.plan` → `TransportRoute(List<SplineLeg>)` 供 engine/transport + NPC/游客移动消费；`RoadApiImpl.getNetwork` → `RoadApi`；`RoadSiteData.fromEdge` → `ConstructionSiteDataPacket`（工地 UI）；`RoadAreaSyncPacket` 广播刷新客户端 ghost。
- **依赖**：`core.types.GridPos`、`core.event.CustomEvent`、`engine.WandscapeEngine`/`ResourceSupplySystem`/`service.SoundService`/`WandscapeSounds`、`building.network.ConstructionSiteDataPacket`、`warehouse.ColonyItemBank`、`task.engine.pool.TaskRequest`、`task.source.PlayerManualSource`、`shared.api.RoadApi`、`shared.network/*`+`shared.ui/*`+`shared.registry/*`+`shared.log.Log`。**深度绑进 engine/task/warehouse/building 的建造管线，非自洽孤岛。**
- **坑/旧文档矛盾**：`docs/modules/road.md` 宣称「无路网图路由 / RoadRouter/TransportRoute/SplineLeg 已整体删除 / RoadNetwork 仅元数据不用于寻路」——**全与代码相悖**。`RoadRouter`（Dijkstra、T 字路口、野路 hop、sweep-line X 索引、`MAX_SEARCH_STEPS=500`）完整存在且有 `RoadRouterTest`/`RoadRouterStressBenchmark`。旧文档描述的 O(B²) 看门狗杀服根因，已被 AABB 预剔除 + sweep-line + 步数上限修掉。
- **归属**：**独立 content 域，保留**；但须接受与 engine/task/warehouse/building 的横向耦合（不是自洽孤岛）。

**「点/向量自造族」Tier 3 裁决口（本包落锤）**：自造族共 **4 个** —— `SplineVec3`(double x,y,z)、`PathPoint`(int x,y,z)、`XZPoint`(int x,z)、`core.types.GridPos`(int x,y,z)。`SplineVec3` 是 vanilla `Vec3` 的裁剪重写（同 `final double x,y,z` + `ZERO`/`add`/`subtract`/`scale`/`length`/`normalize`/`dot`；唯一差异 normalize 阈值 `1e-9` vs vanilla `1.0E-4`，并剪掉 `cross`/`distanceTo` 等）。road/core + road/algorithm **zero** `net.minecraft` import，且有 `SplineModelTest`/`RoadRouterTest` 直接构造它们。**裁决：保留自造族，不换 vanilla** —— 换 `Vec3` 虽不破坏测试（MC 在 test classpath），但①失去「核心零 MC 依赖」纯度承诺；②单测开始依赖 MC 类；③`PathPoint`/`XZPoint`/`GridPos` 无干净 vanilla 等价物（`BlockPos` 是 `Vec3i` 系非 record）。这是**设计哲学约束，非硬性技术卡点**；若 Tier 3 坚持收敛，首选方案是 `GridPos`+`PathPoint`+`XZPoint` 合并为一个 int 三元组点类（而非改 vanilla）。

### wand
- **职责**：NPC 法师法杖的物品载体 —— 数据驱动：预设 JSON → 属性加成 + tooltip/颜色/NBT。无玩家施放行为；属性只对 NPC 主手生效（玩家手持返回 `ItemAttributeModifiers.EMPTY`，避免玩家吃到加成，顺带避开 bastion 法杖负移速卡走）。
- **改它先看**：`wand/item/WandItem.java`（一个 `Item`，`getDefaultAttributeModifiers` 返 EMPTY）+ `wand/internal/WandPresetLoader.java`（注册 `craft_recipes` 类别，`WandPreset.fromJson` 过滤 `type!="wand"`）。
- **数据流**：IN —— `dataconfig.internal.WandscapeDataLoader.register("craft_recipes",...)` 读 JSON → `WandPreset(id, displayName, defaultColor, nbt, attributes)`；`WandItem.appendHoverText` 读 `WandApi.getWandPresetId(stack)`。OUT —— `WandApiImpl.getWandColor/getWandPresetId/getWandModifiers`（读 `CUSTOM_DATA` 的 `wand_color`/`preset_id`）→ `shared.api.WandApi`，供 `npc.WandscapeNpc.syncWandAttributes` 桥接 NPC 装备槽。
- **依赖**：`shared.api.WandApi`、`shared.registry.{WandscapeApis,WandscapeDataRegistry}`、`core.types.{AttributeModifier,AttributeType,ModifierOperation}`、`engine.attribute.WandscapeAttributes`、`dataconfig.internal.WandscapeDataLoader`。
- **坑/旧文档矛盾**：`docs/modules/wand.md` 称 tooltip「显示预设名 + 逐条属性加成（负数标红）」——**不符**。当前 `WandItem` 只 add 一条 `craft_recipe.wandscape.<presetId>`（WandItem.java:48），属性列表已不再渲染。
- **归属**：纯数据驱动 item + preset 数据 + 薄 API 实现，**无 system/state/SavedData/仿真内核**（仅 3 文件，无任何 tick/持久化逻辑）→ 可并入 `items`（WandApi 面归 `api/`，见 Tier 2e）。

### compass
- **职责**：玩家侧「魔法指南针」三档物品 —— 指针指向自己殖民地市政厅；高级/终极 tooltip 显坐标；终极右键传送到市政厅安全落点。
- **改它先看**：`compass/CompassService.java`（服务端静态真业务：`resolveTownHall`/`syncFor`/`teleportToTownHall`，含垂直 + 螺旋 `findSafeSpawn` 安全落点）+ `compass/MagicCompassItem.java`（持有 `CompassTier`，`use`/`useOn`/`inventoryTick`(每100tick) 路由）。
- **数据流**：IN —— `CompassSyncHandler`(`@SubscribeEvent` login/dimension) + `MagicCompassItem.inventoryTick` → `CompassService.syncFor`。OUT —— `resolveTownHall` → `CompassTargetPacket`(S→C) → 客户端 `CompassTargetClientCache`（`angle` item property + tooltip 消费）。传送 `player.teleportTo(overworld, ...)`。
- **依赖**：`raid.RaidTownHall.findTownHall(UUID)`、`shared.registry.WandscapeApis`(`getColonyApiSilently`)、`shared.log.Log`。无 engine/building 依赖。Curios 兼容实际在 `compat/curios/`（compass 包内无）。
- **坑/旧文档矛盾**：`docs/modules/compass.md` 与代码高度吻合。唯一注意：compass 包内无 Curios 代码，Curios 走外部 compat 层（不在本域）。
- **归属**：薄 —— `CompassTier` 是枚举、`CompassService` 是静态类且只被物品/登录事件调用，无 SavedData/无 tick 仿真/无内聚状态机。游戏性（市政厅定位+传送）是物品行为的外延 → 可并入 `items`；**但**会让 items 域背上 `raid.RaidTownHall` 单向只读依赖（可接受，或留 `colony` 下）。

### guidebook
- **职责**：指南书物品 —— 右键直接打开模组教程首页（`index_guide`）。
- **改它先看**：`guidebook/item/GuideBookItem.java`（`use()` 发包）+ `guidebook/network/GuideBookOpenPacket.java`（S→C，负载 `docPath`）。
- **数据流**：IN —— 玩家右键 → `PacketDistributor.sendToPlayer(GuideBookOpenPacket(INDEX_DOC))`。OUT —— 客户端 `handleClient` → `DocumentLoader` 按语言加载并打开阅读器（服务端不读资源）。
- **依赖**：**无任何跨域 import**（只引 `guidebook.network` + MC/NeoForge）。四包中唯一真正零耦合的域。
- **坑/旧文档矛盾**：无。`static setClientHandler` 是客户端 handler 注入点，真正打开逻辑在 `shared/ui`（指南书只是 `String docPath` 触发器）。
- **归属**：极简、无系统内核 → **并入 `items`**。

## 本批归属汇总（供并入共享结论表）

| 包 | 归属 | 备注 |
|----|------|------|
| tourist | 独立 content 域（经济/服务） | 纯消费→喂 colony（商利/元素产出/exp/mage 简历），自身不建不产；重度耦合 building+warehouse+engine+road。真移动状态机是 `TouristMoveGoal.MoveMode`，勿误当 `TouristState` |
| element | 独立基础域（元素值数据/查询层） | 不归 magic；「7元素」枚举 `ElementType` 在 shared/data，搬它得连搬 |
| production | 独立 content 域（配方/craft 层） | 元素经济的 craft 门面；钉在建筑任务流上（喂 `WorkItem`），不 import npc/task |
| road | 独立 content 域（保留） | 真图内核+样条+编辑器+持久化；深度绑 engine/task/warehouse/building 建造管线。**点/向量自造族保留**（Tier 3 裁决口，见 §road） |
| wand | 并入 items（WandApi 面归 api/） | 纯数据驱动 item+preset，无系统内核（3 文件，无 tick/持久化） |
| compass | 并入 items（或留 colony 下） | 薄（静态 `CompassService`，无 SavedData/仿真/状态机）；代价 items 背 `raid.RaidTownHall` 单向只读依赖 |
| guidebook | 并入 items | 极简零耦合，无系统内核；唯一真正零跨域 import 的域 |
