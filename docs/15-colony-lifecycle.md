# 殖民地生命周期

文档编号：NEW-15
版本：1.0
状态：殖民地创建 + 初始放置 + 玩家上手流程
依赖：01-shared-api

---

## 一、职责边界

- 殖民地创建逻辑（放置市政厅 → 初始化殖民地数据）
- 殖民地数据管理（UUID、边界、建筑列表、NPC 列表）
- 殖民地删除（拆除市政厅 → 清理数据）
- 开局流程（玩家引导）

**不包含：**
- 市政厅的建筑队列和远程建造（08/14 模块负责）
- 具体建筑的生成（各建筑模块负责）

---

## 二、殖民地创建

### 2.1 创建流程

```
1. 玩家亲手放置"市政厅"方块
2. 右键点击市政厅方块 → 触发殖民地初始化
3. 系统以市政厅位置为中心创建殖民地数据

```

### 2.2 初始化数据

```java
public class ColonyManager {
    public UUID createColony(BlockPos townHallPos) {
        UUID colonyId = UUID.randomUUID();

        // 1. 注册殖民地
        ColonyData data = new ColonyData(
            colonyId,
            townHallPos,
            townHallPos.getX(), townHallPos.getZ() // 中心坐标
        );

        // 2. 创建仓库实例（空）
        WarehouseApi.createWarehouse(colonyId);

        // 3. 初始化三数值 = 0（市政厅的数值在建造完成后计入）
        data.setComfort(0);
        data.setMagic(0);
        data.setWonder(0);

        // 4. 持久化
        saveColonyData(data);

        // 5. 触发事件
        NeoForge.EVENT_BUS.post(new ColonyCreatedEvent(colonyId, townHallPos));

        return colonyId;
    }
}
```

### 2.3 殖民地数据

殖民地以市政厅为中心，默认半径 128 方块（`DEFAULT_COLONY_RADIUS`），范围可在配置中调整。该半径影响：
- 管理面板小地图的显示范围
- `getColonyId(BlockPos pos)` 的坐标归属判定

```java
public class ColonyData {
    private final UUID colonyId;
    private final BlockPos townHallPos;
    private final int centerX, centerZ;
    private final int radius;          // 默认 128，可配置
    private final Set<UUID> buildingIds = new HashSet<>();
    private final Set<UUID> npcIds = new HashSet<>();
    private int comfort, magic, wonder;
}
```

---

## 三、初始开局

### 3.1 玩家操作序列

```
1. 进入世界
2. 制作并放置市政厅方块 → 右键打开管理面板
3. 管理面板显示"殖民地创建完成"
4. 玩家在面板中选择初始建筑放置位置：
   - 森林节点（选一个有树/草的区域）
   - 大地节点（选一个石质区域）
   - 制作站
   - 房屋（供初始 NPC 使用）
   - 仓库
5. 每个建筑的选择 → 生成建造任务 → 进入市政厅队列
6. 建造任务完成 → 初始 NPC 自动生成
7. 初始 3 个 NPC 出现在殖民地中：
   - NPC A: building:1（建造法杖）
   - NPC B: gathering:1（采集法杖）
   - NPC C: crafting:1（制作法杖）
   所有 NPC 默认拥有 ritual:1
8. 殖民地开始自动运转
```

### 3.2 初始 NPC 属性

初始 NPC 为预设固定值，不随机偏移：

| 属性 | NPC A (建造) | NPC B (采集) | NPC C (制作) |
|------|-------------|-------------|-------------|
| 生命值 | 40 | 40 | 40 |
| 魔力值 | 100 | 100 | 100 |
| 法术强度 | 1 | 1 | 1 |
| 恢复速率 | 2 | 2 | 2 |
| 携带法杖 | building:1 | gathering:1 | crafting:1 |

---

## 四、殖民地删除

- 拆除市政厅 → 殖民地标记为删除
- 所有建筑方块保留在原位，但其 BlockEntity 被移除（失去 Wandscape 功能，变为纯装饰方块）
- 所有 NPC 消失（不生成坟墓）
- 仓库数据清空
- **不可逆操作**，需二次确认

> **设计决策**：保留建筑方块而非替换为原版方块，因为方块本身是玩家消耗资源建造的，直接销毁会造成玩家资源损失。方块保留为惰性装饰方块，玩家可手动拆除回收。

---

## 五、殖民地持久化

殖民地数据通过 World SavedData 持久化：

```java
public class ColonySavedData extends SavedData {
    private static final String DATA_NAME = "wandscape_colonies";
    private final Map<UUID, ColonyData> colonies = new HashMap<>();

    public static ColonySavedData load(CompoundTag tag) { /* ... */ }

    @Override
    public CompoundTag save(CompoundTag tag) {
        // 序列化所有殖民地数据
    }
}
```

---

## 六、独立测试方案

### 单元测试

1. **殖民地创建**：合法的 townHallPos → 返回 colonyId
2. **殖民地查询**：`getColonyId(pos)` 返回正确 colonyId
3. **殖民地删除**：清理后所有关联数据移除

### 集成测试

1. 放置市政厅 → 右键 → 管理面板打开 → 显示空殖民地
2. 选择初始建筑位置 → 确认 → 世界中出现投影 → NPC 建造
3. 初始建筑建成 → 初始 NPC 生成 → 开始工作
4. 拆除市政厅 → 殖民地删除 → 建筑失去功能
