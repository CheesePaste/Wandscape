# building/ — 建筑模块

`src/main/java/com/wsteam/wandscape/building/`

## 职责

殖民地建筑管理：建筑配置（JSON 数据驱动）、建造生命周期、每日结算、装饰加成、商店库存、奇观效果、交互界面、建筑扫描器。建筑**没有自定义方块**（除扫描器外），全部状态存于 `BuildingSavedData`。

## BuildingConfig（JSON → 数据）

`data/BuildingConfig.java` record，从 `data/wandscape/buildings/<id>.json` 解析（默认值见 Deserializer）。顶层字段（完整 JSON 树见 [data/buildings.md](../data/buildings.md)）：

`id / display_name / category / pattern / palette / block_indices / block_nbt / comfort / magic / wonder / queue{capacity,task_types} / unlock_requirement{min_colony_level} / boundary{min,max} / blueprint{id,bind($字段)} / node_config{blueprint,element,amount_per_harvest,channel_ticks} / decoration{radius} / wonder_config{effects} / shop{goods,profit_rate,interaction_duration_ticks} / service{energy_per_use,element_output,max_occupancy,interaction_duration_ticks} / door_offset / tourist_interact_aabb[] / first_free / deprecated`

> 注：`wonder_config` 字段已解析，但当前 `buildings/*.json` 均未定义它。
> 注：方块数据用**调色板**：`pattern`（N 个偏移）+ `palette`（M 个去重方块态）+ `block_indices`（N 个索引，与 pattern 对齐）。`block_mapping` 旧格式已废弃（解析器拒绝）。

`category` 实际值（从数据文件，见 data/buildings.md）：`government`（townhall1）、`storage`（warehouse）、`node`（node×7）、`shop`（bakery/book_shop/flower_shop/potion_store/sea_store/ancient_store/creature_store）、`service`（inn1/service_hall/deprecated library）、`tavern`（tavern）、`crafting_station`（craftstation1）、`potion_station`（potionstation1）、`workstation`（workstation1）。

## 建筑状态与持久化

- `BuildingState`：**无枚举状态机**，用布尔标志：`shutdown`、`structureIntact`、`demolishing`；持久字段含 shutdownReason/colonyId/rotationSteps/taskQueue(Deque<WorkItem>)/patternPositions/currentTaskId。`hasWork()`：关停建筑仅当队首是 `build:place_structure`（修复）才工作。
- `BuildingSavedData`（`wandscape_buildings`）：NBT 键含 shop_stock/shop_max_stock/claimed_free/pattern_pos/rotation 等；三个索引 buildings/posIndex/chunkIndex。

## BuildingApiImpl 公开方法

- 查询：getBuilding/getBuildingAt/getColonyBuildings/getBuildingBounds；聚落三值 getColonySnapshot/getColonyComfort/getColonyMagic/getColonyWonder。
- 生命周期：registerBuilding（重叠检查 + BuildingPlacedEvent）/unregisterBuilding；shutdown(id[,reason])（按类别 applyShutdownPenalties 零贡献 + BuildingShutdownEvent + 灰烟）；restart（恢复贡献 + 星光）；demolishBuilding（置 demolishing + structureIntact=false + 清队列 + 入队 `build:demolish_structure` 优先 49）。
- 队列：isBuildingOccupied/getBuildingsWithPendingWork/dequeueWork/enqueueWork（上限=queue容量或 5；`enqueueWork(buildingId, work, atFront)` 队首插入供紧急补货）/getBuildingsByCategory/setCurrentTask/getQueue/removeFromQueue/moveUp/moveDown。
- 放置：placeBuilding（firstFree 逻辑 + EnqueueHelper.buildWorkItem）；isFirstFreeClaimed；游客交互点：findBeds/sampleWalkableGround/getTouristInteractionTarget/getEntryPoint/getTouristInteractPoint。

## 建造生命周期

```
BuildingConfig JSON → BuildingConfigLoader → BuildingConfig
  → placeBuilding → EnqueueHelper.buildWorkItem
      · 解析 blueprint.bind 的 $field 引用
      · 按 palette+block_indices 自动算 material_list/counts
      · boundary → clear_offsets
      · 对 offsets/blocks/nbt/clear_offsets/door/AABB 做 90° 旋转
  → WorkItem 入 BuildingState.taskQueue
  → BuildingTaskSource.poll(20tick) → GlobalTaskPool → NPC 执行
  → 完成后 emit CustomEvent build_complete
```

- `EnqueueHelper.registerIfAbsent`：先 `getBuildingAt` 判占位，建 BuildingState、`api.registerBuilding`、assignColonyIfPossible；**首个建筑时给仓库每元素种 6000**（`colony.initialElementCount` 可配）。
- `BuildCompleteListener`：订阅 `build_complete`，`findDamagedBlocks` 逐块比对 palette 派生 map（含方块态属性）；损坏≥1/3 判 broken → `BuildingBreakHandler.enqueueRepairForOffsets`；完好 → 分配殖民地 + BuildingPlacedEvent + 烟花 + addBuildingContribution。
- `DemolishCompleteListener`：订阅 `demolish_complete`，unregisterBuilding + colonyApi.onBuildingDestroyed。
- `BuildingBreakHandler`：BreakEvent/ExplosionEvent 复检，broken 则 structureIntact=false、删贡献、town_hall 则删殖民地；**不自动入队修复**（修复只能玩家触发）。`triggerRepair` 供"修复"按钮（V 面板 Repair 或 AnomalyScreen）：复检损坏块（**轻微 <1/3 与 broken ≥1/3 都修**）→ 入队 `build:place_structure` 修复任务（优先 49, addFirst）。

