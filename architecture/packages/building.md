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
| shop | 商店（游客购物） |
| service | 服务建筑（游客交互；`max_occupancy>0`=旅店） |
| relax | 歇脚建筑（游客回复精力） |
| atm | 取款建筑（游客取现补钱包） |
| decoration | 装饰建筑（范围辐射加成） |
| wonder | 奇观（全局效果） |
| custom | 自定义建筑（游客不可交互、三值恒0） |

> 游客四类目标 = `shop`/`service`/`relax`/`atm`（各带模式预设块）。`interact_spots` 标记交互位（spot 数量 = 同时交互人数上限）；无 spots 的游客目标建筑对游客无效（无兜底）。

## 关键设计要点

- **三值评估**：BuildingContributionRegistry 改为每建筑实例独立计算。shop 三值 = 建筑基础值 + 所有有货 goods 的 comfort/magic/wonder 合计。变化广播 `ColonyEvaluationChangedEvent`
- **BuildingUnlockChecker**：静态工具，查询殖民地等级 vs unlockRequirement.minColonyLevel（2026-07-29 三值门槛改为殖民地等级门槛）
- **修复系统**：`BuildingBreakHandler.triggerRepair()` 通过 `BuildingActionPacket("repair")` 手动触发修复扫描，计入 repair material_list/counts 供蓝图调配仓库资源。shutdown 建筑可排队 repair 任务恢复

### 建筑扫描器（两种）

- **创造建筑扫描器**（`creative_building_scanner`，原名 `building_scanner` 改名而来）：完整创作者工具，Type 可选全部类别（含 `custom`），支持 SAVE/CORNER 配对、四类游客模式预设编辑（shop/service/relax/atm）、三值/节点配置、预设、ROAD 导出。**交互位唯一真源 = world 里的 `interact_spot_marker` 方块**（放置=标记 spot、右键循环动作、潜行右键移除，action 存 blockstate），BE 不存 spot 列表；导出扫 boundary 内 marker → `interact_spots`（marker 格跳过 pattern，创作者自行留空）。
- **建筑扫描器**（`building_scanner`）：简化版，专供生存玩家复制自己的建筑供 NPC 重建。类别锁定 `custom`（不可修改，导出无交互区，三值恒0），GUI 仅尺寸/门偏移/ID/Name/导出 + ROAD 模式。方块可合成（金锭×4 + 紫水晶碎片×4 + 工作台）。
- 两者共用 `ScannerExportPacket`（导出到 datapack 并热注册）、`ScannerSyncPacket` 与 `ScannerValuePacket`（服务端算 boundary 内元素价值并打到聊天区）；渲染共用 `ScannerRenderer`。

### 模拟经营系统

- **DailySettlementSystem** — 纯每日结算触发器：每游戏日发 `DailySettlementEvent`，触发商店补货（ShopStockManager）与统计快照（StatisticsCollector），不再扣维护费
- **DemolishCompleteListener** — 清理拆除建筑的 SavedData 状态
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

每日结算
  → DailySettlementSystem 每游戏日发 DailySettlementEvent
  → ShopStockManager 商店补货 + StatisticsCollector 统计快照 订阅

商店运作
  → ShopStockManager.restock() → ColonyItemBank扣元素 → 填充goodSlots
  → 游客交互 → 消耗货品 → ColonyItemBank入元素(利润)
  → 有货/缺货 → 三值贡献开关

装饰辐射
  → DecorationBonusSystem → 曼哈顿距离内装饰累加 + cap → 缓存 → 计入三值
```

## JSON

位置：`data/wandscape/buildings/*.json`（含 `deprecated/`）。格式参见 [data/buildings.md](../data/buildings.md)：四类游客模式预设块（`shop{}`/`service{}`/`relax{}`/`atm{}`）+ `interact_spots`（相对 anchor 交互位，每点带动作）；旧 `tourist_interact_aabb` 顶层字段不再解析。

## 依赖

- shared/api/BuildingApi, shared/data/BuildingData, shared/data/WorkItem
- shared/data/DecorationConfig, shared/data/WonderConfig, shared/data/WonderEffect
- shared/data/ShopConfig, shared/data/ServiceConfig, shared/data/RelaxConfig, shared/data/AtmConfig, shared/data/Activity
- shared/event/BuildingPlacedEvent/BuildingShutdownEvent/BuildingRestartedEvent/ColonyEvaluationChangedEvent
- shared/event/ShopRestockedEvent/WonderEffectChangedEvent
- shared/registry/WandscapeApis
- warehouse/ColonyItemBank
- dataconfig/WandscapeDataLoader
- core/event/CustomEvent
