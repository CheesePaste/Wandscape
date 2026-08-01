# 游客行为 Bug 分析（交互 / 卡住 / 住宿 / 游荡）

> 2026-07-31 探测。定位到 4 个相互关联的游客 AI bug，全部与 `TouristMoveGoal` 的
> 移动/交互状态机、建筑拆除的异步链、以及 `visitedBuildings`/冷却 的误用有关。
> 本文只做根因分析，供后续修复。修复状态如下表，每修一个更新一个。

## 修复状态

| Bug | 描述 | 状态 |
|-----|------|------|
| Bug 1 | 建筑 Demolish 后游客（含新生成）仍可虚空交互 | ✅ 已修复 2026-08-01（unregister 提前到 NPC 派发时 + 交互前重新校验 + 排程不持引用） |
| Bug 2 | 游客卡在羊毛块之间 / 建筑原点，状态 VISITING 卡死 | ✅ 已修复 2026-08-01（根因：交互区未按 rotationSteps 旋转；治标：卡死强制回游荡） |
| Bug 3 | 满意度 60 + 有 inn，黄昏仍不入住直接离开 | ✅ 已修复 2026-08-01 |
| Bug 4 | 游荡不合理：目标易走却频繁传送、长时间停留、范围小 | ✅ 已修复 2026-08-01（节点推进判卡住/地表扫地面/目标可达性/锚点漂移/立即重挑） |

---

## 总览：四个 bug 的共同根因

1. **游客目标过滤只认 `isShutdown()` / `isStructureIntact()`，从不认 `isDemolishing()`。**
   所有筛选点（`planNextBuilding`、`hasBuildingsAvailable`、生成系统的 `getTouristTargets`）
   都漏了 `isDemolishing`，而 `demolishBuilding()` 置了 `demolishing=true` 却不改
   `structureIntact`、也不 shutdown → 拆除中的建筑一直是 100% 合法游客目标（Bug 1）。
2. **交互区/交互点是「规划时快照」，不是实时世界状态。**
   `touristInteractZones`、`interactPoint` 在 `planNextBuilding` 时由 config AABB + anchor 算一次，
   之后无论建筑被拆、楼层被拆、游客卡在哪，`BoundingBox.isInside`（全闭区间，含 Y±2 外扩）都能命中 →
   游客在楼层下方/空地上也触发交互（Bug 1、Bug 2）。
3. **`visitedBuildings` 把「交互过一次」等同于「不用再去了」。**
   白天普通逛 inn 会把 inn 计入 visited，晚上住宿路由 `tryRouteToHotel` 和 `planNextBuilding`
   都直接跳过 visited 建筑 → 游客永不入住（Bug 3）。
4. **卡住检测阈值 `distSqr(lastPos) < 1.0` 过粗 + 目标点不可达性未校验 + 游荡锚点固定。**
   绕障碍/原地抖动能被误判「没动」触发传送；`findGround` 可能落到屋顶/结构内；
   游荡每 60–180 tick 才换一次目标，且锚点半径固定 8 格（Bug 4，并放大 Bug 2）。

> 另注意：2026-07-31 的提交 `5762302`（交互时长与冷却合二为一）把「到达交互点站立
> 计时再交互」改成了「进入交互区立即交互」。这让 Bug 2 的「误入交互区即触发交互」从
> 「站一会儿」变成了「立即生效」，放大了下方楼层/夹层位置的误触发。

---

## Bug 1：建筑 Demolish 后游客（含新生成）仍可虚空交互

### 现象

- V 面板点 Destroy → 执行 Demolish，建筑方块消失、V 面板准心无法再选中它（正常）。
- 但旧游客仍能与该「空建筑」交互；退出重进后仍可交互；**新生成的游客也能交互**。

### 现象推导（2026-08-01 修正）

建筑**已完全拆除**（方块成平地），但数据只删了一部分，残留在 `BuildingSavedData.buildings`：
- 放新建筑报「碰撞箱重叠」→ `register()` 遍历 `buildings.values()` 做重叠检查 → 被拆建筑还在 map 里。
- 游客仍能交互 → 游客侧仍能命中它（生成排程持旧引用 / 交互不重新校验）。
- **V 面板「看不到」是假象**：右键交互走 `getBuildingIdInInteractionZone`（带 `isStructureIntact` 过滤），方块被拆时 `structureIntact` 已置 false → 返回 null；边界框靠射线命中方块，方块没了打到下方地面 → 无响应。两者都不说明数据被清。

