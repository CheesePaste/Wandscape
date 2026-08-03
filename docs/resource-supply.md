# 资源供需规则：何时自动采集、何时自动合成

> 目标系统设计（2026-07-31）。本文描述**目标行为**——"有需求才采集"。当前代码的偏差与修复步骤见 `docs/decisions.md`「资源供需重构」一节。

## 总览

殖民地有两条自动补货通道，**只在存在真实需求时触发**：

| 通道 | 资源类型 | 触发时机 | 谁来入队 |
|---|---|---|---|
| **自动采集** | 元素（earth/wood/…） | 维护储备不足 / 生产缺元素 | `MaintenanceForecastSystem` / `ResourceSupplySystem` |
| **自动合成** | 物品（stone_bricks/…） | 建造/补货请求物品不足 | `ResourceShortageHandler` / `ResourceSupplySystem` |

无需求则不采集、不合成。NPC 空闲时不会因为"闲着"而被塞入采集任务。

---

## 一、什么时候自动采集（gather）

采集只生产**元素**。有两个独立需求源，任一满足即可触发：

### 1. 维护储备需求（防止建筑停机）

- **触发条件**：殖民地某元素储备 `< 每日维护费合计 × reserveDays`
- **默认参数**：`reserveDays = 2`（`config/maintenance.reserveDays`），即维持"够撑 2 天维护"的底仓
- **谁来入队**：`MaintenanceForecastSystem`，每 `forecastIntervalTicks`（默认 6000 tick = 1/4 MC 天）扫描一次
- **入队规则**：找出能产出该短缺元素的**空闲** node 建筑（无排队任务、未占用、完整未停机），入队 `node:gather`（优先级 49）
- **何时停止**：储备回到 `维护费×reserveDays` 以上，下次扫描不再入队

> 维护费由 `DailySettlementSystem` 每日结算扣费（`building/internal/DailySettlementSystem.java`）。Forecast 提前备货，避免建筑因欠费自动停机。

### 2. 生产消耗需求（元素不足时现采现用）

- **触发条件**：Workstation / Crafting_Station / 建筑建造等**消耗元素**的操作发现仓库该元素不足
- **机制**：消耗操作抛 `ResourceShortageException` → 任务转 AWAITING_RESOURCES → `ResourceSupplySystem.tryGatherElement` 找产该元素的空闲 node 入队采集（优先级 40）
- **与合成的优先级**：先试合成，无合成配方（元素没有合成配方）才走采集（见下文"合成/采集决策顺序"）
- **何时停止**：元素补足后，挂起任务被唤醒继续执行

### 3. 玩家手动发布（非自动）

- 右键 node 建筑打开 NodeScreen，拖动"收获次数"滑条 → 发布采集任务。这是唯一不受需求约束的采集入口，由玩家显式决策。

---

## 二、什么时候自动合成（synthesize）

自动合成只处理**物品**（有 `production:synthesize` 配方的资源，如 stone_bricks）。合成站类别为 **`workstation`**（玩家 WorkstationScreen 所在的建筑，2026-08-03 修正——原代码误找 `crafting_station` 法宝合成站）。

- **触发条件**：某任务/补货请求物品时仓库不足，且该物品存在合成配方
  - 建造任务：蓝图 `request_resource` op → `ResourceRequestExecutor` 发现仓库物品不足 → 任务挂起 → 入队合成
  - 商店补货：`ShopStockManager.restock` 发现仓库缺货 → 直接调 `ResourceSupplySystem.enqueueSynthesize` 入队合成，并加入 `pendingRestock` 每 ~100 tick 重试；物品入仓后自动补齐店铺并退出重试集
  - 生产级联：synthesize 自身缺元素 → 抛异常 → 进入采集流程
- **谁来入队**：
  1. 任务挂起时 `ResourceShortageHandler`（`EngineBootstrap.createShortageHandler`）立即尝试入队合成（优先级 40）
  2. `ResourceSupplySystem` 每 40 tick 兜底重试（若当时无空闲合成站）
  3. 商店补货直接调用共享的 `ResourceSupplySystem.enqueueSynthesize`
- **防重复**：同一配方的合成任务已在队中（`isSynthesizeInFlight`）则不再重复入队
- **何时停止**：物品补足后，挂起任务被唤醒继续；商店物品入仓后 `pendingRestock` 重试把货补齐并退出

> 合成需要的元素由合成站操作内部消耗；元素不足时会自动级联到采集流程（见下）。

---

## 三、合成 vs 采集：决策顺序

当任务因资源不足挂起时，`ResourceSupplySystem` 每 40 tick 扫描 AWAITING_RESOURCES 任务，按此顺序尝试补货：

```
资源不足
 ├─ 该资源有合成配方 & 有空闲合成站？ → 入队 production:synthesize（元素→物品）
 └─ 否则该资源是元素？              → 入队 node:gather（采集元素）
     └─ 都不是 → 该资源无供应渠道，等待玩家手动补充
```

**关键点**：
- **物品**优先/只能走**合成**（元素无法直接变成 stone_bricks）
- **元素**只能走**采集**（元素没有合成配方）
- 合成过程中缺元素 → synthesize 操作抛异常 → 挂起 → 级联到采集，形成 **合成→采集** 三级链
- 无供应渠道的资源（无配方、非元素）不自动生成任务，任务保持挂起，等玩家手动放入仓库

---

## 四、补足后如何继续任务（恢复链）

无论合成还是采集，产物入库后都会唤醒挂起的任务：

```
合成/采集完成
 → resources.addResource()  写入仓库（ColonyItemBank）
 → onResourceAdded 事件
 → GlobalTaskPool 检查所有 AWAITING_RESOURCES 任务
    └─ 该任务所有需求资源现在都足够？ → 转回 PENDING_ASSIGN → 重新分配给 NPC
         └─ NPC 从挂起时保存的 stepIndex 继续执行（不重头开始）
```

---

## 五、不会发生的情况（防御）

- **无条件采集**：目标系统中不存在"每个空闲 node 永远排一个采集任务"的行为（`supplyNodeBuildings` 已删除）
- **无解的维护短缺**：某元素没有任何 node 能产出时，只记日志警告并冷却，不反复入队/反复报警
- **重复合成**：同一配方在队中时不重复入队
- **任务无限等待**：只要资源最终入库，挂起任务一定被唤醒；完全无供应渠道的资源则保持挂起并可由玩家在 TaskQueuePanel 手动取消

---

## 六、需求来源对比（谁消耗了什么）

| 消耗方 | 消耗内容 | 触发通道 |
|---|---|---|
| 建筑每日维护（DailySettlementSystem） | 元素 | 采集（维护储备需求） |
| 合成站 synthesize | 元素 | 采集（生产消耗需求） |
| 工作站 decompose | 物品→元素（产出元素，不消耗元素） | 不触发采集 |
| 建造 request_resource | 物品 | 合成（若该物品有配方） |
| 商店补货 | 物品 | 合成（若该物品有配方）；缺货直接入队 + `pendingRestock` 重试 |
| 法杖制作 craft_wand | 元素 | 采集（生产消耗需求） |
