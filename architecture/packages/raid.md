# raid/ — 袭击机制（复用原版村庄袭击）

殖民地版村庄袭击：玩家携带不祥之兆靠近建筑触发，**100% 复用原版 `Raid`/`Raids`**（波次表、掠夺者/卫道士/唤魔者/女巫/劫掠兽、Boss 条、袭击号角、村庄英雄、RAID_WIN 统计、SavedData 持久化）。本模块只写三小块：**isVillage mixin + 触发扫描器 + 胜利跟踪**。

## 核心闭环

```
玩家带 RAID_OMEN/BAD_OMEN 靠近任意非停摆建筑 AABB 水平 ±raid.triggerRange(10)（Y 不扩展）
  → RaidTriggerScanner（每 raid.checkIntervalTicks=20 tick，onServerTick 驱动）
  → 找该殖民地市政厅 → getNearbyRaid(市政厅, nearbyRadius²) 无活跃袭击才继续
  → ensureRaidOmen（BAD_OMEN→RAID_OMEN）→ level.getRaids().createOrExtendRaid(player, 市政厅)
  → 原版 Raid 全链路接管（波次刷新在市政厅周边 ±32）
  → ColonyRaidTracker 轮询 isVictory() → NeoForge 广播 ColonyRaidVictoryEvent → 移除
```

## 关键机制

- **中心 = 市政厅**：触发点可能是殖民地边缘的建筑，但 `createOrExtendRaid` 传入的是市政厅位置（无原版村庄 POI → 中心退回传入位置）。触发范围（任意建筑 ±10）与袭击中心（市政厅）刻意分离。
- **isVillage mixin（关键）**：原版 `Raid.tick()` 每 tick 检查 `isVillage(center)`，中心不在村庄则 STOP/LOSS。`MixinServerLevel` 让 `ServerLevel.isVillage(pos)` 在市政厅 `raid.villageRange`(16) 内返回 true → 原版袭击把殖民地当村庄：中心不被挪走、波次正常刷新、`updateRaiders` 把远离殖民地的袭击者清理掉。
- **索敌/防御全复用**：掠夺者等袭击者自动索敌 NPC/游客——`HostileTargetingHandler` 已给索敌村民的生物补 `VillagerLike` 目标；NPC 防御走 guard + 自防御。
- **胜利事件**：`ColonyRaidTracker` 内存表 `colonyId→raidId`，每 tick `level.getRaids().get(raidId)`，`isVictory()` → `ColonyRaidVictoryEvent`（带 colonyId/raidId/center/omenLevel/groupsSpawned），成就系统订阅即用。服务器重载后每 200 tick `relink` 把进行中的袭击重新挂回。
- **无失败概念**：mixin 使 isVillage 恒 true，原版 LOSS（村庄被毁）基本不触发；袭击的真实代价是掠夺者破坏建筑 → `structureIntact=false` → 三值扣减 + 自动修复（现有 `BuildingBreakHandler` 机制）。

## 关键文件

| 文件 | 职责 |
|------|------|
| `RaidTownHall.java` | 市政厅定位（GOVERNMENT 分类 + structureIntact）+ `isNearTownHall` 判定 + 纯几何 `withinHorizontalRange`（可单测） |
| `RaidTriggerScanner.java` | 触发扫描器：带不祥之兆玩家近建筑 → `createOrExtendRaid(市政厅)` + 广播 `ColonyRaidStartedEvent` |
| `ColonyRaidTracker.java` | 殖民地↔袭击跟踪：轮询 `isVictory()` → 广播 `ColonyRaidVictoryEvent`；`relink` 重载恢复 |
| `mixin/MixinServerLevel.java` | `@Inject ServerLevel.isVillage(BlockPos)`：市政厅 `raid.villageRange` 内返回 true |
| `shared/event/ColonyRaidStartedEvent.java` | NeoForge 事件：colonyId/raidId/center/omenLevel/numGroups |
| `shared/event/ColonyRaidVictoryEvent.java` | NeoForge 事件：colonyId/raidId/center/omenLevel/groupsSpawned |

## 注册点

| 注册点 | 位置 |
|--------|------|
| `MixinServerLevel` | `src/main/resources/wandscape.mixins.json` 的 `"mixins"` 数组 |
| `RaidTriggerScanner.INSTANCE.tick(overworld)` + `ColonyRaidTracker.INSTANCE.tick(overworld)` | `Wandscape.java` `onServerTick`（①h，guard 自防御之后） |
| `raid.*` 配置 | `Config.java`：`triggerRange`(10)/`villageRange`(16)/`nearbyRadius`(64)/`checkIntervalTicks`(20) |

## 依赖与边界

- 依赖 `ColonyApi.getAllColonyIds()` + `BuildingApi.getColonyBuildings`/`getBuildingBounds`（跨模块不直接引用 building/internal）；市政厅 AABB 判定复用 `guard/GuardZone`。
- 只处理主世界（殖民地所在维度，`Wandscape.onServerTick` 传 `event.getServer().overworld()`；`createOrExtendRaid` 内部再校验 `dimensionType().hasRaids()`）。
- 袭击本体、持久化、波次、奖励全部由原版负责——本模块不写任何袭击内容。
