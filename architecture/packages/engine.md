# engine/ — MC 适配层

实现 core 边界接口，连接 ECS 引擎与 Minecraft 世界。

## 核心流程

`Wandscape.onServerStarting()` → `EngineBootstrap.bootstrap()` → `Wandscape.onServerTick()` → `world.tick(1.0f)`

## 引擎持有者

WandscapeEngine 单例持有：World + AsyncTransformExecutor + 各边界实现 + BlueprintConfigLoader + TaskPoolSavedData + RoadSavedData + ItemTransportManager + ColonyLevelManager。`reset()` 在 ServerStoppedEvent 清空静态状态。

## 统一指标服务 (service/)

`ColonyMetricsService` 需要引用所有模块 API（BuildingApi/TouristApi/NpcApi/WarehouseApi/ColonyLevelManager），放在 engine/ 符合依赖规则。它聚合实时殖民地指标，是 PanelStateTracker、PanelStateTogglePacket 和 AchievementService 的唯一数据源。

`AchievementService` 是成就授予器：进度定义在 `data/wandscape/advancement/*.json`（15 个，vanilla 系统，不自定义），它订阅 BuildingPlaced/ShopRestocked/LivingDeath/ColonyRaidVictory/ColonyLevelUp 事件 + 100tick 周期扫描，条件达成时 `PlayerAdvancements.award()` 授予全部在线玩家（授予幂等）。

`EngineBootstrap.register()` 前必须确保 `WandscapeEngine.setWorld(world)` 已完成——2026-07-29 修复了此前服务在 null world 上注册的时序 bug。

## TaskSource 实现 (source/)

BuildingTaskSource（每 20tick 轮询：清理完成/资源暂存 → 发布 WorkItem → TaskRequest 入池，是 BE→引擎的唯一桥梁）。发布前 `ChunkLoadManager.leaseBuilding` 强加载建筑 footprint（预算内），head 完成、且无暂存（AWAITING_RESOURCES）任务后 `releaseBuilding` 让区块卸载；`BuildingRemovedEvent` 触发拆除/注销释放。/ BlueprintConfigLoader / DataDrivenSteps（遗留 fallback）。纯 core 的 TaskSource 在 `task/source/`，RoadTaskSource 在 `road/engine/`。

## 按需强加载 (service/)

`ChunkLoadManager`（engine/service/）：殖民地在区块卸载时照常施工的核心。`leaseBuilding`/`releaseBuilding` 用 `BuildingState.getBounds().intersectingChunks()`（Stream<ChunkPos>）算 footprint，逐 chunk 引用计数 `ServerLevel.setChunkForced`——共享区块多建筑不会误卸。租赁注册表 `ChunkLeaseData`（SavedData `wandscape_chunk_leases`）持久化 buildingId→chunk 集合，server 启动时对账释放崩溃残留的 `ForcedChunksSavedData` 条目。并发上限 `Config.general.maxConcurrentBuildings`。NPC 无需物理到场：任务 TransformOp 在强加载区块里执行，ECS 逻辑全局 tick 驱动。

## 持久化

TaskPoolSavedData（跨会话，保存 blueprintId + params + stepIndex + **buildingId/isBuildingHead** → NBT，重载时从蓝图重新编译恢复进度；建筑归属落盘保证重启后 lease 释放/防重复施工正确）
ChunkLeaseData（跨会话，强加载租赁注册表，见上）

## ECS 系统 (system/)

NavigationSystem（≤64格寻路 + 卡死检测每60tick/3次→传送）/ ResourceSupplySystem（每40tick扫描 AWAITING_RESOURCES 任务，聚合需求 → 合成/采集）。均 `implements System`，注册到 World.tick()。

## 物品运输 (transport/)

ItemTransportManager：样条线数据发客户端（TransportRoute + SplineLeg 列表），服务器仅 elapsed 倒计时判定到达。客户端真插值（TransportItemEntity 用 tickLeg 执行样条线，60FPS 帧率平滑）。自定义渲染胶囊气泡（中性金边暗灰底）。

## 敌对生物索敌（HostileTargetingHandler）

`EntityJoinLevelEvent`：生物加入世界时若目标选择器已有对 AbstractVillager 的 `NearestAttackableTargetGoal`，则同优先级追加等价 goal——目标类型用公共父类 PathfinderMob + `instanceof VillagerLike` 谓词（实体区块存储 ClassInstanceMultiMap 只支持 Entity 子类查找、接口会崩）。不枚举生物类，自动覆盖僵尸族/灾厄村民/劫掠兽，排除中立生物（僵尸猪灵）。同一实体可能多次 join（维度传送/chunk 重载）：若目标选择器已存在目标类为 PathfinderMob.class 的等价 goal（本 handler 唯一标记），跳过避免叠加。

## ColonyApiImpl（engine 根包）

ColonyApi 实现，桥接 BuildingSavedData 查询殖民地信息。
