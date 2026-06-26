# tourist/ — 游客实体与行为 AI

游客是殖民地的新非玩家角色类型。与 WandscapeNpc（殖民地工作 NPC）不同，游客不施法、不接任务、不参与生产——他们是纯访客，与商店/服务建筑交互，产生经济循环。

## 为什么不复用 WandscapeNpc

WandscapeNpc 承载 ECS 桥接、法杖、魔力池、任务执行器等完整殖民地 NPC 设施。游客不需要这些。独立实体类避免组件污染，行为逻辑更简洁。

## 关键类

### 实体

- **TouristEntity** (entity/) — extends PathfinderMob（非 WandscapeNpc）。属性：等级/精力值/满意度/建筑类型偏好Map（默认40，每次访问-15）/样貌(市民95%/法师5%)

### 数据

- **TouristAttributes** (data/) — record: level(int)/energy(int)/satisfaction(int, 0-100)/appearance(Appearance: CITIZEN/MAGE)

### API

- **TouristApi** (api/) — 接口：getActiveTourists(colonyId)/getTouristCount(colonyId)/getAverageSatisfaction(colonyId)/registerDeparture(touristId, colonyId, satisfaction)
- **TouristApiImpl** (internal/) — package-private 实现

### 行为系统 (internal/)

- **TouristSpawnSystem** ✅ — 每日清晨心跳（tick interval=1200）：
  1. 计算 targetCount = Config.baseSpawnCount + (comfort+magic+wonder) / Config.evalScoreDivisor
  2. 查询 RoadSavedData 获取道路边缘位置作为生成点（回退到建筑位置）
  3. 在道路上生成 TouristEntity，指定目标建筑（shop/service），类型偏好 lazy-default 为 40
  4. 设置 touristMode=true + commuteTarget=建筑交互目标位置
  5. cleanupTourists()：精力耗尽/夜幕/tick超时 → discard

- **TouristMoveGoal** ✅ — AI Goal（仅 touristMode=true 时激活），多站行程：
  - 沿道路网络寻路到 commuteTarget（BuildingApi.getInteractionTarget()）
  - 到达交互范围内触发 onArrived() → shop→ShopInteractionHandler / service→满意度+精力消耗 / hotel→checkIn
  - **加权建筑选择**：planNextBuilding() 按 typePreference × threeValueSum 加权随机选下一站，夜间优先宾馆
  - **偏好驱动满意度**：Δsat = min(√(typePref × (threeSum - level×threshold + 1)), maxPerVisit)，低于阈值→0增益
  - **偏好衰减**：每次交互后该建筑类型偏好 -15（TOURIST_PREFERENCE_DECAY），最低保底 5
  - 交互后设 commuteTarget=null → 短时 idle → planNextBuilding 或 cleanupTourists 移除

- **HotelStayHandler** ✅ — 宾馆入住/退房/精力恢复心跳
  - Map<UUID, Set<UUID>> 每建筑入住游客集合
  - checkIn() 检查容量，checkOut() 恢复精力+满意度加成
  - 心跳（每秒）：入住游客恢复精力，清晨自动退房

- **TouristInteractGoal** — 已并入 TouristMoveGoal.onArrived()。
- **TouristLeaveManager** — 已并入 TouristSpawnSystem.cleanupTourists()。
- **TouristSatisfactionHandler** — 已并入 TouristMoveGoal.computeSatisfactionGain()。

## 偏好系统

游客对每个建筑类型（buildingTypeId）有独立偏好值（5–100，默认 40）。

- **驱动建筑选择**：`weightedPick()` 用 `typePreference × threeValueSum` 做加权随机，偏好越高的建筑类型越容易被选中
- **驱动满意度**：`computeSatisfactionGain()` 公式中 typePref 直接影响满意度增量
- **衰减机制**：每次交互后对该建筑类型偏好 -15（可配置），最低保底 5。游客连续使用同类型建筑后会自动转向其他类型
- **新类型默认**：未接触过的建筑类型默认偏好 40，无需预生成

## 满意度公式

```
threshold = tourist.level × levelSatisfactionThreshold (默认 3)
threeSum = building comfort + magic + wonder + goods bonus

if threeSum < threshold → Δsat = 0       // 截断：建筑太低级
else → Δsat = min(√(typePref × (threeSum - threshold + 1)), maxPerVisit (默认 25))
```

- **level 影响**：高级游客门槛更高，低级建筑直接 0 收益
- **√ 递减**：避免单次交互拉满满意度
- **硬上限**：单次最多 25，保证至少 4 次不同建筑交互才满

## 数据流

```
每日清晨 (dayTime=0)
  → TouristSpawnSystem:
      1. spawnCount = base + (c+m+w) / divisor
      2. RoadSavedData → 殖民地边界道路位置列表
      3. 依次 spawn TouristEntity(随机目标建筑，类型偏好默认 40)
  → TouristMoveGoal: 道路寻路到目标
  → onArrived: 交互 + 偏好驱动满意度 + 偏好衰减
  → planNextBuilding: 加权随机选下一站（夜间优先宾馆）
  → 离开条件触发 → cleanupTourists
      → 满意度 ≥ 100 → depart（法师→酒馆简历）
      → 满意度 70–99 + 离开条件 → HotelStayHandler
          → 有空位 → 入住(心跳恢复精力)
          → 无空位 → depart
      → 满意度 < 70 + 离开条件 → depart
```

## 与道路系统联动

游客生成和移动都依赖道路系统：
- **生成位置**：不使用随机坐标，而是查询 RoadSavedData 找到殖民地边界处的路面位置
- **移动路径**：TouristMoveGoal 使用道路网络进行寻路，游客优先沿道路移动（不横穿建筑群）
- **战略意义**：道路布局直接影响游客流量——主干道沿线建筑曝光率高，偏僻建筑游客少

## 依赖

- shared/api/TouristApi
- shared/event/TouristArrivedEvent/TouristDepartedEvent
- shared/registry/WandscapeApis
- engine/road/RoadSavedData（道路网络查询）
- building/internal/ShopInteractionHandler, ShopStockManager（商店交互+货物三值）
- building/internal/BuildingConfigLoader（建筑三值查询）
- Config（TOURIST_PREFERENCE_DECAY, TOURIST_LEVEL_SATISFACTION_THRESHOLD, TOURIST_MAX_SATISFACTION_PER_VISIT 等）
- tavern/internal/TavernRecruitStorage（法师满意度100% → 酒馆）
- citizen/ （皮肤纹理复用）
