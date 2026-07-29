# stats/ — 统计系统

采集殖民地运营数据，生成日快照和 30 天滚动摘要，推送至面板显示。

## 数据流

```
DailySettlementEvent → StatisticsCollector
  → 构建 ColonyDailySnapshot → StatisticsData.append()
  → 面板打开 → computeSummary() → StatsSyncPacket → 客户端面板

TouristArrivedEvent / TouristDepartedEvent
  → StatisticsCollector 日内计数器递增 → 结算时写入快照

ColonyEvaluationChangedEvent
  → StatisticsCollector 记录
  → colonyStatsSyncPacket 独立推送评估值（更细粒度）
```

## 关系

`ColonyMetricsService`（engine/service/）与 stats/ 互补：stats/ 聚焦历史日快照和滚动摘要，ColonyMetricsService 聚焦实时全量指标聚合，两者不互相调用。

## 依赖

- shared/event/DailySettlementEvent/TouristArrivedEvent/TouristDepartedEvent/ColonyEvaluationChangedEvent
- shared/network/ColonyStatsSyncPacket
- shared/registry/WandscapeApis
