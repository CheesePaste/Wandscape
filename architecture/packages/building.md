# building/ — 建筑管理

零自定义方块/BE。建筑状态全部通过 `BuildingSavedData` (Level SavedData) 管理。所有建筑使用原版方块，NPC 通过蓝图放置。

## 建筑类别 (category)

| 类别 | 说明 |
|------|------|
| basic | 基础建筑（市政厅等） |
| node | 节点建筑（元素采集） |
| storage | 仓库建筑 |
| workstation | 工作站（分解/合成） |
| crafting_station | 制作站（法杖制作） |
| potion_station | 魔药站 |
| tavern | 酒馆（招募） |
| shop | 商店（游客购物，带交互区） |
| service | 服务建筑（游客交互，需进入建筑） |
| decoration | 装饰建筑（范围辐射加成） |
| wonder | 奇观（全局效果） |

## 关键设计要点

- **三值评估**：BuildingContributionRegistry 改为每建筑实例独立计算。shop 三值 = 建筑基础值 + 所有有货 goods 的 comfort/magic/wonder 合计。变化广播 `ColonyEvaluationChangedEvent`
- **BuildingUnlockChecker**：静态工具，查询殖民地等级 vs unlockRequirement.minColonyLevel（2026-07-29 三值门槛改为殖民地等级门槛）
- **修复系统**：`BuildingBreakHandler.triggerRepair()` 通过 `BuildingActionPacket("repair")` 手动触发修复扫描，计入 repair material_list/counts 供蓝图调配仓库资源。shutdown 建筑可排队 repair 任务恢复

### 模拟经营系统

- **DailySettlementSystem** — 每游戏日 0:00 按优先级（CRITICAL→HIGH→NORMAL→LOW）扣维护费。不足 shutdown，宽限期跳过，剩余元素自动重启。发布 `DailySettlementEvent`
- **DemolishCompleteListener** — 清理拆除建筑的 SavedData 状态
- **MaintenanceForecastSystem** — 每 6000tick 扫描，存量低于阈值时触发闲置 node 高优先级采集
- **DecorationBonusSystem** — 曼哈顿距离内装饰加成累加 + cap → 缓存 → 计入三值
- **ShopStockManager** — per-building 库存 + maxStock 管理，心跳 restock，purchase 消费，clearUnsold。stock 控制三值开关
- **WonderEffectApplier** — 统计生效奇观效果（StatMod/PriceMod/RuleUnlock）

## 数据流

```
玩家/GUI 提交建造
  → EnqueueHelper → BuildingSavedData(structureIntact=false)
  → WorkItem → BuildingTaskSource.poll() → BuildingTaskPool (仅head入全局池)
  → GlobalTaskPool → SchedulerSystem(NPC匹配) → NpcTaskPackage
  → NPC领取 → 执行蓝图 → 放置原版方块
  → emit_event("build_complete")
  → BuildCompleteListener → findDamagedBlocks 扫描
  → 全部修复 → structureIntact=true → 加入三值贡献 → 广播 ColonyEvaluationChangedEvent

建筑受损（Break/Explosion）
  → BuildingBreakHandler → structureIntact=false → 移除三值贡献
  → 广播 ColonyEvaluationChangedEvent
  → 构造局部 WorkItem（含 material_list/counts 凭据）→ addFirst 队首
  → NPC修复 → BuildCompleteListener 扫描 → 恢复

手动修复（AnomalyScreen → BuildingActionPacket("repair")）
  → BuildingBreakHandler.triggerRepair() → 扫描损毁方块 → enqueueRepairForOffsets
  → 同上修复流程

shutdown 建筑例外：hasWork() 允许队首 repair 任务通过，pollWork() 不再直接跳过 shutdown 建筑

维护费循环
  → DailySettlementSystem 每游戏日0:00
  → 按优先级：CRITICAL(node/basic/storage) → HIGH(production) → NORMAL(shop/tavern) → LOW(service/decoration)
  → ColonyItemBank.consumeElements() → 不足则shutdown → 按category分级惩罚
  → 剩余元素 → 自动重启已 shutdown 建筑

商店运作
  → ShopStockManager.restock() → ColonyItemBank扣元素 → 填充goodSlots
  → 游客交互 → 消耗货品 → ColonyItemBank入元素(利润)
  → 有货/缺货 → 三值贡献开关

装饰辐射
  → DecorationBonusSystem → 曼哈顿距离内装饰累加 + cap → 缓存 → 计入三值
```

## JSON

位置：`data/wandscape/buildings/*.json`，8 个现有建筑。格式参见 [data/buildings.md](../data/buildings.md)。

## 依赖

- shared/api/BuildingApi, shared/data/BuildingData, shared/data/WorkItem
- shared/data/MaintenanceCost, shared/data/DecorationConfig, shared/data/WonderConfig, shared/data/WonderEffect, shared/data/ShopConfig, shared/data/ServiceConfig
- shared/event/BuildingPlacedEvent/BuildingShutdownEvent/BuildingRestartedEvent/ColonyEvaluationChangedEvent
- shared/event/MaintenanceDueEvent/ShopRestockedEvent/WonderEffectChangedEvent
- shared/registry/WandscapeApis
- warehouse/ColonyItemBank
- dataconfig/WandscapeDataLoader
- core/event/CustomEvent
