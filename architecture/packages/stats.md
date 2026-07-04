# stats/ — 统计系统

采集殖民地运营数据（游客流量/元素消耗/建筑状态），生成日快照和 30 天滚动摘要，推送至面板显示。

## 数据 (data/)

- **ColonyDailySnapshot** (record) — 单个殖民地在一个结算边界处的不可变日快照（维护活动/游客流量/三值评估值）。NBT 序列化
- **ColonyStatsSummary** (record) — 30 天滚动窗口预计算聚合摘要（快照计数/元素消耗总量/建筑统计/游客统计/EMPTY 哨兵）

## 内部实现 (internal/)

- **StatisticsCollector** — 事件驱动采集器，订阅 DailySettlementEvent/TouristArrivedEvent/TouristDepartedEvent/ColonyEvaluationChangedEvent。维护日内计数器，结算时记录 ColonyDailySnapshot，面板打开时推送摘要
- **StatisticsData** — 继承 SavedData，持久化存储。每个殖民地维护 30 天滚动窗口（最新优先），NBT 序列化/反序列化。提供 computeSummary() 聚合

## 网络包 (network/)

- **StatsSyncPacket** (record, CustomPacketPayload) — S→C 推送 ColonyStatsSummary 到客户端面板（RegistryFriendlyByteBuf 编解码）

## 引擎集成

- **engine/system/StatsSystem** — 引擎级事件消费者（骨架），订阅 NarrativeEventTriggered，统计逻辑待实现

## 数据流

```
DailySettlementEvent → StatisticsCollector
  → 构建 ColonyDailySnapshot → StatisticsData.append()
  → 面板打开 → computeSummary() → StatsSyncPacket → 客户端 WandscapePanelState

TouristArrivedEvent / TouristDepartedEvent
  → StatisticsCollector 日内计数器递增
  → 结算时写入快照

ColonyEvaluationChangedEvent
  → StatisticsCollector 记录
  → colonyStatsSyncPacket 独立推送评估值（更细粒度）
```

## 依赖

- shared/event/DailySettlementEvent/TouristArrivedEvent/TouristDepartedEvent/ColonyEvaluationChangedEvent
- shared/network/ColonyStatsSyncPacket
- shared/registry/WandscapeApis
