# 仓库系统设计决策

文档编号：NEW-26
版本：2.0
状态：grill-me 决策记录（已更新为 SavedData 架构）
日期：2026-06-21
依赖：docs/04-warehouse-system.md、docs/27-multiblock-refactor-analysis.md

---

## 决策汇总

| # | 决策点 | 选定方案 |
|---|--------|---------|
| Q1 | 仓库形态 | **纯 SavedData 建筑** — BuildingSavedData 注册 (category=storage) + 原版方块 pattern + BuildingInteractHandler 右键开 GUI。零自定义方块/BE |
| Q2 | 预留机制 | **三步预留保留** — reserve/commit/release |
| Q3 | 数据持有者 | **ColonyItemBank (Level SavedData)** — 物品数据独立于方块，方块破坏不丢失 |
| Q4 | 存储类型 | **ItemKey → Long 物品存储** — 不用 ElementType，元素通过 ElementMapping 从物品推导 |
| Q5 | 任务队列 | **不需要队列** — queue.capacity=0 |
| Q6 | 持久化粒度 | **全量 NBT** — 脏标记仅判断"是否保存"，写入时全量 |
| Q7 | 查找方式 | **BuildingSavedData.posIndex O(1)** — BuildingInteractHandler 统一拦截右键 |
| Q8 | 方块实现 | **纯原版方块** — anchor 使用 minecraft:barrel，其余 stone_bricks/oak_log/oak_planks。无自定义方块 |
| Q9 | 预留追踪 | **简单计数法** — reserved 加减，no holder，no timeout |
| Q10 | category | **`storage`** — BuildingConfig 反序列化器已有 default→basic 兜底 |
| Q11 | GUI 渲染 | **自定义 GuiGraphics** — ContainerMenu 只放玩家背包，物品列表自己画 |
| Q12 | 网络同步 | **打开时一次性全量** — 不推送增量 |
| Q13 | MVP 范围 | **先做物品** — 元素由 ElementMappingLoader 从物品推导，不在仓库存元素 |
| Q14 | ItemKey NBT | **完整 ItemKey** — MVP 不特殊处理耐久/附魔，自然作为不同条目 |
| Q15 | 实现分层 | **一个类实现两个接口** — WarehouseManager implements WarehouseApi + ColonyResourceAccess |
| Q16 | 建筑结构 | **3×3×3 敞开正面** — 石砖基座 + 橡木柱 + 橡木板屋顶，anchor=barrel 正面无墙 |
| Q17 | 游戏事件 | **NeoForge EVENT_BUS** — `ResourceInsufficientEvent` 在 hasEnough() 返回 false 时 post，10s 冷却 |
| Q18 | 事件通知 | **聊天栏消息** — `WarehouseNotificationHandler` 订阅事件 → `sendSystemMessage` |

---

## 一、架构变更 (v1.1 → v2.0)

旧架构（v1.1）：自定义方块 WarehouseBlock + WarehouseBE (AbstractWandscapeBE) — BE 持有物品数据 + NBT。

新架构（v2.0）：仓库是 BuildingSavedData 中的普通建筑（category=storage），物品数据在 ColonyItemBank (Level SavedData)：

```
BuildingSavedData (Level SavedData)
  ├── posIndex: BlockPos → buildingId            ← O(1) 右键查找
  └── BuildingState (category=storage)            ← colonyId / shutdown / structureIntact

BuildingInteractHandler.RightClickBlock
  → posIndex.get(pos) → buildingId → BuildingState
  → category=storage → colonyId → ColonyItemBank.getSnapshot() → WarehouseMenu GUI

WarehouseManager (implements WarehouseApi + ColonyResourceAccess)
  └── 读写 ColonyItemBank（非 BE）
```

**关键变化**：
- Q1/Q8：不再有自定义方块/BE → 纯原版方块 + BuildingSavedData
- Q3：数据从 BE 移到 ColonyItemBank (SavedData) — 方块破坏不丢数据
- Q7：查找从 BuildingApi → getBeAt() 改为 BuildingSavedData.posIndex O(1)

## 二、文件清单 (v2.0)

| 操作 | 文件 | 内容 |
|------|------|------|
| 新增 | `warehouse/ColonyItemBank.java` | Level SavedData：Map<UUID, Map<ItemKey, Long>> + reserve/commit/release + NBT |
| 新增 | `warehouse/WarehouseManager.java` | 同时实现 WarehouseApi + ColonyResourceAccess |
| 新增 | `warehouse/WarehouseMenu.java` | ContainerMenu（玩家背包 slot） |
| 新增 | `warehouse/client/WarehouseScreen.java` | GUI：物品网格 + 数量滑条 + 元素面板 |
| 新增 | `warehouse/WarehouseNotificationHandler.java` | 订阅 ResourceInsufficientEvent → 聊天栏 |
| 新增 | `warehouse/network/WarehouseDataPacket.java` | 客户端-服务端物品数据同步 |
| 新增 | `data/wandscape/buildings/warehouse.json` | category=storage, pattern 全原版方块, anchor=barrel |
| 修改 | `building/internal/BuildingInteractHandler.java` | category=storage → 直接开 WarehouseMenu |
| 修改 | `Wandscape.java` | WAREHOUSE_MENU 注册 + WarehouseManager 注入 |
| 删除 | `warehouse/WarehouseBE.java` | 物品存储→ColonyItemBank，GUI→BuildingInteractHandler |
| 删除 | `warehouse/WarehouseBlock.java` | 不再需要自定义方块 |

## 三、不做的

- 虚拟滚动 — 用普通渲染，后期优化
- 多仓库支持 — 单殖民地单仓库
- reserved 超时清理
- 物品 NBT 渲染（先只用 itemId 显示图标）
- 搜索过滤
- 存入物品（玩家交互）— 先只读展示
- 维护结算扣物品 — 先空着，15 模块时补
