# citizen/ — 游客生命周期管理

**原则:** 纯生命周期（spawn/despawn），移动行为由 `tourist/internal/TouristMoveGoal` 驱动。

## 关键类

### `citizen/CitizenState.java`

5 状态（由 TouristMoveGoal 自动同步，反映实际移动目标）：

| 状态 | 显示 | 含义 |
|------|------|------|
| `VISITING` | 前往建筑 | 导航到商店/服务建筑 |
| `EXPLORING` | 游览中 | 导航到 POI |
| `WANDERING` | 闲逛中 | 在家附近漫步 |
| `IDLE` | 空闲 | 静止不动 |
| `SLEEPING` | 睡眠中 | SLEEPING pose |

### `citizen/CitizenManager.java`

单例，殖民地级生命周期管理器。

**核心逻辑**：
- `evaluateAndSpawn()` — 基于建筑数（床数）计算人口上限，差额生成 tourist
- `tick()` — 清理已移除/死亡的实体
- `onBuildingPlaced` — 建筑放置后重新评估生成
- `spawnInitial()` — 世界加载时首次生成
- 持久化防护：`shouldBeSaved() = false` + `ServerStoppingEvent` discard + `ServerStartingEvent` 清残留

**关键 Map**：

| Map | Key | 含义 |
|-----|-----|------|
| `active` | entity UUID | 当前在世界的实体 |
| `bedAssignments` | entity UUID | 分配的床坐标 |
| `homeAssignments` | entity UUID | 宿舍建筑 anchor |

无 stored/workplace/profession — 所有实体始终可见。

### 移动 AI（已统一）

所有移动逻辑在 `tourist/internal/TouristMoveGoal.java`。详见 `architecture/packages/tourist.md`。

## 注册

- 实体：`wandscape:tourist`（复用 TouristEntity）

## 依赖

```
citizen/ → shared/api/BuildingApi.ts       (getColonyBuildings, findBeds, sampleWalkableGround)
        → shared/event/BuildingPlacedEvent (NeoForge 事件订阅)
        → shared/registry/WandscapeApis    (getBuildingApi)
        → tourist/entity/TouristEntity
```

## 测试命令

```
/wandscape citizen list                    列出 active
/wandscape citizen state <name|all> <state>  强制状态切换
```

有效状态：`visiting exploring wandering idle sleeping`
