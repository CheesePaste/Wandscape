# raid/ — 袭击模块

`src/main/java/com/wsteam/wandscape/raid/`

## 职责

复用原版村庄袭击机制，让殖民地成为袭击目标。玩家带不祥之兆靠近非关停建筑 → 以**市政厅**为中心触发袭击；胜利后授予进度/事件。

## 配置（Config.java）

- `raid.triggerRange=10`：玩家带 BAD_OMEN/RAID_OMEN 在此水平 X/Z 外扩内（Y 不变）触发袭击。
- `raid.villageRange=16`：市政厅水平 ±此距离内 `isVillage` 返回 true。
- `raid.nearbyRadius=64`：袭击中心距市政厅此距离内不重复触发（同时只一场）。
- `raid.checkIntervalTicks=20`：扫描间隔。

## RaidTriggerScanner

- 扫描间隔 = RAID_CHECK_INTERVAL(20)；RAIDS_DISABLED gamerule 或维度无袭击则跳过；遍历非旁观、带 BAD_OMEN 或 RAID_OMEN 的玩家。
- `triggerForPlayer`：逐殖民地找市政厅 → `isNearBuilding`（玩家在任一非停摆且完整建筑 AABB ±triggerRange 内，Y 不扩）→ 市政厅 nearbyRadius 内已有袭击则跳过 → `ensureRaidOmen`（仅 BAD_OMEN 时补 RAID_OMEN 600 tick + 同放大器）→ `createOrExtendRaid(player, townHall)` → track + 发 `ColonyRaidStartedEvent`（含 omenLevel、numGroups）+ 政府建筑中心上方橙色营火烟粒子。袭击以市政厅为中心。

## ColonyRaidTracker

- `track(colonyId, raid)` 记 raidId；`tick`：每 RELINK_INTERVAL_TICKS=200 relink（重载恢复）；轮询 `raid.isVictory()` → 发 `ColonyRaidVictoryEvent`（含 groupsSpawned=实际波数）+ 市政厅上方烟花庆祝；isOver 移除。`relink` 用 getNearbyRaid(townHall, RAID_REMOVAL_THRESHOLD_SQR) 重挂。

## RaidTownHall

`findTownHall` 取首个完整（intact）的 government 类建筑位置；`isNearTownHall` 水平切比雪夫距离判定。

## MixinServerLevel

@Inject `isVillage(BlockPos)` HEAD cancellable——市政厅水平 ±RAID_VILLAGE_RANGE 内返回 true。原版 `Raid.tick()` 每 tick 查 isVillage(center)，否则判 STOP/LOSS；此钩子让袭击中心始终为村庄。

## 事件

`ColonyRaidStartedEvent` / `ColonyRaidVictoryEvent`（shared/event/，均 extends Event）在 NeoForge EVENT_BUS 上触发，携带 colonyId/raidId/center/omenLevel/波数，供成就系统消费。
