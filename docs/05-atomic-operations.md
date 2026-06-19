# 原子操作

文档编号：NEW-05
版本：1.0
状态：四种原子操作 A/B/C/D 完整定义与执行
依赖：01-shared-api

---

## 一、职责边界

- 定义四种原子操作的具体行为和参数
- 实现每种操作的执行逻辑
- 提供统一执行入口（通过 `AtomicExecutor` 接口）
- 处理 NPC 施法动画（统一为彩色光束）

**不包含：**
- 任务如何拆解为原子操作序列（任务系统负责）
- NPC 如何被调度执行操作（任务系统 + NPC 系统负责）

---

## 二、四种原子操作概览

| 操作 | 名称 | 目标 | 说明 |
|------|------|------|------|
| A | 方块转化 | 方块 | 放置/破坏/转化/阶段变更等改变方块状态的操作 |
| B | 建筑交互 | Wandscape 建筑 | 与模组建筑的交互（打开 GUI、触发功能等） |
| C | 实体交互 | 实体 | 对实体的资源获取/状态施加/行为控制 |
| D | 仪式 | 无形目标 | 传送、复活、天气等非方块/非实体的魔法效果 |

**统一视觉**：全部表现为"举起法杖 → 发射彩色直线粒子束 → 命中目标"。射线不检测沿途方块碰撞（穿墙魔法）。

**操作射程**：NPC 的有效操作距离 = `BASE_OPERATION_RANGE (16) + wand.range × PER_WAND_LEVEL_RANGE (8)`。超距操作自动触发 NPC 传送至目标旁再执行（而非失败）。

---

## 三、操作 A：方块转化

### 3.1 子类

| 子类 | 源 | 目标 | 说明 |
|------|-----|------|------|
| 放置 | 空气 | 方块 | 建造。消耗元素储量 |
| 破坏 | 方块 | 空气 | 拆除。可选产生掉落物。NPC 用法杖拆除回收 100% 元素 |
| 转化 | 方块 A | 方块 B | 加工。消耗元素储量差额（若 B 比 A 贵） |
| 阶段变更 | 作物阶段 N | 阶段 N+1/收获 | 农业操作 |

### 3.2 参数

```java
public record OperationA(
    BlockPos target,
    BlockState source,          // 期望的源方块（不匹配则失败）
    BlockState result,           // 目标方块
    boolean produceDrops,        // 破坏时是否产出掉落物
    Map<ElementType, Long> elementCost // 建造消耗的元素（从殖民地储量扣除）
) implements AtomicStep {}
```

### 3.3 元素消耗

- **放置**：扣除 `elementCost` 中的所有元素。储量不足 → 触发物资等待
- **破坏**：NPC 法杖拆除回收 100% `elementCost` 元素注入仓库。玩家手动工具拆除不回收
- **转化**：扣除 `elementCost` 差额
- **阶段变更**：不消耗元素

### 3.4 源方块不匹配处理

实际操作时，若目标位置的当前方块与 `OperationA.source` 不匹配：
- 世界状态已被其他操作改变（如玩家手动挖掘、爆炸破坏）
- 任务标记失败，以 chat 消息通知玩家（"建造任务失败：位置 (x,y,z) 方块不匹配"）
- 若任务剩余步骤 > 0，任务退回全局池重新分配
- 若任务仅此一步，任务取消

---

## 四、操作 B：建筑交互

### 4.1 设计理念

操作 B 不再处理"开关门、拉杆"等通用方块交互（原版村民已能胜任），而是聚焦于 **Wandscape 建筑的功能交互**。例如：与魔力池交互充能、从仓库存取物品等需要模组特定逻辑的场景。

### 4.2 参数

```java
public record OperationB(
    UUID buildingId,            // Wandscape 建筑 ID（不是坐标，便于重构）
    String action,              // 动作标识，如 "open_gui" "charge" "extract" "decompose" "synthesize"
    Map<String, Object> params  // 操作参数（item_id, count, recipe_id, element, amount, channel_ticks 等）
) implements AtomicStep {}
```

### 4.3 典型应用

| 建筑 | action | 效果 |
|------|--------|------|
| 仓库 | open_gui | NPC 打开仓库界面（用于物资请求/上缴的物品存取） |
| 魔力池 | charge | 将个人魔力注入公共魔力池（buildingId=目标魔力池） |
| 魔力池 | extract | 从公共魔力池抽取魔力到个人池（buildingId=来源魔力池） |
| 制作站 | craft | 触发制作流程 |
| 工作站 | decompose | 分解物品→元素（params: item_id, count） |
| 工作站 | synthesize | 元素合成物品（params: recipe_id, count） |
| 节点建筑 | node_gathering | 采集元素注入仓库（params: element, amount, channel_ticks） |
| 制作站 | craft_wand | 制作法杖/装备（params: recipe_id, count） |
| 魔药站 | brew_potion | 制作药剂（params: recipe_id, count） |
| 酒馆 | recruit | 招募 NPC（params: colonyId + 候选人全部属性） |

MVP 期间，B 操作主要用于仓库存取、魔力池交互、节点采集、工作站分解合成、制作站制作法杖。

### 4.4 操作 B vs 操作 D 判断规则

| 判断条件 | → B | → D |
|---------|-----|-----|
| 必须指定目标建筑实例？ | 是（buildingId 指向具体建筑） | 否（buildingId 可为 null） |
| 效果与建筑功能绑定？ | 是（充能→魔力池、合成→工作站） | 否（传送、复活不依赖特定建筑） |
| 建筑不存在时还能执行吗？ | 否 | 是（瞬发仪式无需建筑） |
| 典型例子 | charge, extract, decompose, synthesize, craft_wand, node_gathering, recruit | self_teleport, item_transport, resurrection, rain_call |

