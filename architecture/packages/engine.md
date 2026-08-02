# engine/ — MC 适配层

实现 core 边界接口，连接 ECS 引擎与 Minecraft 世界。

## 核心流程

`Wandscape.onServerStarting()` → `EngineBootstrap.bootstrap()` → `Wandscape.onServerTick()` → `world.tick(1.0f)`

## 引擎持有者

WandscapeEngine 单例持有：World + AsyncTransformExecutor + 各边界实现 + BlueprintConfigLoader + TaskPoolSavedData + RoadSavedData + ItemTransportManager + ColonyLevelManager。`reset()` 在 ServerStoppedEvent 清空静态状态。

## 统一指标服务 (service/)

`ColonyMetricsService` 需要引用所有模块 API（BuildingApi/TouristApi/NpcApi/WarehouseApi/ColonyLevelManager），放在 engine/ 符合依赖规则。它聚合实时殖民地指标，是 PanelStateTracker、PanelStateTogglePacket 和 AchievementService 的唯一数据源。

`EngineBootstrap.register()` 前必须确保 `WandscapeEngine.setWorld(world)` 已完成——2026-07-29 修复了此前服务在 null world 上注册的时序 bug。

## TaskSource 实现 (source/)

BuildingTaskSource（每 20tick 轮询：清理完成 → 节点供给 → 发布 WorkItem → TaskRequest 入池，是 BE→引擎的唯一桥梁）/ BlueprintConfigLoader / DataDrivenSteps（遗留 fallback）。纯 core 的 TaskSource 在 `task/source/`，RoadTaskSource 在 `road/engine/`。

## 持久化

TaskPoolSavedData（跨会话，保存 blueprintId + params + stepIndex → NBT，重载时从蓝图重新编译恢复进度）

## ECS 系统 (system/)

NavigationSystem（≤64格寻路 + 卡死检测每60tick/3次→传送）/ ResourceSupplySystem（每40tick扫描 AWAITING_RESOURCES 任务，聚合需求 → 合成/采集）。均 `implements System`，注册到 World.tick()。

## 物品运输 (transport/)

ItemTransportManager：样条线数据发客户端（TransportRoute + SplineLeg 列表），服务器仅 elapsed 倒计时判定到达。客户端真插值（TransportItemEntity 用 tickLeg 执行样条线，60FPS 帧率平滑）。自定义渲染胶囊气泡（中性金边暗灰底）。

## 敌对生物索敌（HostileTargetingHandler）

`EntityJoinLevelEvent`：生物加入世界时若目标选择器已有对 AbstractVillager 的 `NearestAttackableTargetGoal`，则同优先级追加对 `shared/entity/VillagerLike` 的等价 goal。不枚举生物类，自动覆盖僵尸族/灾厄村民/劫掠兽，排除中立生物（僵尸猪灵）。

## ColonyApiImpl（engine 根包）

ColonyApi 实现，桥接 BuildingSavedData 查询殖民地信息。