**真正的断点**：`demolish_complete → listener → unregister` 这条链**在实践中永久断掉**——NPC 确实执行了 demolish（方块消失），但 unregister 从未运行。这不是「异步窗口」，而是数据清理完全没发生。

### 根因 1a：游客目标过滤漏掉 `isDemolishing`

拆解调用链（从 Destroy 到数据消失）：

```
V面板 Destroy 按钮
  → BuildingActionPacket.handleServer "destroy"        (BuildingActionPacket.java:63)
  → api.demolishBuilding(buildingId)                   (BuildingApiImpl.java:284)
      ├─ state.setDemolishing(true); queue.clear()     (BuildingApiImpl.java:297-298)  ← 只标记 demolishing
      └─ 入队 WorkItem("build:demolish_structure")     (BuildingApiImpl.java:326)      ← 异步，靠 NPC 执行
  → NPC 执行 blueprint：for_each 填空气 → emit_event demolish_complete
  → DemolishCompleteListener.onDemolishComplete        (DemolishCompleteListener.java:41)
  → api.unregisterBuilding(anchor)                     (DemolishCompleteListener.java:71)
      └─ sd.unregister(buildingId)                     (BuildingApiImpl.java:149)      ← 这才真正删数据
```

**问题**：从「入队 demolish」到「unregister 删数据」是一段**异步窗口**（依赖 NPC 领取并执行完任务、
事件送达、listener 命中）。窗口内建筑状态是 `demolishing=true, structureIntact=true, shutdown=false`。

而游客侧所有筛选点只检查后两者：

| 筛选点 | 位置 | 检查 |
|--------|------|------|
| `TouristMoveGoal.planNextBuilding` | TouristMoveGoal.java:986-987 | `isShutdown() \|\| !isStructureIntact()` |
| `TouristMoveGoal.hasBuildingsAvailable` | TouristMoveGoal.java:899 | 同上 |
| `TouristSpawnSystem.getTouristTargets` | TouristSpawnSystem.java:593 | 同上 |
| `BuildingSavedData.getTouristInteractPoint` | BuildingSavedData.java:247 | `isShutdown() \|\| !isStructureIntact()` |
| `BuildingSavedData.getEntryPoint` | BuildingSavedData.java:288 | 同上 |

→ 拆除中的建筑对游客完全可见、可选、可交互。

### 根因 1b：异步链中断 = 永久幽灵建筑

如果 NPC 任务未执行完 / `demolish_complete` 事件未送达 / 服务器在任务中途重启，建筑就**永远不会被 unregister**：
- `demolishing` 字段**不写入 NBT**（见 `BuildingSavedData.save`，只存 shutdown/intact/rotation…），重载后自动归 false →
  幽灵建筑「看起来更正常」，更不可能被游客侧排除。
- 建筑永久以 `intact=true` 存在 → 旧游客、重进后的游客、以及此后每天新生成的游客都会持续选中它。

### 根因 1c：交互执行时不重新校验建筑

即使建筑真的被 unregister，`performBuildingInteraction`（TouristMoveGoal.java:530）也不检查建筑当前是否存在：
```java
UUID buildingId = tourist.getTargetBuildingId();       // 缓存值，不查 SavedData
String category  = tourist.getTargetBuildingCategory(); // 缓存的类别
...
if ("shop".equals(category)) interactWithShop(buildingId);    // 用缓存 id 直接结算
```
`interactWithShop` 走 `ShopStockManager`（按 buildingId 键控），`unregisterBuilding` 又**不清理商店库存**
（`BuildingApiImpl.unregisterBuilding` 只调 `sd.unregister`，未调 `removeShopData`）→
即使建筑没了，游客仍能「买到货」、涨满意度、扣精力、记行程。

### 根因 1d：规划时快照的交互区/交互点不随拆除失效

`planNextBuilding`（TouristMoveGoal.java:1081-1105）在规划时把 `interactPoint` 和 `touristInteractZones`
（由 config AABB + anchor 算出的世界坐标盒）缓存下来。建筑拆除后这些坐标盒仍存在、仍参与到达判定 →
游客走到空地上的原点，`zone.isInside(pos)` 照样命中 → 交互照样触发。

