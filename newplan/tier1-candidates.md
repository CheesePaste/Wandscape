# Tier 1 死码候选表（IDEA 全仓检查 · 已滤杂音 + 置信度分级 · 已执行完成）

> 来源：`Analyze|Inspect Code`（默认 profile，32 错误/2489 警告）已滤拼写/风格/空方法/Javadoc/API 用法/未解析引用；已滤契约(抽象/接口)、事件/mixin/命令 handler、枚举/私有类构造。
> 判据：private 字段/方法信 IDEA 数据流「从未被读」；**public 面与数据/NBT/注册字符串引用不在此表**（闭环见 tier1.md 通道 B）。
> 置信度：**高**=已 grep 收紧无任何引用；**中**=IDEA 标记待核；**低**=`load(`(SavedData 加载器)/`tick*`/`worldTick`/tourist 流程等疑似被注册活着。删除仍须「build+test 绿 + grep 旧名零命中」。
> **执行状态（2026-08-30）**：全部经编译+全仓 grep 验证，死代码已删除，活代码（事件监听/命令建议器/方法引用等）已保留，`./gradlew test` 及 `./gradlew build` 全绿。

## A. 整类死的 record/类（已删除）
- [x] `shared/data/InterruptRecord` —— 整 record + 构造函数死（0 引用，使用的同名 record 在 `task/runtime/InterruptRecord`）。已删除。
- [x] `core/types/EquipmentPreset` —— 整 record 死（全仓 0 引用）。已删除。

## B. 特殊：record 组件死（工具抓不到，人工核）
- `core/types/NpcAttributes` —— **类未死**（`CoreBootstrap`/`EntityComponentBridge` 用作类型并传参），7 个组件 `maxHp/moveSpeed/spellPower/workSpeed/spellSpeed/armorValue/maxMana` **全 0 读取**（record 访问器 public 合成，IDE 默认不报）。且是 Mage 属性**重复定义**——归 Tier 3，本表只记录不删。

## C. 私有死字段（已全部清理，37 处）
- [x] `wonderEffectApplier` `src/main/java/com/wsteam/wandscape/Wandscape.java`（去除无用字段赋值，保留 `WonderEffectApplier.register()`）
- [x] `buildingPos` `src/main/java/com/wsteam/wandscape/building/client/AltarScreen.java`
- [x] `colonyId` `src/main/java/com/wsteam/wandscape/building/client/AltarScreen.java`
- [x] `buildingId` `src/main/java/com/wsteam/wandscape/building/client/HotelScreen.java`
- [x] `buildingPos` `src/main/java/com/wsteam/wandscape/building/client/HotelScreen.java`
- [x] `colonyId` `src/main/java/com/wsteam/wandscape/building/client/HotelScreen.java`
- [x] `colonyId` `src/main/java/com/wsteam/wandscape/building/client/MageHutScreen.java`
- [x] `atmDuration` `src/main/java/com/wsteam/wandscape/building/scanner/client/CreativeScannerScreen.java`
- [x] `atmWithdraw` `src/main/java/com/wsteam/wandscape/building/scanner/client/CreativeScannerScreen.java`
- [x] `metaComfort` `src/main/java/com/wsteam/wandscape/building/scanner/client/CreativeScannerScreen.java`
- [x] `metaCreator` `src/main/java/com/wsteam/wandscape/building/scanner/client/CreativeScannerScreen.java`
- [x] `metaId` `src/main/java/com/wsteam/wandscape/building/scanner/client/CreativeScannerScreen.java`
- [x] `metaMagic` `src/main/java/com/wsteam/wandscape/building/scanner/client/CreativeScannerScreen.java`
- [x] `metaName` `src/main/java/com/wsteam/wandscape/building/scanner/client/CreativeScannerScreen.java`
- [x] `metaWonder` `src/main/java/com/wsteam/wandscape/building/scanner/client/CreativeScannerScreen.java`
- [x] `nodeAmount` `src/main/java/com/wsteam/wandscape/building/scanner/client/CreativeScannerScreen.java`
- [x] `nodeChannel` `src/main/java/com/wsteam/wandscape/building/scanner/client/CreativeScannerScreen.java`
- [x] `relaxDuration` `src/main/java/com/wsteam/wandscape/building/scanner/client/CreativeScannerScreen.java`
- [x] `relaxEnergy` `src/main/java/com/wsteam/wandscape/building/scanner/client/CreativeScannerScreen.java`
- [x] `serviceDuration` `src/main/java/com/wsteam/wandscape/building/scanner/client/CreativeScannerScreen.java`
- [x] `serviceEnergy` `src/main/java/com/wsteam/wandscape/building/scanner/client/CreativeScannerScreen.java`
- [x] `serviceMaxOcc` `src/main/java/com/wsteam/wandscape/building/scanner/client/CreativeScannerScreen.java`
- [x] `shopDuration` `src/main/java/com/wsteam/wandscape/building/scanner/client/CreativeScannerScreen.java`
- [x] `shopProfitRate` `src/main/java/com/wsteam/wandscape/building/scanner/client/CreativeScannerScreen.java`
- [x] `unlockLevel` `src/main/java/com/wsteam/wandscape/building/scanner/client/CreativeScannerScreen.java`
- [x] `filesWritten` `src/main/java/com/wsteam/wandscape/element/internal/ElementValueGenerator.java`
- [x] `sprites` `src/main/java/com/wsteam/wandscape/magic/client/MagicCircleDotParticle.java`
- [x] `armorStacks` `src/main/java/com/wsteam/wandscape/npc/client/NpcScreen.java`
- [x] `knownSpells` `src/main/java/com/wsteam/wandscape/npc/client/NpcScreen.java`
- [x] `magicCatalog` `src/main/java/com/wsteam/wandscape/npc/client/NpcScreen.java`
- [x] `priority` `src/main/java/com/wsteam/wandscape/npc/client/NpcScreen.java`
- [x] `spellCategories` `src/main/java/com/wsteam/wandscape/npc/client/NpcScreen.java`
- [x] `strategyPreset` `src/main/java/com/wsteam/wandscape/npc/client/NpcScreen.java`
- [x] `lastPressAction` `src/main/java/com/wsteam/wandscape/shared/ui/component/TaskQueuePanel.java`
- [x] `entityId` `src/main/java/com/wsteam/wandscape/tourist/client/TouristScreen.java`
- [x] `buildingPos` `src/main/java/com/wsteam/wandscape/warehouse/client/WarehouseScreen.java`
- [x] `colonyId` `src/main/java/com/wsteam/wandscape/warehouse/client/WarehouseScreen.java`

