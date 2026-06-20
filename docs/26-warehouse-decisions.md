# 仓库系统设计决策

文档编号：NEW-26
版本：1.1
状态：grill-me 决策记录
日期：2026-06-20
依赖：docs/04-warehouse-system.md（设计文档）、core/boundary/ColonyResourceAccess.java、shared/api/WarehouseApi.java

---

## 决策汇总

| # | 决策点 | 选定方案 |
|---|--------|---------|
| Q1 | 仓库形态 | **功能性建筑方块** — warehouse 方块+BE(AbstractWandscapeBE)，放置→右键 GUI |
| Q2 | 预留机制 | **三步预留保留** — reserve/commit/release |
| Q3 | 数据持有者 | **BE 内部持有数据** — 不走 ECS 组件 |
| Q4 | 存储类型 | **ItemKey → Long 物品存储** — 不用 ElementType，元素通过 ElementMapping 从物品推导 |
| Q5 | 任务队列 | **不需要队列** — queue.capacity=0 |
| Q6 | 持久化粒度 | **全量 NBT** — 脏标记仅判断"是否保存"，写入时全量 |
| Q7 | 查找方式 | **通过 BuildingApi** — 过滤 category=storage 找到 WarehouseBE |
| Q8 | 方块实现 | **复用 WandscapeBuildingBlock** — BE 覆写 onActivate() 开 GUI |
| Q9 | 预留追踪 | **简单计数法** — reserved 加减，no holder，no timeout |
| Q11 | GUI 渲染 | **自定义 GuiGraphics** — ContainerMenu 只放玩家背包，物品列表自己画 |
| Q12 | 网络同步 | **打开时一次性全量** — 不推送增量 |
| Q10 | category | **新增 `storage`** — BuildingConfig 反序列化器已有 default→basic 兜底 |
| Q13 | MVP 范围 | **先做物品** — 元素由 ElementMappingLoader 从物品推导，不在仓库存元素 |
| Q14 | ItemKey NBT | **完整 ItemKey** — MVP 不特殊处理耐久/附魔，自然作为不同条目 |
| Q15 | 实现分层 | **一个类实现两个接口** — WarehouseManager implements WarehouseApi + ColonyResourceAccess，复用 ElementMappingLoader |
| Q16 | 建筑结构 | **3×3×3 敞开正面** — 石砖基座 + 橡木柱 + 橡木板屋顶，正面无墙可直接右键方块 |
| Q17 | 游戏事件 | **NeoForge EVENT_BUS** — `ResourceInsufficientEvent` 在 hasEnough() 返回 false 时 post，10s 冷却 |
| Q18 | 事件通知 | **聊天栏消息** — `WarehouseNotificationHandler` 订阅事件 → `sendSystemMessage` 到在线玩家 |

---

## 一、关键设计变更 (Q4+Q13)

不再存储 `Map<ElementType, Long>`。仓库是纯粹的物品银行：

```
仓库存 Map<ItemKey, Long>
  ↑                    ↓
  │                NodeBuilding: 方块→物品 (如 oak_log × 64)
  │                Workstation:  方块→物品 (如 cobblestone × 128)
  │
  └─ ColonyResourceAccess:
       ResourceId("wood")  →  ElementMappingLoader →  ItemKey("minecraft:oak_log")
       resource.consume(wood, 10)  →  warehouse.extractItem(oak_log × 10)
```

**元素仍然存在** — `03-element-system` 的 `ElementMappingLoader` 提供 itemId → ElementType 映射。节点建筑产出的是物品（放入仓库），工作站消耗的也是物品（从仓库取）。元素的抽象仅用于：
- 物品配方匹配（"分解 64 圆石 → 128 土"）
- 殖民地三数值计算
- 后期元素消耗特殊逻辑

## 二、架构概要

```
warehouse placed by player
  │
  └─ WarehouseBE extends AbstractWandscapeBE
       │
       ├── items:    Map<ItemKey, Long>           ← 全量 NBT 持久化
       ├── reserved: Map<ItemKey, Long>           ← 不持久化(重启作废)
       └── dirty:    boolean                      ← 有变化 → 触发全量保存
       │
       └── onActivate(player) → 打开 WarehouseScreen

WarehouseManager (implements WarehouseApi + ColonyResourceAccess)
  │  一个类实现两个接口 — 都是读写 WarehouseBE 数据
  │
  ├── WarehouseApi 侧: UUID colonyId + ElementType/itemId
  └── ColonyResourceAccess 侧: ResourceId
       │
       └── ElementMappingLoader 把 ResourceId 转为 ItemKey
             → BuildingApi → WarehouseBE → items/reserved
```