### 根因 1e：当日生成排程捕获了旧建筑引用（新游客的另一个来源）

`TouristSpawnSystem.createSchedule`（TouristSpawnSystem.java:252-259）在每天生成排程时**直接持有
`BuildingState target` 和 `interactionTarget` 的引用**。若排程在拆除前创建、拆除发生在上半天，
则 `pendingSpawns` 里的 `PendingSpawn` 仍指向被拆建筑，`flushPendingSpawns`（:287）生成的新游客
照旧 `setCommuteTarget(interactionTarget)` 直奔空建筑。

### 修复方向（供后续）

1. 所有游客目标筛选（1a 表格里的 5 处）增加 `!isDemolishing()` 条件；或更彻底：`demolishBuilding()` 入队时就
   `setStructureIntact(false)`，让建筑从入队瞬间起对游客失效。
2. `performBuildingInteraction` 交互前重新校验建筑存在 + intact + 非 demolishing；不存在则直接
   `finishBuildingStop()` 并重规划。
3. `unregisterBuilding` 时同步 `ShopStockManager`/`removeShopData`，并让正在前往该建筑的游客目标失效。
4. `demolish_complete` 事件增加幂等兜底（如对已不存在/已 unregister 的建筑不报错）。
5. `createSchedule` 生成排程时不要持有 `BuildingState` 引用，改存 buildingId，spawn 时实时查；
   或 spawn 前校验目标仍有效。

### 修复方式（2026-08-01 实施）

采纳「unregister 提前到 NPC 派发时」——数据清理不再依赖 `demolish_complete → listener` 这条易断的异步链：

1. **`BuildingApiImpl.dequeueWork`**：取到 `build:demolish_structure` 任务时立即 `unregisterState()`。
   NPC 执行 demolish 是「方块消失」的直接原因（已证实），此刻删数据；方块破坏用 WorkItem 里的快照
   params，与数据清理解耦。`demolish_complete` listener 保留为幂等兜底。
2. **`BuildingApiImpl.demolishBuilding`**：入队即 `setStructureIntact(false)`，关闭「点击 → 下次 poll」
   之间 ~20 tick 的游客选择窗口。
3. **`unregisterState` 增加 `removeShopData`**：拆完的商店不再「买到空货」。
4. **`BuildingSavedData.register` 重叠检查跳过 `isDemolishing()`**：拆除中/已拆建筑不再挡新建筑放置。
5. **`TouristMoveGoal.performBuildingInteraction` 交互前重新校验**建筑存在/intact/非 demolishing，
   无效则 `finishBuildingStop()` 重规划——在途游客不会对幽灵建筑结算。
6. **`TouristSpawnSystem` 排程只存 buildingId**：`createSchedule`/`forceSpawn`/`flushPendingSpawns`
   在生成时实时查 + 校验，目标已拆则丢弃该次生成。

---

## Bug 2：游客卡在羊毛块之间 / 建筑原点，状态 VISITING 卡死

> **2026-08-01 修复勘误**：用户实测「游客完全包裹在交互区内，却 VISITING 不交互」，
> 根因**不是**寻路（2a 的 Y 外扩误判理论作废），而是 `planNextBuilding`（TouristMoveGoal.java:1097-1116）
> 计算 `touristInteractZones` 时**未按 `rotationSteps` 旋转**，而 `interactPoint` 与渲染橙框都已旋转
> （`BuildingSavedData.getTouristInteractPoint` :256 用 `rotateBoundary`）→ 旋转建筑下到达判定命中错位框，
> `arrived` 恒 false → 卡死兜底「传送到自己」形成不可见死循环。
> 修复：zone 按 `BuildingRotation.rotateBoundary(zone, rotationSteps)` 对齐 + 卡死强制回游荡（治标，`abandonBuildingVisit`）。

### 现象

- 两个商店附近，部分游客卡在「第 2、3 块羊毛之间下方」。
- 右键 UI：状态 = 前往建筑（VISITING），位置 = 建筑原点。
- 该位置被交互区覆盖。**拆掉一些方块仍卡住**。

