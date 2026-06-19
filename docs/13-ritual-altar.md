# 仪式祭坛

文档编号：NEW-13
版本：1.0
状态：多方块仪式祭坛 + 复活仪式 + 其他高级仪式
依赖：01-shared-api, 08-building-core

---

## 一、职责边界

- 定义仪式祭坛多方块结构（检测和验证）
- 执行需要祭坛的高级仪式（复活、天气、结界）
- 对接任务系统（复活任务自动发布）

**不包含：**
- 不需要祭坛的瞬发仪式（物品传送、充能魔力池）—— 这些由 NPC 直接执行操作 D
- 仪式定义数据（16 模块负责 JSON）
- 引导中断处理（05 原子操作 + 06 任务系统负责）

---

## 二、多方块结构

### 2.1 结构定义

仪式祭坛是一个多方块结构（如 3×3×2），玩家按正确布局放置后，中心方块识别完整结构并激活。

多方块检测通过 JSON 定义结构匹配模式，不硬编码。

> **格式说明**：多方块使用分层 grid 格式（直观匹配矩形区域），建筑建造/修复使用 BlockOffset 数组格式（遍历高效）。两者目的不同——多方块检测是矩形区域内的模式匹配，建筑建造是稀疏方块列表的逐个放置。

```json
// data/wandscape/multiblocks/ritual_altar.json
{
  "id": "ritual_altar",
  "pattern": {
    "layers": [
      {
        "y_offset": 0,
        "grid": [
          ["S", "S", "S"],
          ["S", "A", "S"],
          ["S", "S", "S"]
        ]
      },
      {
        "y_offset": 1,
        "grid": [
          ["S", "P", "S"],
          ["P", " ", "P"],
          ["S", "P", "S"]
        ]
      }
    ]
  },
  "mapping": {
    "S": "minecraft:stone_bricks",
    "A": "wandscape:altar_core",
    "P": "wandscape:rune_pillar"
  },
  "controller_pos": { "x": 1, "y": 0, "z": 1 },
  "result_block": "wandscape:ritual_altar"
}
```

### 2.2 验证

多方块结构在玩家放置任意组成方块时检测。结构完整 → 替换为激活的祭坛方块。结构被破坏 → 祭坛失效，直至重新检测通过。

---

## 三、RitualAltarBE

```java
public class RitualAltarBE extends AbstractWandscapeBE {
    private boolean isActive = false; // 多方块结构是否完整
    private long lastRitualTick = 0;

    @Override
    public void tick() {
        super.tick();
        // 检测多方块结构
        isActive = MultiblockValidator.validate(level, worldPosition, "ritual_altar");
        // 如果有死亡 NPC → 自动入队复活任务
        if (isActive && taskQueue.isEmpty() && currentTaskId == null && !isShutdown()) {
            // 检查殖民地中是否有已死亡待复活的 NPC
            if (hasDeadNpcs(colonyId)) {
                enqueueRitualTask("resurrection");
            }
        }
    }

    private void enqueueRitualTask(String ritualId) {
        RitualConfig config = getRitualConfig(ritualId);
        TaskTemplate ritualTask = new TaskTemplate(
            BehaviorType.RITUAL,
            config.requiredLevel(),
            List.of(
                new OperationD(this.getUUID(), ritualId, config.channelTicks(), true)
            )
        );
        TaskApi.enqueueBuildingTask(this.getUUID(), ritualTask);
    }
}
```

---

## 四、仪式清单

### 4.1 需要祭坛的仪式

| 仪式 | required_level | 引导时间 | 效果 |
|------|---------------|---------|------|
| 守护结界 | ritual 2 | 中引导 | 殖民地边界生成临时伤害区域 |
| 群体振奋 | ritual 2 | 中引导 | 所有村民工作效率 +20% |
| 复活 | ritual 1 | 长引导 | 复活指定 NPC |
| 唤雨 | ritual 3 | 长引导 | 殖民地范围降雨 |
| 驱雨 | ritual 3 | 长引导 | 清除降雨 |
| 折跃门 | ritual 3 | 长引导 | 开启临时传送门 |

### 4.2 不需要祭坛的仪式（NPC 直接执行操作 D）

| 仪式 | required_level | 说明 |
|------|---------------|------|
| 物品传送 | ritual 1 | NPC 背包 ↔ 仓库 |
| 玩家召唤 | ritual 1 | 召唤玩家到殖民地 |

> 充能/抽取魔力池是**操作 B（建筑交互）**，不是仪式。详见 05 §4.3。

---

## 五、复活仪式流程

1. 殖民地中有已死亡 NPC（死亡数据记录在殖民地中）
2. 仪式祭坛检测到死亡 NPC → 自动入队复活任务
3. 调度器匹配持有 ritual:1 的 NPC（所有 NPC 默认拥有）
4. NPC 前往祭坛，开始长引导
5. 引导完成 → NPC 在祭坛旁重生（生命值与魔力值满，无装备）
6. 复活后 NPC 默认 `ritual:1` 仍生效（与装备无关），可立即接取物资传送、魔力池充能等基础任务
7. NPC 死亡时物品已掉落地上，复活不恢复物品
8. 引导中断 → 魔力已消耗，任务重新入池

---

## 六、多祭坛并行

可建造多座仪式祭坛。每座独立队列、独立冷却（由各自的任务占用自然控制）。多座祭坛可同时执行不同仪式。

---

## 七、MVP 范围

MVP 仅实现复活仪式一种需要祭坛的仪式。

---

## 八、独立测试方案

### 单元测试

1. **多方块检测**：合法结构 → 激活；破坏一块 → 失效
2. **仪式配置加载**：所有仪式 JSON 正确解析
3. **队列容量**：祭坛队列容量 10

### 集成测试

1. 按正确布局放置祭坛组件 → 祭坛激活
2. NPC 死亡后祭坛自动入队复活任务
3. 任意 NPC 执行复活（ritual:1 默认拥有）→ NPC 在祭坛旁重生（无装备）
4. 引导中断（NPC 受伤）→ 复活失败，任务重新入池
5. 破坏祭坛结构 → 祭坛失效
