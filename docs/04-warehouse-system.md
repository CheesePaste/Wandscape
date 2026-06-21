# 抽象仓库系统

文档编号：NEW-04
版本：3.0
状态：纯 SavedData — ColonyItemBank 存储 + BuildingSavedData 管理 + BuildingInteractHandler 右键开 GUI。零自定义方块/BE。
依赖：01-shared-api, 08-building-core (BuildingSavedData)

---

## 一、职责边界

- 以殖民地为单位存储物品条目（ItemKey → 数量），通过 `ColonyItemBank` (Level SavedData) 持久化
- 提供存入/取出/查询接口给玩家和 NPC
- 提供仓库 GUI（虚拟滚动，支持搜索）
- 支持预留/提交/释放事务语义（用于异步任务资源预约）
- 触发 `ElementChangedEvent` 当元素储量变化
- **物品数据独立于方块**：仓库建筑破坏后物品不丢失，重建建筑即可继续使用

**不包含：**
- 元素如何产生（节点建筑负责）
- 物品如何制作（工作站负责）
- 建筑维护如何扣费（建筑核心模块调用本模块 API）

---

## 二、架构

```
BuildingSavedData              ← 建筑注册 + 空间索引（category=storage）
        ↓ 右键原版方块 (如 minecraft:barrel)
BuildingInteractHandler        ← posIndex O(1) 查找 → 识别 category=storage → 打开 GUI
        ↓
WarehouseManager               ← API 实现（实现 WarehouseApi + ColonyResourceAccess）
        ↓
ColonyItemBank (SavedData)     ← 物品存储（Level SavedData，NBT 持久化）
```

仓库是 `BuildingSavedData` 管理的普通建筑（category=storage），pattern 全用原版方块，anchor 位置推荐 `minecraft:barrel` 作为交互终端。方块破坏 = 建筑标记为 structureIntact=false，物品不受影响。

### 2.1 ColonyItemBank

```java
public class ColonyItemBank extends SavedData {
    // colonyId → items
    private final Map<UUID, Map<ItemKey, Long>> storage;
    // 内存预留（不持久化）
    private final Map<UUID, Map<ItemKey, Long>> reservations;

    // 查询
    long count(UUID colonyId, ItemKey key);
    long available(UUID colonyId, ItemKey key);  // = count - reserved
    Map<ItemKey, Long> getSnapshot(UUID colonyId);

    // 存取
    void add(UUID colonyId, ItemKey key, long amount);
    boolean consume(UUID colonyId, ItemKey key, long amount);

    // 事务预留（NPC 异步任务用）
    boolean reserve(UUID colonyId, ItemKey key, long amount);
    boolean commit(UUID colonyId, ItemKey key, long amount);
    void release(UUID colonyId, ItemKey key, long amount);
}
```

### 2.2 右键交互

仓库不再有自定义方块/BE。右键拦截由 `BuildingInteractHandler` 统一处理（`building/internal/BuildingInteractHandler.java`）：

```java
// category=storage 分支：
UUID colonyId = state.getColonyId();
Map<ItemKey, Long> snapshot = ColonyItemBank.get(level).getSnapshot(colonyId);
player.openMenu(WarehouseMenu.createMenuProvider(snapshot));
```

### 2.3 物品存储

```java
private final Map<ItemKey, Long> items = new HashMap<>();
```

**ItemKey 唯一性**：`itemId` + `CompoundTag`。相同 itemId + 语义相同 NBT = 同一条目。耐久度视为 NBT 的一部分。

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

## 五、持久化与性能

### 5.1 持久化

- `ColonyItemBank` 为 Level SavedData，NBT 全量保存
- 脏标记：`setDirty()` 在任何存取操作后调用
- 保存时机：MC 原生的 SavedData 自动保存（世界保存时）
- reservation 不持久化（服务器重启后重新计算）
- 仓库建筑破坏不影响物品数据

### 5.2 性能

- **查找 O(1)**：`ConcurrentHashMap<UUID, Map<ItemKey, Long>>`
- **GUI 虚拟滚动**：只渲染可见行（约 20 行）
- **线程安全**：`ConcurrentHashMap` 支持并发读写

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

1. 右键原版 barrel（仓库 anchor）→ 打开 GUI，物品正常显示
2. 虚拟滚动：插入 1000 种物品，GUI 不卡顿
3. 搜索过滤正确
4. 取出 NBT 物品后，物品属性完全一致
5. 破坏仓库 anchor 方块 → 物品不丢失 → 重建后恢复