### 根因 2a：到达判定用 Y 外扩的交互区，且全闭区间 → 楼层下方也算「到达」

`planNextBuilding`（TouristMoveGoal.java:1089-1105）构建到达判定区：
```java
int yMin = chosenAnchor.getY() + zone.min().y() - 2;  // 向下多扩 2 格
int yMax = chosenAnchor.getY() + zone.max().y() + 2;  // 向上多扩 2 格
zones.add(new BoundingBox(..., yMin, ..., yMax, ...));
```
而 `tickIndoorNav` 的到达判定（TouristMoveGoal.java:416-423）用的是 `zone.isInside(pos)`。
已核实 MC `BoundingBox.isInside(int x,y,z)` 是**全闭区间**（`x>=minX && x<=maxX && y>=minY && y<=maxY && z>=minZ && z<=maxZ`）。

→ 交互区本身（如柜台/地板层）在 `zone.min().y()` 之上，游客站在**地板下方 1–2 格**（如两层羊毛夹层、
建筑底部悬空位）时，`pos.getY()` 仍落在 `[anchorY+min-2, anchorY+max+2]` 内 → `arrived=true`。

### 根因 2b：交互改为「到达即立即生效」，误触发直接结算

提交 `5762302` 删除了「到达后站立 `interaction_duration_ticks` 再交互」的阶段，改为
`if (arrived) { performBuildingInteraction(); }`（TouristMoveGoal.java:429-439）。
于是游客只要被判定「在区内」就立即扣精力、涨满意度、计行程、设冷却，**哪怕它根本不在可交互的站位上**。

### 根因 2c：交互后进入「退出」阶段，但下方/夹层位置寻不到出路 → 卡死 + 兜底传送循环

交互结束后（TouristMoveGoal.java:441-454）：
```java
if (entryPoint != null && isInsideBuilding(buildingId)) {
    exitingPhase = true;
    nav.moveTo(entryPoint...);          // 被地板/墙体挡住 → 无路径
}
```
游客被夹在羊毛层之间、又处于建筑 bbox 内 → `isInsideBuilding` 为 true → 进入退出阶段但路径不通 →
`nav.isDone()` 恒 true → 每 tick 重试 → 约 20 秒后（`noMoveTicks > 100 || totalNavTicks > 400`，
TouristMoveGoal.java:352）硬传送。若兜底目标（entryPoint/interactPoint）仍是不可达的 AABB 派生点，
就会形成「传送到固定点 → 又卡 → 又传」的循环，且传送点是坐标快照、**与方块是否拆掉无关** →
「拆方块也卡住」。

### 根因 2d：首次访问的 commuteTarget 是「交互点」而非「入口点」

`TouristSpawnSystem` 生成时直接 `setCommuteTarget(interactionTarget)`（TouristSpawnSystem.java:287），
`startBuildingVisit` 也直接朝 `commuteTarget` 导航（TouristMoveGoal.java:238）。第一个目标就是建筑内部点，
跳过了「入口 → 进入」的正常流程，更容易从错误角度扎进下方/夹层区。

### 根因 2e：交互点本身可能不可达

`BuildingSavedData.getTouristInteractPoint`（:245-270）对交互区做自顶向下的螺旋扫描，取第一个「空气在上、
实心在下」的格。可能取到高台/屋顶/货架顶等**不可步行到达**的位置；一旦如此，游客永远到不了真正的
交互点，只能反复触发兜底传送。

### 修复方向（供后续）

1. 到达判定改用「交互点 + 小半径」，不要用整块 Y 外扩 AABB；或把 Y 下限钳到交互区地板层（去掉 `-2` 下扩），
   并对「玩家身位在地板以下」直接判未到达。
2. 交互前校验游客已站在可步行地面（`blockPosition().below()` 为实心）再结算。
3. 兜底传送目标先做可达性检查（至少确认目标下方有实心块、不在 bbox 内），避免传送到再次卡死的位置。
4. 首次访问也走「入口点 → 室内导航」的完整流程，不要直接把交互点当 commuteTarget。
5. 卡住检测阈值 `distSqr(lastPos) < 1.0`（见 Bug 4）对所有移动模式统一修正，减少误判。

---

## Bug 3：满意度 60 + 有 inn，黄昏仍不入住直接离开

### 现象