## D. 私有方法核验与清理结果

### 真死方法（已删除）
- [x] `computeWorldBoxFromPattern(...)` `src/main/java/com/wsteam/wandscape/building/internal/BuildingSavedData.java`
- [x] `shortId(...)` `src/main/java/com/wsteam/wandscape/building/internal/ShopInteractionHandler.java`
- [x] `recalculateForBuilding(...)` `src/main/java/com/wsteam/wandscape/building/internal/WonderEffectApplier.java`
- [x] `removeEffects(...)` `src/main/java/com/wsteam/wandscape/building/internal/WonderEffectApplier.java`
- [x] `resolveBlock(...)` and `blockCache` `src/main/java/com/wsteam/wandscape/engine/boundary/WandscapeBlockOps.java`
- [x] `brighten(...)` `src/main/java/com/wsteam/wandscape/projection/client/BuildingDebugOverlay.java`
- [x] `drawCenteredText(String ...)` `src/main/java/com/wsteam/wandscape/projection/client/BuildingDebugOverlay.java`
- [x] `formatWorkItemTitle(...)` `src/main/java/com/wsteam/wandscape/shared/network/tasks/TaskPanelSyncTracker.java`
- [x] `getCols(...)` `src/main/java/com/wsteam/wandscape/shared/ui/panel/WandscapePanelController.java`
- [x] `drawDebugRect(...)` `src/main/java/com/wsteam/wandscape/shared/ui/util/BuildingPreviewRenderer.java`
- [x] `countSynthesizeInFlight(String, World)` (2 参数无用重载) `src/main/java/com/wsteam/wandscape/engine/system/ResourceSupplySystem.java`
- [x] `worldTick(...)` `src/main/java/com/wsteam/wandscape/engine/system/NavigationSystem.java`
- [x] `storePressAction(...)` `src/main/java/com/wsteam/wandscape/shared/ui/component/TaskQueuePanel.java`

### 经核验为存活代码（保留，禁止误删）
- 事件监听器 / 方法引用：
  - `commonSetup`, `onModConfig` (`Wandscape.java`)
  - `onClientTick`, `onPlayerLoggingOut` (`WandscapeClient.java`)
  - `onBuildComplete` (`BuildCompleteListener.java`)
  - `onClientTickPost` (`ScannerGizmoController.java`)
  - `onServerTick` (`AchievementService.java`)
  - `onEvent` (`StatsService.java`)
  - `onTaskCompleted` (`EventDrivenTaskSource.java`)
  - `onCoordChanged` (`ConstructionScreen.java`)
- 内部直接调用 / 流程活跃：
  - `onOpenMenu` (`MageHutServerHandler.java`)
  - `handleRejectMage` (`TavernRecruitPacket.java`)
  - `handleEnvironmentalDamage` (`SelfDefenseHandler.java`)
  - `handleEscape` (`ProjectionFlightController.java`)
  - `handleEscapeInput` (`RoadPlacementController.java`)
  - `handleBuildingSlotClick` (`WandscapePanelController.java`)
  - `extractItemOrRecipeIdJson` (`TaskPanelSyncTracker.java`)
  - `drawCenteredText(Component ...)` (`BuildingDebugOverlay.java`)
  - Tourist 相关全部内部调用：`tryHotelCheckIn`, `findBed`, `getBuildingDisplayName`, `eveningRouteToHotel`, `navigateToQueueSlot`, `returnToOwnHotel`, `routeToHotelBuilding`, `startQueueing`, `teleportToHotel`, `tickActivity`, `tickIndoorNav`, `tickOutdoorNav`, `tickQueue`, `abandonQueue`, `atOwnHotel`, `checkDeparture`, `depart`, `runTick`, `hasHotelVacancy`, `collectSpawnPositions`, `countOvernightStayers`, `containingBox`, `findGround`, `isInsideAnyBuilding`, `nearestRoadSpot`, `peripherySpot`
  - 其他内部渲染/逻辑：`openNodeGui`, `renderCornerMode`, `startGizmoDrag`, `exportRoad`, `renderBubble`, `batch`, `addElementInputs`, `checkDecomposePreconditions`, `checkPreconditions`, `emit`, `spawnElement`, `startGizmoDrag`, `drawArrayTab`, `drawCurveTab`, `layout`, `worldTick(TaskExecutionSystem)`
- Brigadier 命令提示器：`suggestTypes`, `suggestLayers`, `suggestStates`, `suggestToggle`
- SavedData 工厂方法：所有 `load(CompoundTag, HolderLookup.Provider)`
