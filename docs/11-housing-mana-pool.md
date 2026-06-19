# 房屋与魔力池

文档编号：NEW-11
版本：1.0
状态：房屋分配 + 魔力恢复 + 公共魔力池
依赖：01-shared-api, 08-building-core

---

## 一、职责边界

- 房屋建筑：分配给 NPC → 空闲时返回 → 魔力恢复 ×3
- 公共魔力池建筑：殖民地魔力存储 → 充能/抽取
- 对接 NPC 系统（房屋绑定）和任务系统（充能任务）

**不包含：**
- NPC 本身的行为（07 模块负责）
- 管理面板中的分配 UI（14 模块负责）
- 建筑队列和关停（08 模块负责）

---

## 二、房屋

### 2.1 功能

- 每座房屋可绑定至 1 个 NPC
- 绑定后 NPC 空闲时返回该房屋
- 在房屋内魔力恢复速度 ×3（`HOUSE_MANA_REGEN_MULTIPLIER = 3.0f`）
- 未绑定 → 无法享受加速

### 2.2 JSON 配置

```json
// data/wandscape/buildings/mage_house.json
{
  "id": "mage_house",
  "display_name": "法师小屋",
  "category": "basic",
  "block_id": "wandscape:mage_house",
  "comfort": 2,
  "magic": 1,
  "wonder": 1,
  "maintenance_cost": 2,
  "queue": {
    "capacity": 5,
    "task_types": []
  },
  "unlock_requirement": { "min_wonder": 0 }
}
```

### 2.3 HouseBE

```java
public class HouseBE extends AbstractWandscapeBE {
    private UUID assignedNpcId; // null = 未分配

    public boolean assignNpc(UUID npcId) {
        if (assignedNpcId != null) return false; // 已有人住
        this.assignedNpcId = npcId;
        NpcApi.assignHouse(npcId, this.getUUID());
        return true;
    }

    public boolean unassignNpc() {
        if (assignedNpcId == null) return false;
        NpcApi.assignHouse(assignedNpcId, null);
        this.assignedNpcId = null;
        return true;
    }

    public boolean isOccupied() { return assignedNpcId != null; }
}
```

---

## 三、公共魔力池

### 3.1 功能

- 殖民地级别的魔力存储建筑
- **充能**：系统在魔力池储量低于阈值时自动入队充能任务，空闲 NPC 可将个人魔力注入公共池
- **抽取**：任务执行中魔力不足或仪式需要大量魔力时，通过物资请求从公共池抽取

### 3.2 魔力抽取

NPC 主动从魔力池抽取魔力为**操作 B**（`OperationB(buildingId=魔力池, action="extract")`），由 NPC 在任务前通过私有任务执行。

以下为任务执行中的**自动兜底**（非原子操作，是 API 级别的系统辅助）：当 NPC 执行任务中途魔力不足时，系统自动尝试从公共池抽取。若公共池也不足 → 任务中断。

```java
// 任务执行中自动兜底：
if (npc.getCurrentMana() < requiredMana) {
    long deficit = requiredMana - npc.getCurrentMana();
    // 自动从公共池抽取（非 NPC 主动操作）
    boolean ok = ManaPoolApi.consumeMana(colonyId, deficit);
    if (ok) {
        npc.addMana(deficit);
    } else {
        // 公共池也不足 → 任务中断
    }
}
```

### 3.3 充能任务

魔力池储量低于阈值（如最大容量的 30%）时自动入队充能任务：

```java
public class ManaPoolBE extends AbstractWandscapeBE {
    private long manaStored;        // 当前储魔量
    private long maxMana;           // 最大容量（由建筑等级决定）
    private static final long LOW_THRESHOLD_RATIO = 0.3;

    @Override
    public void tick() {
        super.tick();
        if (manaStored < maxMana * LOW_THRESHOLD_RATIO
            && taskQueue.isEmpty()
            && currentTaskId == null
            && !isShutdown()) {
            enqueueChargeTask();
        }
    }

    private void enqueueChargeTask() {
        TaskTemplate chargeTask = new TaskTemplate(
            BehaviorType.RITUAL,
            1,
            List.of(
                new OperationB(this.getUUID(), "charge")
            )
        );
        TaskApi.enqueueBuildingTask(this.getUUID(), chargeTask);
    }
}
```

### 3.4 JSON 配置

```json
// data/wandscape/buildings/mana_pool.json
{
  "id": "mana_pool",
  "display_name": "魔力池",
  "category": "functional",
  "block_id": "wandscape:mana_pool",
  "comfort": 0,
  "magic": 2,
  "wonder": 1,
  "maintenance_cost": 4,
  "mana_pool_config": {
    "max_mana": 10000,
    "low_threshold_ratio": 0.3
  },
  "queue": {
    "capacity": 10,
    "task_types": ["ritual"]
  },
  "unlock_requirement": { "min_wonder": 2 }
}
```

---

## 四、独立测试方案

### 单元测试

1. **房屋分配**：同一房屋不能分配给两个 NPC
2. **魔力恢复倍率**：在房屋内 ×3 vs 不在 ×1 计算正确
3. **充能阈值**：低于 30% 自动入队充能任务
4. **充能任务不重复**：已有充能任务待处理时不重复入队

### 集成测试

1. NPC 绑定房屋后空闲时返回房屋
2. NPC 在房屋内魔力恢复速度明显快于室外
3. 魔力池低于阈值 → 充能任务生成 → NPC 接取 → 魔力池储量增加
4. 任务中魔力不足时从公共池抽取成功
