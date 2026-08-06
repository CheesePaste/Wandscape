# stats/ — 统计模块

`src/main/java/com/wsteam/wandscape/stats/`

## 职责

殖民地运营统计：数据采集 → 每日快照 → 30 天滚动摘要 → 同步到客户端面板。

## StatisticsCollector

订阅 DailySettlementEvent / TouristArrivedEvent / TouristDepartedEvent / ColonyEvaluationChangedEvent。维护当日计数（touristsArrived/Departed/totalSatisfaction）与 comfort/magic/wonder。结算时聚合 `ColonyDailySnapshot` 写入 StatisticsData 并复位计数器；评估变化时 piggyback 推送。`pushStatsToPlayers` 只发给该殖民地面板开启（PanelStateTracker）玩家。

## StatisticsData（SavedData）

key `wandscape_statistics`；`MAX_SNAPSHOTS=30` 滚动窗口，新快照 addFirst/删末尾。`computeSummary` 汇总快照：累加 buildings/tourists/satisfaction、取最新 comfort/magic/wonder、avgSatisfaction=总满意/离店数、聚合 elementsConsumed。

## 数据类

- `ColonyDailySnapshot`：不可变 record（day/elementsConsumed/buildingsPaid/Shutdown/Restarted/touristsArrived/Departed/totalSatisfaction/comfort/magic/wonder），NBT 存取。
- `ColonyStatsSummary`：聚合结果 record（+avgSatisfaction/totalElementsConsumed/snapshotCount），含 EMPTY 常量。
- `StatsSyncPacket`（S→C）：推 summary，handleClient 写入 `WandscapePanelState.StatsSummary`。
