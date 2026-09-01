# GuideProgress 系统内核错置在 items（待处理，需拍板）

> 记录：2026-09-01 包扫描发现。**非小优化，需拍板归属后再搬**，先记录备用。
> 判定依据：`items` 域定位 = **纯薄物品容器（无系统内核）**；而这是完整**跨域新手引导系统内核**。

## 现状（全在 `content/items/`）

- `items/service/GuideProgressService` —— 服务端核：10 步进度计算，聚合 `colony`/`building`/`warehouse`/`tourist`/`production` 五域状态
- `items/service/GuideServerContext`
- `items/data/GuideProgressSavedData`
- `items/network/GuideProgressSyncPacket`、`GuideProgressUpdatePacket`

客户端引导步骤表在 `foundation/ui/guidance/GuideRegistry`、`GuideStep`（引 `GuideProgressService.computeStep` 对齐顺序）——**服务端核在 items，客户端表在 foundation/ui/guidance，特性被割裂两地**。

## 为何算错置

违反"items=纯物品容器（无系统内核）"——它不是薄物品，而是一套带 SavedData + 进度计算 + 网包的独立系统。

## 消费面（全模组，经 `WandscapeApis.getGuideApi`）

`ColonyCreateRequestPacket`、`BuildingInteractHandler`、`ProjectionPlacePacket`、`RoadSegmentListener`、`WarehouseMenu`、`PanelStateTogglePacket`、`RequestGatherTaskPacket` 等。

## 可选去路（拍板三选一）

1. **独立功能域 `content/guide`**（最干净，新增一个域）
2. **整体收 `foundation/ui/guidance`**（与客户端引导表同址；但服务端核含 SavedData + 系统逻辑，放 foundation 需注意"foundation 不反向认识域"红线——若引导要聚合五域状态，它天然认识域，可能仍该独立成域）
3. **保留 `items` 但明确扩展 items 域为"物品 + 引导系统"**（力度最小，但淡化 items 定位；类似 scepter 例外）

## 动工前

- 与"核心恒持"（10 步进度如何被五域推进）对齐，确认进度的**真实归属语义**——它是"玩家在新手期的全局状态机"，不是任一域的私有状态。
- 参考 §『拆分铁律』：跨域聚合状态（认多域）按其语义可独立成域，塞进某域或收 foundation 都不完全贴。