## 五大子系统

### 1. DailySettlementSystem（每日结算）

每天 timeOfDay ≤ SETTLEMENT_WINDOW_TICKS(10) 发 DailySettlementEvent（带 SettlementReport(colonyId, day)）；订阅方：ShopStockManager（商店补货）、StatisticsCollector（统计快照）。

### 2. DecorationBonusSystem（装饰加成）

每 200 tick；源 = decoration 类建筑，目标 = 非 wonder 非 decoration；曼哈顿距离 ≤ decoration.radius(默认 8)；累加三值并按 `min(累计, 目标基础×DECORATION_BONUS_CAP=1.0)` 封顶。

### 3. ShopStockManager（商店库存）

库存持久化于 BuildingSavedData。`purchase` 扣库存 + 按 `ceil(元素价值×(1+profitRate))` 入账（**非固定 1.2X**，bakery 0.2 → 1.2X）；`walletPrice` = 各元素 ceil(v×(1+profitRate)) 之和；`purchaseAffordable`：游客预算 0.2–1.0×初始钱包、qty=floor(budget/price)+1；stock<maxStock 触发动态补货 restock，从仓库 consume 物品、可选 ItemTransportManager 运输动画、缺货走 ResourceSupplySystem.enqueueSynthesize（**队首插入**，抢在建材合成前）、pendingRestock 每 100 tick 重试；onDailySettlement 补货全商店。

### 4. WonderEffectApplier（奇观效果）

wonder 类且完整非关停生效；三种效果：`StatMod(target,value)` / `PriceMod(target,percentage)` / `RuleUnlock(ruleId)`（WonderEffect sealed + 按 type 字段反序列化）；shutdown 移除 + WonderEffectChangedEvent + 音效；查询 getStatMod/getPriceMod/isRuleUnlocked。

### 5. 辅助

- `BuildingContributionRegistry`：intactCounts 计数，0↔1 跨界才发 ColonyEvaluationChangedEvent；getSnapshot 逐实例累加（decoration 只辐射、shop 需有货+在库商品加成）。
- `BuildingUnlockChecker`：government/first_free → true，否则殖民地等级 ≥ min_colony_level。
- `ColonyAmbientTracker`：每 20 tick 判玩家是否在建筑包围盒膨胀 20 格内、白天 [1000,18000)，发 ColonyAmbientPacket + 120 tick 心跳。

## 交互界面（BuildingInteractHandler）

`handleInteraction` 按类别分发：government 无殖民地 → ColonyCreatePromptPacket；government 有殖民地 → TownHallOpenPacket（等级/经验/创建者名）；service(maxOccupancy>0) → HotelOpenPacket；storage → WarehouseDataPacket；workstation → WorkstationDataPacket；crafting_station → CraftingStationPacket；node → NodeDataPacket；shop → ensureStockInitialized + ShopOpenPacket；tavern → TavernOpenPacket（法师简历）；potion_station → "未实现"提示；service(非旅馆)/relax/decoration/atm → BuildingInfoPacket（通用信息面板 BuildingInfoScreen：service 显示产出元素 icon+数量、消耗精力/时间，relax 显示回复精力/时间，decoration/atm 一句话介绍，底部显示制作者）。触发限定 `PanelStateTracker.isPanelOpen`，先精确方块命中再 AABB 回退。屏幕均为 MedievalScreen 子类，靠收包打开。

## 扫描器（scanner/）

- **创造模式扫描器** `creative_building_scanner`（原 building_scanner 更名，给创作者）：含 FACING，右键开 CreativeScannerScreen；ScannerMode：BOUNDARY/DOOR/INTERACT/META/EXPORT；BE 另有 BlockMode SAVE/CORNER、TargetMode BUILDING/ROAD；`detectBoundaryFromCorners`（同 structureName 64 格内）、`detectDoors`（只计下半）。导出时扫描边界、跳过空气/扫描器、生成 pattern/palette/block_indices/block_nbt(base64 压缩 NBT)，写 JSON 到世界 datapack `wandscape_builds` 并即时可建；ROAD 模式导出道路预设。
- **生存模式扫描器** `building_scanner`：类别锁 "custom"、三值/交互区恒空，配原版合成配方。
- `ScannerPresetStore`：客户端 `<gameDir>/wandscape/scanner_presets/*.nbt` 的 list/load/save/delete。

## network/ 包

S→C：TownHallOpenPacket / ShopOpenPacket / HotelOpenPacket / TavernOpenPacket / NodeDataPacket / TaskQueueDataPacket。C→S：ShopMaxStockPacket（改上限）/ TavernRecruitPacket（招募）/ RequestGatherTaskPacket（harvests → NodeGatherTaskFactory 入队）/ TaskQueueModifyPacket（refresh/delete/move_up/move_down）/ ScannerSyncPacket / ScannerExportPacket。
