# 任务与调度系统

文档编号：NEW-06
版本：1.0
状态：全局任务池 + 调度器 + 私有池 + 生命周期 + 建筑队列
依赖：01-shared-api

---

## 一、职责边界

- 维护殖民地级别的全局任务池
- 为每个 NPC 维护私有任务队列
- 2 秒心跳调度：收集空闲 NPC、匹配能力、分配任务
- 管理任务生命周期（状态转换、中断续传、物资等待）
- 管理建筑队列 → 全局任务池的逐个发布
- 任务持久化（殖民地卸载后恢复）

**不包含：**
- NPC 如何执行具体操作（NPC 系统 + 原子操作模块负责）
- 建筑如何生成任务（各建筑模块负责）
- 物资是否充足（仓库模块负责）

---

## 二、架构

```
  [各建筑模块]                [玩家手动]              [事件触发]
       │                         │                       │
       ▼                         ▼                       ▼
  ┌──────────────────────────────────────────────────────────┐
  │                    全局任务池                             │
  │  [待审批] → [待分配] → [进行中] → [已完成]               │
  │                ↑            ↓                            │
  │                └── [中断] ←─┤                            │
  │                └── [物资等待] ←── 元素/物品不足          │
  └────────────────────────┬─────────────────────────────────┘
                           │ 调度器 (每 2 秒)
                           ▼
  ┌──────────┐    ┌──────────┐    ┌──────────┐
  │ NPC A    │    │ NPC B    │    │ NPC C    │
  │ 私有池   │    │ 私有池   │    │ 私有池   │
  │ 全局任务 │    │ 全局任务 │    │ 空闲      │
  └──────────┘    └──────────┘    └──────────┘
```

---

## 三、双任务池

### 3.1 全局池

- **容量**：无上限
- **接取限制**：每个 NPC 同时只接 1 个全局任务
- **数据结构**：

```java
private final Map<UUID, TaskData> globalTasks = new ConcurrentHashMap<>();
private final List<UUID> pendingAssignQueue = new ArrayList<>(); // 按 priority 排序
```

### 3.2 私有池

- **容量**：无上限
- **优先级**：高于全局任务。NPC 必须先清空私有池
- **来源**：
  - 自传送 → `OperationD(ritualId="self_teleport")`：目标过远/寻路失败/卡死时自动生成。消耗由仪式 JSON 定义
  - 区域清理 → `OperationA`：目标位置有非目标方块，需先清理
  - 物资请求 → `OperationD(ritualId="item_transport")`：从仓库传送物品到背包
  - 物资上缴 → `OperationD(ritualId="item_transport")`：背包物品存入仓库
  - 魔力充能/抽取 → `OperationB(buildingId=魔力池, action="charge"/"extract")`

- **数据结构**：

```java
// 每个 NPC 一个 FIFO 队列
private final Map<UUID, Queue<TaskData>> privatePools = new HashMap<>();
```

私有任务不进入调度器，NPC 直接顺序执行。所有私有任务使用相同的原子操作 A/B/C/D。

---

## 四、任务生命周期

### 4.1 状态转换

```
[任务生成] → 待审批 → 待分配 → 进行中 → 已完成
                 │         │         │
                 │         │         ├→ 物资不足 → 物资等待 → 待分配
                 │         │         │
                 │         │         └→ NPC中断 → 中断 → 待分配
                 │         │
                 │         └→ 玩家取消 → [删除]
                 │
                 └→ 玩家否决 → [删除]
```

### 4.2 任务属性

```java
public interface TaskData {
    UUID getTaskId();
    TaskStatus getStatus();
    int getPriority();
    BehaviorType getRequiredBehavior();
    int getRequiredLevel();
    List<AtomicStep> getSteps();          // 原子操作序列（可能含物资请求标记）
    int getCurrentStepIndex();            // 当前执行到第几步
    UUID getAssignedNpcId();              // null if unassigned
    UUID getOwnerBuildingId();            // 发布此任务的建筑
    List<InterruptRecord> getInterruptHistory();
    boolean isLargeTask();                // 是否需要审批
}
```

### 4.3 中断与续传

- 中断原因：NPC 魔力耗尽、受伤召回、强制中断
- 进度回滚至上一个已完成的原子指令节点
- 任务重新进入"待分配"，保留进度
- **中断冷却**：同一 NPC 在冷却时间（5 分钟）内不能重新接同一任务
- **卡死监控**：300 tick（15 秒）内同一 NPC 对同一任务中断 3 次 → 判定卡死 → 自动重置：释放当前任务、清空私有池、传送至绑定的房屋、魔力回满、通知玩家

### 4.4 仪式任务特殊规则

引导型仪式（操作 D，channelTicks > 0）中断后：
- 进度归零（不保留）
- 魔力已消耗不退还
- 任务直接回"待分配"重新分配

---

## 五、调度器

### 5.1 心跳流程（每 40 tick = 2 秒）

```
1. 收集所有"空闲"NPC（无全局任务、私有池为空、无异常状态）
2. 对每个空闲 NPC，计算总能力集（背包法杖并集 → AbilitySet）
3. 获取全局池中所有"待分配"任务，按 priority 降序
4. 对每个任务（从高到低）：
   a. 遍历所有空闲 NPC
   b. 检查能力匹配：npc.getAbilities().satisfies(task.requiredBehavior, task.requiredLevel)
   c. 检查魔力预判：npc.currentMana >= minStepManaCost
   d. 检查中断冷却：npc 不在 interruptHistory 中或冷却已过
   e. 找到最高评分 NPC，分配任务
5. 分配成功 → 任务状态 = IN_PROGRESS
```

