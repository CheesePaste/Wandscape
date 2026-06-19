# 事件目录

所有 NeoForge EventBus 事件的集中索引。每个事件记录：类名、发布模块、订阅模块、触发时机、携带数据。

## 事件清单

### 殖民地事件

| 事件 | 发布方 | 订阅方 | 触发时机 | 关键字段 |
|------|--------|--------|---------|---------|
| ColonyCreatedEvent | 15 colony-lifecycle | 08 building-core, 06 task-system | 殖民地创建完成 | colonyId, townHallPos |
| ColonyDeletedEvent | 15 colony-lifecycle | 全部建筑模块 | 殖民地删除 | colonyId |

### 任务事件

| 事件 | 发布方 | 订阅方 | 触发时机 | 关键字段 |
|------|--------|--------|---------|---------|
| TaskPublishedEvent | 06 task-system | 08 building-core | 任务发布到全局池 | taskId, colonyId |
| TaskAssignedEvent | 06 task-system | 07 npc-system, 08 building-core | NPC 分配到任务 | taskId, npcId |
| TaskCompletedEvent | 06 task-system | 08 building-core, 09 node-building | 任务执行完成 | taskId, npcId |
| TaskInterruptedEvent | 06 task-system | 07 npc-system, 08 building-core | 任务中断 | taskId, npcId, reason |
| TaskAwaitingMaterialsEvent | 06 task-system | 04 warehouse-system, 14 management-panel | 元素/物品不足 | taskId, missingElement, required, available |

### 建筑事件

| 事件 | 发布方 | 订阅方 | 触发时机 | 关键字段 |
|------|--------|--------|---------|---------|
| BuildingPlacedEvent | 08 building-core | 06 task-system, 14 management-panel | 建筑结构验证通过 | buildingId, colonyId, buildingTypeId |
| BuildingShutdownEvent | 08 building-core | 09 node-building, 10 production-stations | 建筑关停 | buildingId |
| BuildingRestartedEvent | 08 building-core | 09 node-building, 10 production-stations | 建筑重启 | buildingId |
| MaintenanceTickEvent | 08 building-core | 04 warehouse-system | 维护结算周期到达 | colonyId |

### NPC 事件

| 事件 | 发布方 | 订阅方 | 触发时机 | 关键字段 |
|------|--------|--------|---------|---------|
| NpcDiedEvent | 07 npc-system | 13 ritual-altar, 14 management-panel | NPC 生命值归零 | npcId, deathPos, graveData |
| NpcResurrectedEvent | 13 ritual-altar | 07 npc-system, 14 management-panel | 复活仪式完成 | npcId, altarId |
| NpcRecruitedEvent | 12 tavern-recruitment | 07 npc-system, 11 housing-mana-pool | 招募完成 | npcId, tavernId |

### 元素事件

| 事件 | 发布方 | 订阅方 | 触发时机 | 关键字段 |
|------|--------|--------|---------|---------|
| ElementChangedEvent | 04 warehouse-system | 14 management-panel | 元素储量变化 | colonyId, type, newAmount, delta |

## 事件使用规则

1. **所有事件类定义在 `01-shared-api` 中**（`shared/event/` 包）
2. **优先级**：所有监听器使用 `EventPriority.NORMAL`。需要顺序保证的行为用 API 直接调用，不依赖事件触发顺序
3. **事件仅用于通知**：发送方不知道（也不关心）谁在监听。需要返回值/确认的行为用 API
4. **不阻塞事件线程**：事件处理器中的重操作应异步执行

## 阶段 0 状态

- 全部 15 个事件类已创建在 `com.wsteam.wandscape.shared.event/` 包
- 所有事件继承 `net.neoforged.bus.api.Event`，字段 `private final` + getter
- 发布方和订阅方待对应模块实现时添加 `post()` 和 `@SubscribeEvent`

> **维护规则**：新增事件类时在此文件添加一行。修改事件字段时更新"关键字段"列。删除事件时移除行。
