# 抽象仓库系统

文档编号：NEW-04
版本：1.0
状态：元素 + 物品统一存储 + GUI
依赖：01-shared-api

---

## 一、职责边界

- 以殖民地为单位存储元素（长整数映射）和物品条目（ItemKey → 数量）
- 提供存入/取出/查询接口给玩家和 NPC
- 提供仓库 GUI（虚拟滚动，支持搜索）
- 触发 `ElementChangedEvent` 当元素储量变化
- 持久化（差量保存，不丢失数据）

**不包含：**
- 元素如何产生（节点建筑负责）
- 物品如何制作（工作站负责）
- 建筑维护如何扣费（建筑核心模块调用本模块 API）

---

## 二、数据结构

### 2.1 元素存储

```java
// 每个殖民地一个 ElementStore 实例
private final Map<ElementType, Long> elements = new HashMap<>();
```

初始全为 0。无上限（long 范围，实际不可能打满）。

### 2.2 物品存储

```java
private final Map<ItemKey, Long> items = new HashMap<>();
```

**ItemKey 唯一性**：`itemId` + `CompoundTag`（MC 原生对象，已正确实现 `equals`/`hashCode`）作为复合键。相同 itemId + 语义相同 NBT = 同一仓库条目。构造时 `copy()` 防止外部修改影响键查找。可被 MC 原生的 `ItemStack.areItemStacksEqual()` 交叉验证。

耐久度视为 NBT 的一部分。有耐久的物品（如法杖）不同耐久视为不同条目。

### 2.3 殖民地维度隔离

每个殖民地拥有完全独立的仓库实例。殖民地 ID 是仓库的主键。

```java
private final Map<UUID, ColonyWarehouse> warehouses = new HashMap<>();
```

---

## 三、核心 API

### 3.1 元素操作

```java
// 查询
long getElement(UUID colonyId, ElementType type);

// 存入（节点产出、工作站分解、拆除回收）
void addElement(UUID colonyId, ElementType type, long amount);

// 扣除（建造消耗、维护成本、工作站合成）
// 返回 false 表示储量不足
boolean consumeElement(UUID colonyId, ElementType type, long amount);
```

### 3.2 物品操作

```java
// 查询数量
long getItemCount(UUID colonyId, ItemKey key);

// 取出到指定容器 Inventory（玩家背包或 NPC 虚拟背包）
boolean extractItem(UUID colonyId, ItemKey key, long count, Inventory target);

// 存入一批物品
void insertItems(UUID colonyId, List<ItemStack> items);

// 检查存量是否足够
boolean hasItems(UUID colonyId, ItemKey key, long count);
```

---

## 四、GUI 设计

### 4.1 布局

```
┌──────────────────────────────────────────────────┐
│  殖民地仓库                      搜索: [_________] │
├────────────┬──────────┬──────────┬────────────────┤
│ 元素储量    │ 圆石      │ 橡木原木  │ 建造法杖(#A020)│
│ 土: 128K   │ ×1.0M    │ ×32K     │ ×5            │
│ 木: 52K    │          │          │               │
│ 水: 8K     │ 石砖      │ 铁锭      │ 采集法杖(#00FF)│
│ 火: 1.2K   │ ×500K    │ ×128     │ ×2            │
│            │          │          │               │
├────────────┴──────────┴──────────┴────────────────┤
│  取出 [━━━━━━━●━━━] ×42           存入 [拖放物品]    │
└──────────────────────────────────────────────────┘
```

- **左侧栏**：固定显示元素储量（仅显示有储量的元素）
- **右侧网格**：物品条目（图标 + 名称 + 格式化数量），虚拟滚动
- **搜索栏**：实时过滤物品名称
- **数量选择**：滑条形式，滑条上方实时显示当前数量。范围为 1 ~ min(64, 当前库存量)
- **NBT 物品**：用实际 NBT 渲染图标和 tooltip

### 4.2 Screen 类

```java
public class WarehouseScreen extends AbstractContainerScreen<WarehouseMenu> {
    private EditBox searchBox;
    private VirtualItemGrid itemGrid;
    private ElementPanel elementPanel;
    private Slider quantitySlider;
    // ...
}
```

---

## 五、性能要求

- **查找 O(1)**：`HashMap<ItemKey, Long>`
- **NBT 哈希缓存**：存入时计算，后续直接比较
- **GUI 虚拟滚动**：只渲染可见行（约 20 行），即使有 10 万种物品也不卡
- **序列化差量保存**：
  - 脏标记：每个 `ColonyWarehouse` 维护 `dirtyElements` / `dirtyItems` 两个 `HashSet`，记录本次保存周期内变化的 key
  - 保存时机：区块卸载时（`onChunkUnload`）+ 每 5 分钟定时（`MAINTENANCE_INTERVAL_TICKS` 的 1/4 = 5 分钟）
  - 批量合并：同一周期内对同一 key 的多次操作（如 64 次分解），脏标记只记录一次，最终保存的是最终值
  - 全量兜底：殖民地首次创建或加载后首次保存执行全量写入
- **绝对不洗 NBT**：完全使用 Minecraft 原生 NBT 序列化

---

## 六、独立测试方案

### 单元测试

1. **元素存取**：`addElement` → `getElement` 一致性
2. **元素扣除**：`consumeElement` 扣至负数返回 false
3. **物品插入**：相同 ID+NBT 合并数量，不同 NBT 分条目
4. **物品取出**：NBT 完全复制的物品栈，取出后条目数量正确减少
5. **殖民地隔离**：不同 colonyId 的仓库完全独立
6. **ItemKey 等价性**：相同 itemId + 语义相同 NBT → 同一 key；不同 NBT → 不同 key

### 集成测试

1. 玩家打开仓库 GUI，拖入物品，关闭重开数据不丢失
2. 虚拟滚动：插入 1000 种物品，GUI 不卡顿
3. 搜索过滤正确
4. 取出 NBT 物品后，物品属性完全一致