### 5.2 NPC 评分

```java
private double scoreNpc(NpcData npc, TaskData task) {
    double score = npc.getSpellPower() * 10;
    score += npc.getCurrentMana() * 0.1;
    // 同建筑连续执行加成（减少 NPC 在不同建筑间往返）
    if (task.getOwnerBuildingId().equals(npc.getLastBuildingId())) {
        score += SAME_BUILDING_CONTINUATION_BONUS; // 见 WandscapeConstants，默认 50
    }
    return score;
}
```

### 5.3 NPC 执行循环

```
NPC 空闲
    │
    ├→ 私有池有任务？ ──是→ 执行下一个私有任务 → 完成 → 回到检查
    │
    └→ 否 → 等待调度器分配全局任务
                │
                ├→ 分配到 → 接取全局任务
                │              │
                │              ├→ [移动]：NPC 前往任务目标。距离 < 64 尝试寻路步行，
                │              │         距离 ≥ 64 / 寻路失败 / 卡死 → 入队私有 self_teleport 任务（Operation D）
                │              │
                │              ├→ 解析步骤
                │              ├→ [元素请求]：从殖民地储量扣除
                │              ├→ [物品请求]：NPC 自行操作 D 传送（需 ritual:1）
                │              │             后备：发布到全局池等其他 NPC
                │              ├→ 储量不足 → 物资等待 → NPC 释放
                │              ├→ [原子操作]：执行 A/B/C/D
                │              ├→ 进度回写
                │              ├→ 还有下一步？──是→ 继续
                │              └→ 否 → 任务完成 → NPC 空闲
                │
                └→ 未分配到 → 空闲，等下次心跳
```

移动阶段不中断任务——寻路失败、卡死均生成 self_teleport 私有任务兜底。魔力不足时 NPC 原地等待恢复后继续执行 self_teleport（不释放全局任务）。

---

## 六、建筑队列机制

### 6.1 设计

每个建筑内部维护一个 FIFO 任务队列。建筑只在 **当前无进行中/待分配任务** 时，从队列头部取出一个任务发布到全局池。

- 这保证一个建筑同一时间只有一个活跃任务（天然冷却）
- 玩家可一次性下达多个指令（如工作站合成 60 组物品），不用反复操作

### 6.2 队列容量

| 建筑类型 | 容量 |
|---------|------|
| 市政厅 | 5 |
| 万能工作站 | 60 |
| 制作站 | 60 |
| 魔药站 | 10 |
| 仪式祭坛 | 10 |
| 节点建筑 | 10 |
| 房屋 | 5 |
| 魔力池 | 10 |

### 6.3 队列操作接口

```java
// TaskApi 中
UUID enqueueBuildingTask(UUID buildingId, TaskTemplate template);
List<UUID> getBuildingQueue(UUID buildingId);
boolean reorderBuildingQueue(UUID buildingId, int fromIndex, int toIndex);
boolean cancelBuildingQueueTask(UUID buildingId, UUID taskId);
```

### 6.4 连续执行优化

当 NPC 在某建筑完成一个任务后：
- 建筑队列中还有下一个任务
- NPC 魔力充足且无异常
→ 直接将下一个任务分配给同一 NPC（跳过全局池重新匹配），减少调度开销和往返。

若 NPC 魔力不足以完成下一个任务 → **接取前自动判定中断**，该任务退回全局池，由其他空闲 NPC 接取。不会出现 NPC 执行到一半才因魔力不足中断的情况。

---

## 七、任务来源

| 来源 | 触发条件 | 示例 |
|------|---------|------|
| 建筑自动生成 | 建筑队列取头发布 | 节点冷却完毕 → 采集任务 |
| 玩家手动发布 | 管理面板操作 | 建造任务、制作任务 |
| 事件触发 | 殖民地状态变化 | NPC 死亡 → 复活任务 |
| 结构损坏 | 建筑方块被破坏/爆炸 | 自动发布修复任务（高优先级） |
| 连锁反应 | 任务物资不足 | 自动发布补货任务 |
| 物资请求后备 | NPC 无 ritual 能力 | 发布到全局池等他人帮忙 |

---

## 八、独立测试方案

### 单元测试

1. **状态转换**：所有合法状态转换成功，非法转换抛异常
2. **调度匹配**：创建模拟 NPC 和任务池，验证正确的能力匹配和优先级排序
3. **中断冷却**：同一 NPC 冷却期内无法接同一任务
4. **建筑队列**：入队 3 个任务 → 仅第 1 个发布 → 完成后第 2 个自动发布
5. **私有池优先**：NPC 私有池非空时不接全局任务
6. **物资等待**：元素不足 → 状态转 AWAITING_MATERIALS → NPC 释放

### 集成测试

1. 放置节点建筑，验证自动发布采集任务到全局池
2. 多个空闲 NPC，验证调度器按评分分配
3. NPC 执行中魔力耗尽，验证中断回滚和 NPC 释放
4. 补货完成后物资等待任务自动唤醒