## 三、BE 数据结构

```java
public class WarehouseBE extends AbstractWandscapeBE {
    // === 物品存储 ===
    private final Map<ItemKey, Long> items = new HashMap<>();
    private boolean dirty = false;

    // === 预留 ===
    private final Map<ItemKey, Long> reserved = new HashMap<>();
    // 不持久化 — 重启后归零

    // === 核心 API ===
    long count(ItemKey key)                    // items.getOrDefault(key, 0L)
    long available(ItemKey key)                // count - reserved
    void add(ItemKey key, long amount)         // items.merge(key, amount, Long::sum); dirty=true
    boolean consume(ItemKey key, long amount)  // available>=amount → items[key]-=amount; dirty=true
    boolean reserve(ItemKey key, long amount)  // available>=amount → reserved[key]+=amount
    boolean commit(ItemKey key, long amount)   // items[key]-=amount; reserved[key]-=amount; dirty=true
    void release(ItemKey key, long amount)     // reserved[key]-=amount (不 touch items)
}
```

## 四、NBT 持久格式

```
// NBT tag: {items: {count: N, entries: [...]}}
// 格式: ListTag of CompoundTag，每个: {key: "minecraft:stone", nbt: {...}, count: 123456L}
// reserved 不写入

// 脏标记触发保存:
//   - 区块卸载时 (onChunkUnload)
//   - 每 5 分钟定时 + dirty=true
//   - 首次创建（全量写入）
```

## 五、ResourceId → ItemKey 映射

```
ColonyResourceAccessImpl:
  ResourceId("wood")  → ElementMappingLoader.getMappings()
                         filter by ElementType=WOOD
                         pick first mapping (e.g. oak_log → WOOD)
                         → ItemKey("minecraft:oak_log", null)
  ResourceId("stone_bricks") → ItemKey("minecraft:stone_bricks", null)
  
  // 通用规则：尝试直接从 ResourceId.name() 构造 ItemKey
  // 失败时查 ElementMappingLoader 反向映射
```

## 六、游戏事件

仓库通过 NeoForge EVENT_BUS 广播游戏层事件，UI/通知层订阅响应。

| 事件 | 触发点 | 订阅者 |
|------|--------|--------|
| `ResourceInsufficientEvent` | `WarehouseManager.hasEnough()` false | `WarehouseNotificationHandler` → 聊天栏 |
| `ElementChangedEvent` | 已定义，待接线 | UI 元素面板更新 |

事件流：`core ResourceRequestOp` → `ColonyResourceAccess.hasEnough()` → `WarehouseManager` → `NeoForge.EVENT_BUS.post(ResourceInsufficientEvent)` → `WarehouseNotificationHandler.onResourceInsufficient()` → 全体在线玩家聊天栏。

## 七、文件清单

| 操作 | 文件 | 内容 |
|------|------|------|
| 新增 | `warehouse/WarehouseBE.java` | BE：物品存储、reserve/commit/release、NBT |
| 新增 | `warehouse/WarehouseManager.java` | 同时实现 WarehouseApi + ColonyResourceAccess |
| 新增 | `warehouse/client/WarehouseScreen.java` | GUI：物品网格 + 数量滑条 |
| 新增 | `warehouse/client/WarehouseMenu.java` | ContainerMenu(玩家背包) |
| 新增 | `data/wandscape/buildings/warehouse.json` | category=storage, pattern, block_mapping |
| 修改 | `Wandscape.java` | 注册 warehouse 方块/BE/BlockItem，WandscapeApis.setWarehouseApi() |
| 修改 | `engine/bootstrap/EngineBootstrap.java` | 注入 WarehouseManager as ColonyResourceAccess |

## 七、不做的

- 虚拟滚动 — 用普通渲染，后期优化
- 多仓库支持 — 单殖民地单仓库
- reserved 超时清理
- 物品 NBT 渲染（先只用 itemId 显示图标）
- 搜索过滤
- 存入物品（玩家交互）— 先只读展示
- 维护结算扣物品 — 先空着，15 模块时补