- 游客满意度 60（满足入住条件 ≥50 且 <100），有空 inn，但黄昏不入住，直接离开。
- 用户怀疑：白天普通逛过 inn 会干扰住宿。**该怀疑成立**——但机制不是冷却，而是 `visitedBuildings`。

### 根因 3a：白天普通逛 inn → inn 被计入 `visitedBuildings` → 晚上住宿路由跳过它

- 白天 inn 作为普通 service 建筑：`planNextBuilding` 的 `isNight` 分支为 false 时把 inn 放进
  `serviceTargets`（TouristMoveGoal.java:1012-1014）。游客逛完，`performBuildingInteraction` 末尾
  `addVisitedBuilding(buildingId)`（TouristMoveGoal.java:584）把 inn 写进 `visitedBuildings`。
- 夜晚住宿路由：
  - `planNextBuilding` 顶部 `if (tourist.hasVisitedBuilding(...)) continue;`（TouristMoveGoal.java:988）
    → inn 被排除出 `hotelTargets`。
  - 兜底路由 `tryRouteToHotel`（TouristSpawnSystem.java:556）也有
    `if (t.hasVisitedBuilding(b.getBuildingId())) continue;` → inn 被排除。

`visitedBuildings` 的语义是「本趟已逛过」，但对 inn 来说「白天普通逛过」**不该**阻止「晚上住宿」。
`tryRouteToHotel` 返回 false → `cleanupTourists` / `processNightDepartures` 把游客移除（离开）
（TouristSpawnSystem.java:377 / :441）。这就是「满意度够 + 有 inn 却不入住」的直接路径。

### 根因 3b（次要）：服务冷却也会在窗口内挡住 inn

白天逛 inn 时 `applyInteractionCooldown`（TouristMoveGoal.java:1131-1137）同时设置
- 单建筑冷却 `serviceCooldowns[inn]`；
- 全局冷却 `serviceCooldownEndTick`。

夜晚来临时若全局冷却仍在生效，`isInRestCooldown()` 会把 `decideNextMode` 钉在 WANDER/POI
（TouristMoveGoal.java:856-860），且 `planNextBuilding` 直接 `continue` 所有 service
（TouristMoveGoal.java:990、:1002）。不过它只是短期窗口（时长为 `interaction_duration_ticks`），
相比 3a 的 `visitedBuildings`（整趟有效）是次要因素。

### 修复方向（供后续）

1. 把「酒店住宿」从 `visitedBuildings` 的排除中豁免：
   - `tryRouteToHotel` 对 hotel 建筑不判 `hasVisitedBuilding`；
   - `planNextBuilding` 的夜晚 hotel 分支同样豁免（白天普通逛过 ≠ 晚上不能住）。
2. 更干净的方案：白天「普通 service 交互」不要把 hotel 写进 `visitedBuildings`，或单独用
   `serviceCooldowns` 语义表达「刚逛过」而 `visitedBuildings` 只表达「行程已覆盖」。
3. 夜晚住宿路由时清除/忽略 inn 的全局服务冷却，保证 13000 起游客能立即被路由入住。
4. 校验方向：确认 `processNightDepartures` 在 13000-18000 的 `cleanupTourists` 夜晚分支
   （TouristSpawnSystem.java:374-377）与 18000 后的 `processNightDepartures`（:437-442）都经过
   `tryRouteToHotel`，且此函数不被 visited/cooldown 卡死。

---

## Bug 4：游荡不合理——目标易走却频繁传送、长时间停留、范围小

### 现象

- 游荡时游客频繁「传送」，哪怕目标点很容易走到。
- 经常在同一个方块上停留很久、移动量很小、活动范围非常有限，看起来像卡住。

### 根因 4a：卡住阈值 `distSqr(lastPos) < 1.0` 过粗 → 正常绕路也被判「没动」→ 传送

`tickWander`（TouristMoveGoal.java:778-793）：
```java
if (lastPos != null && pos.distSqr(lastPos) < 1.0) {
    noMoveTicks++;
} else {
    noMoveTicks = 0;
    lastPos = pos;
}
if (!nav.isDone() && noMoveTicks > WANDER_STUCK_TICKS) {   // 120 tick
    // 传送到 anchor 地面
}
```
`lastPos` 只在净位移 ≥1 格时才更新。游客绕墙角、贴障碍抖动、被挤在狭窄缝隙时，**数秒内净位移都不足 1 格**，
`noMoveTicks` 攒到 120（6 秒）就触发传送到 anchor 地面——尽管目标近在咫尺、路完全能走。这是「目标易走却传送」的主因。

