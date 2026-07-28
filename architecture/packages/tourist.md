# tourist/ — 游客实体与行为 AI

游客是短居非玩家角色。与 WandscapeNpc（殖民地工作 NPC）不同——不施法、不接任务、不参与生产。纯访客，与商店/服务建筑交互，产生经济循环。

## 为什么不复用 WandscapeNpc

WandscapeNpc 承载 ECS 桥接、法杖、魔力池、任务执行器等完整设施。游客不需要这些：独立实体类（extends PathfinderMob，非 WandscapeNpc）避免组件污染，行为逻辑更简洁。

## 行为系统

- **TouristSpawnSystem** — 每日清晨心跳：`targetCount = base + (comfort+magic+wonder) / divisor`。查询 RoadSavedData 获取道路边缘位置生成。设置 commuteTarget = 建筑交互目标
- **TouristMoveGoal** — 统一移动 AI，MoveMode 状态机：
  - VISITING_BUILDING：加权选建筑（`typePreference × threeValueSum`）→ 交互
  - EXPLORING_POI：随机远 POI → 停留 5-15 秒
  - WANDERING：半径内随机漫步
  - 转移概率：BUILDING 后 60%/25%/15%，POI 后 50%/30%/20%，WANDER 后 40%/30%/30%
- **HotelStayHandler** — 入住/退房/精力恢复心跳。每建筑容量管理，清晨自动退房
- **TouristInteractGoal / TouristLeaveManager / TouristSatisfactionHandler** — 已分别并入 TouristMoveGoal 和 TouristSpawnSystem

## 偏好系统

游客对每个 buildingTypeId 有独立偏好值（5–100，默认 40）。

- **驱动建筑选择**：`weightedPick()` 用 `typePreference × threeValueSum` 加权随机
- **驱动满意度**：公式中 typePref 直接影响 Δsat
- **衰减**：每次交互后该类型偏好 -15（可配置），最低 5。未接触过的类型默认 40

## 满意度公式

```
threshold = tourist.level × levelSatisfactionThreshold (默认 3)
threeSum = building comfort + magic + wonder + goods bonus
if threeSum < threshold → Δsat = 0        // 截断
else → Δsat = min(√(typePref × (threeSum - threshold + 1)), maxPerVisit (25))
```

Level 越高门槛越高，√ 递减保证至少 4 次交互才满，硬上限 25。

## 数据流

```
每日清晨 (dayTime=0)
  → TouristSpawnSystem:
      spawnCount = base + (c+m+w) / divisor
      道路位置列表 → 依次 spawn TouristEntity
  → TouristMoveGoal: 道路寻路到目标
  → onArrived: 交互 + 偏好驱动满意度 + 偏好衰减
  → planNextBuilding: 加权随机选下一站（夜间优先宾馆）
  → 离开条件触发 → cleanupTourists
      → 满意度 ≥ 100 → depart（法师→酒馆简历）
      → 70–99 + 离开条件 → HotelStayHandler（有空位入住/无空位depart）
      → < 70 + 离开条件 → depart
```

## 与道路系统联动

游客生成和移动都依赖道路系统：RoadSavedData 边界路面位置生成，RoadRouter 路网寻路。道路布局直接影响游客流量。

## 依赖

- shared/api/TouristApi / shared/event/TouristArrivedEvent/TouristDepartedEvent / WandscapeApis
- engine/road/RoadSavedData
- building/internal/ShopInteractionHandler, ShopStockManager
- Config（衰减/阈值/上限等参数）
- tavern/internal/TavernRecruitStorage