---

## 五、操作 C：实体交互

### 5.1 子类

| 子类 | 说明 | 示例 |
|------|------|------|
| 资源获取 | 从实体获得产物，不杀死 | 剪羊毛、取羽毛 |
| 状态施加 | 施加瞬间或持续效果 | 伤害怪物、给予 Buff |
| 行为控制 | 改变实体行为 | 跟随、坐下 |

### 5.2 参数

```java
public record OperationC(
    UUID targetEntityId,
    String effectId,
    int intensity,
    int durationTicks        // 0 = 瞬间
) implements AtomicStep {}
```

MVP 第一阶段操作 C 暂不实现。保留接口，数据驱动预留。

---

## 六、操作 D：仪式

### 6.1 分类

| 类型 | 说明 | 引导时间 |
|------|------|---------|
| 瞬发仪式 | 一次粒子发射即完成 | 0 tick |
| 引导仪式 | NPC 需持续施法一段时间 | > 0 tick |

引导仪式中断即失败：不保留进度、不部分生效、魔力已消耗但不产生效果。

### 6.2 参数

```java
public record OperationD(
    UUID buildingId,          // 关联建筑（可为 null 表示不需建筑）
    String ritualId,           // 仪式 ID，对应 JSON 配置
    int channelTicks,          // 引导时间（0 = 瞬发）
    boolean needsAltar,        // 是否需要仪式祭坛
    long manaCost,             // 魔力消耗（来自仪式 JSON）
    Map<ElementType, Long> elementCost  // 元素消耗（来自仪式 JSON）
) implements AtomicStep {}
```

### 6.3 仪式清单

| 仪式 | ritualId | 引导时间 | 需祭坛 | 需求等级 |
|------|----------|---------|--------|---------|
| 自传送 | self_teleport | 瞬发 (0) | 否 | ritual 1 |
| 物品传送 | item_transport | 瞬发 (0) | 否 | ritual 1 |
| 玩家召唤 | summon_player | 瞬发 (0) | 否 | ritual 1 |
| 守护结界 | guardian_barrier | 中引导 | 是 | ritual 2 |
| 群体振奋 | mass_vigor | 中引导 | 是 | ritual 2 |
| 复活 | resurrection | 长引导 | 是 | ritual 1 |
| 唤雨 | rain_call | 长引导 | 是 | ritual 3 |
| 驱雨 | rain_dispel | 长引导 | 是 | ritual 3 |
| 折跃门 | warp_gate | 长引导 | 是 | ritual 3 |

MVP 仅实现：物品传送、复活。充能魔力池为操作 B（见 §4.3）。

---

## 七、统一执行入口

```java
public class AtomicExecutorImpl implements AtomicExecutor {
    @Override
    public CompletableFuture<ExecutionResult> executeA(OperationA op, UUID npcId) {
        // 1. 验证源方块匹配
        // 2. 检查元素储量（若为放置/转化）
        // 3. 扣除元素（通过 WarehouseApi）
        // 4. 播放光束粒子 (wand_color → 目标色)
        // 5. 修改方块状态
        // 6. 若为破坏，回收元素
        // 7. 返回结果
    }

    @Override
    public CompletableFuture<ExecutionResult> executeB(OperationB op, UUID npcId) {
        // 1. 查找建筑实例（通过 buildingId）
        // 2. 从 op.params 中提取操作参数（item_id, count, recipe_id, element 等）
        // 3. 调用建筑的处理方法（action + params 路由到具体逻辑）
        // 4. 播放光束粒子
        // 5. 返回结果
    }

    // executeC / executeD 类似
}
```

---

## 八、魔力消耗

- 操作 A：每方块消耗 1 魔力 × mana_cost_multiplier
- 操作 B：瞬发交互（open_gui / charge / extract）= 1 魔力；引导交互（decompose / synthesize / node_gathering / craft_wand）= `ceil(channel_ticks / 20)` 魔力（1 魔力/秒）
- 操作 C：1 ×（1 + intensity × 0.5）魔力 × mana_cost_multiplier
- 操作 D：魔力消耗由仪式 JSON 的 `mana_cost` 字段定义，不从公式计算。瞬发仪式通常 `mana_cost` 较小，引导仪式较大

**任务开始前预判**：NPC 接取任务时，调度器检查 NPC 当前魔力是否足够完成该任务的最小步骤。若不足 → 自动判定中断，任务退回全局池重新分配，NPC 不开始执行（避免执行到一半中断）。

NPC 执行过程中魔力耗尽时，若操作 A 可只处理魔力允许的方块数，其余跳过并标记任务部分完成。

---

## 九、独立测试方案

### 单元测试

1. **OperationA 验证**：源方块不匹配 → 失败
2. **元素扣除/回收**：放置 → 扣除元素；NPC 拆除 → 回收元素；玩家手动 → 不回收
3. **OperationD 瞬发 vs 引导**：channelTicks=0 vs >0 的行为差异
4. **引导中断**：OperationD 引导中被中断 → 魔力已消耗、无效果

### 集成测试

1. NPC 执行 OperationA 放置方块，元素正确扣除，光束粒子正确播放
2. NPC 执行 OperationD 复活仪式，长引导完成后坟墓消失、NPC 复活
3. 操作过程中 NPC 魔力耗尽，任务中断正确处理