### 根因 4b：游荡目标点可能落在屋顶/结构内，不可达

`tickWander` 随机取 `tx,tz` 后（TouristMoveGoal.java:808-811）：
```java
BlockPos g = findGround(tx, anchor.getY(), tz);   // 从 anchorY+5 向下扫，取第一个「非空+上方空」
nav.moveTo(g.getX()+0.5, g.getY(), g.getZ()+0.5, wanderSpeed);
```
`findGround` 不看可达性。目标格落在建筑屋顶/墙上时，会取到屋顶/夹层 → 导航无路径 →
`nav.isDone()` 恒 true → 站着等下一个冷却周期。若屋顶满足 `isStandingOnFloatingSurface`
（脚踩浮空实心、脚下再下是空气），`tickRoofRescue`（:1445-1467）会在 80 tick 后把游客「传送下去」——
这就是另一类「传送」。

### 根因 4c：游荡步进间隔 60–180 tick + nav 完成后不换目标 → 长时间发呆

`wanderCooldown = 60 + rand(120)`（TouristMoveGoal.java:813）最长 9 秒才挑下一个随机点。
且 `tickWander` 在 `nav.isDone()` 后**不会立刻换目标**，只会等到冷却到期（或 300–500 tick 的模式重评估）。
于是「到达 → 呆站数秒 → 再随机一步」形成「长时间停留、移动量很小」的表象。

### 根因 4d：游荡锚点固定 + 半径 8 → 范围有限

`wanderAnchor` 在进入 WANDERING 时固定为当前位置、`wanderRadius=8`（TouristMoveGoal.java:764-770）。
`tickWander` 用 `manDist > radius + 3` 拉回锚点（:795-800），随机步也全部落在锚点 ±8 内 → 活动范围被钉死在一个小方块里，表现为「移动范围很有限」。

### 根因 4e：游荡走原版导航，不参与道路规划

`tickWander` 直接 `nav.moveTo`，未走 `RoadWalkPlanner` 的粗粒度路网 + 分段采样（宏导航才用）。
崎岖地形/复杂建筑环境里原版 A*（FOLLOW_RANGE 64 → 节点预算 ~1024）容易失败或绕远，叠加 4a 放大传送。

### 修复方向（供后续）

1. 卡住检测改为「位移速率」而非「净位移是否 ≥1 格」，或用连续多 tick 的平均速度判卡住；调低误判率。
2. 游荡目标点生成后做可达性检查：目标不可达时降级为「朝最近的可达地面格走」或换点。
3. `findGround` 改为从目标 XZ 的当前地表高度向下找（不要从 anchorY+5 盲扫），并排除屋顶（`below(2).isAir` 的情况）。
4. 游荡锚点允许随游客当前位置漂移（如每步小范围偏移），不要钉死在一个点；半径可随模式扩大。
5. 缩短发呆：`nav.isDone()` 且未到达目标时立即重挑一个目标或补一次导航，不要等到冷却到期。

---

## 级联关系

```
(5762302 交互改为「进入交互区立即生效」)
   ↓
交互区 Y±2 外扩 + BoundingBox 全闭区间          [Bug 2a]
   → 楼层下方/夹层位置误判「到达」
   → 立即结算交互 + 进入退出阶段 → 无路径       [Bug 2b/2c]
   → 兜底传送(固定坐标快照) → 循环卡死           [Bug 2e / Bug 4a]

建筑 Destroy → demolishBuilding(只标 demolishing) [Bug 1a]
   → 游客筛选不认 demolishing                    [Bug 1a]
   → 异步拆解中断 → 幽灵建筑(重载后 demolishing 归 false) [Bug 1b]
   → 交互不校验 + 交互区坐标快照 + 当日排程持引用  [Bug 1c/1d/1e]

白天逛 inn → addVisitedBuilding(inn)            [Bug 3a]
   → 夜晚 planNextBuilding / tryRouteToHotel 跳过 inn → 游客离开
```
