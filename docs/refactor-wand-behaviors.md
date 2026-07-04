# 法杖 behaviors 等级系统删除 — 影响分析 + 新设计

> 目标：删除 wand 的 behaviors 等级系统，引入装备槽 + 属性修饰器系统，
> 法杖成为 NPC 装备的一种，影响 NPC 属性，调度系统按任务类型考察属性权重。

---

## 设计决策（已确定）

| # | 问题 | 决定 |
|---|------|------|
| 1 | NPC 法杖获取方式 | NPC 生成时携带默认法杖；调度系统发现 NPC 没有法杖则自动给一根默认法杖。删除所有 Wand 相关的调度系统 |
| 2 | 法杖预设数量 | 合并为 3 种（基础/进阶/大师） |
| 3 | wand_color | 保留，后续法杖会有其他属性 |
| 4 | 仓库调度 | 不需要仓库调度能力，删除 WandProvisionSystem 等 |
| 5 | Scheduler 打分依据 | NPC 有效魔力值（当前魔力 / mana_cost_multiplier）+ 距离 + 任务类型的属性权重 |
| 6 | 装备系统演进 | 路径 A：这次重构直接做装备槽骨架，WandCarrier→EquipmentComponent |
| 7 | 属性模型 | 修饰器模式（ADDITION / MULTIPLY_BASE / MULTIPLY_TOTAL），与 Minecraft AttributeModifier 对齐 |
| 8 | 任务属性权重 | 硬编码在 SchedulerSystem 中，同类型任务用同一套权重，不同类型不同。distance 作为临时计算因子不存入属性 |

---

## 第一部分：新属性系统设计

### 1.1 属性定义

NPC 拥有一组属性（Attribute），基础值 + 装备修饰器叠加 = 有效值。

**当前定义的属性集：**

| 属性 ID | 类型 | 范围 | NPC 基础值 | 说明 | 高低偏好 |
|---------|------|------|-----------|------|---------|
| `range` | float | 1~10 | 1 | 操作距离（方块） | 高 |
| `mana_cost_multiplier` | float | 0.3~1.0 | 1.0 | 魔力消耗倍率 | **低** |
| `max_mana` | float | 0~1000 | 100 | 魔力上限 | 高 |
| `mana_regen` | float | 0~100 | 5 | 魔力回复速度（/tick） | 高 |
| `max_hp` | float | 1~100 | 20 | 生命上限 | 高 |
| `move_speed` | float | 0~1 | 0.1 | 移动速度（方块/tick） | 高 |

> 注意：`currentMana` 和 `currentHp` 是运行时状态，不是属性，不由装备修饰。

### 1.2 修饰器模式

对齐 Minecraft 的 `AttributeModifier` 操作类型：

| 操作 | 效果 | 公式 | 适用场景 |
|------|------|------|---------|
| `ADDITION` | 加算 | `v = base + sum(add)` | max_hp: +5, range: +1, mana_regen: +2 |
| `MULTIPLY_BASE` | 倍率（基于基础值） | `v = base * (1 + sum(multBase))` | move_speed: +10% |
| `MULTIPLY_TOTAL` | 倍率（基于总值） | `v = total * (1 + sum(multTotal))` | 全局百分比加成 |

**简化策略：** 初期大部分装备修饰器只用 `ADDITION`，尤其是 `range` 和 `mana_cost_multiplier`：

```
NPC 基础 range = 1
基础法杖: range +0       → 有效 range = 1
进阶法杖: range +1       → 有效 range = 2
大师法杖: range +2       → 有效 range = 3

NPC 基础 mana_cost_multiplier = 1.0
基础法杖: mana_cost_multiplier +0.0  → 有效值 = 1.0
进阶法杖: mana_cost_multiplier -0.2  → 有效值 = 0.8
大师法杖: mana_cost_multiplier -0.4  → 有效值 = 0.6
```

### 1.3 属性系统数据模型

```java
// core/types/ — 纯 Java 21，零 MC 依赖

public enum AttributeType {
    RANGE,
    MANA_COST_MULTIPLIER,
    MAX_MANA,
    MANA_REGEN,
    MAX_HP,
    MOVE_SPEED
}

public enum ModifierOperation {
    ADDITION,
    MULTIPLY_BASE,
    MULTIPLY_TOTAL
}

// 一个装备上的修饰器
public record AttributeModifier(
    AttributeType type,
    float amount,
    ModifierOperation operation
) {}

// 装备预设数据（由 engine 层从 JSON 加载）
public record EquipmentPreset(
    String id,
    String displayName,
    EquipmentSlot slot,
    List<AttributeModifier> modifiers,
    String color           // future: 可能扩展为 Map<String, String> properties
) {}

// 装备槽位枚举
public enum EquipmentSlot {
    WAND        // 当前只有法杖，未来可扩展 RING, AMULET, ROBE, BOOTS...
}
```

---

## 第二部分：EquipmentComponent（取代 WandCarrier）

### 2.1 新组件设计

```java
// core/component/EquipmentComponent.java

public class EquipmentComponent {
    // 各槽位当前装备的 preset ID
    private final Map<EquipmentSlot, String> equipped = new EnumMap<>(EquipmentSlot.class);
    // 当前各属性的有效值（由 recalculate 计算）
    private final EnumMap<AttributeType, Float> effectiveAttributes = new EnumMap<>(AttributeType.class);

    // 装备流程：
    // 1. 外部（engine 层）调用 equip(slot, presetId, modifiers)
    // 2. 组件存储 slot→presetId 映射
    // 3. 组件从 modifiers 计算有效属性值

    public void equip(EquipmentSlot slot, String presetId, List<AttributeModifier> modifiers) {
        equipped.put(slot, presetId);
        applyModifiers(slot, modifiers);
    }

    public void unequip(EquipmentSlot slot) {
        equipped.remove(slot);
        recalculateAll();  // 重新计算所有属性
    }

    public float getAttribute(AttributeType type) {
        return effectiveAttributes.getOrDefault(type, 0f);
    }

    // NPC 是否有装备在指定槽位
    public boolean hasEquipment(EquipmentSlot slot) {
        return equipped.containsKey(slot);
    }

    // 获取有效魔力值（调度系统用）
    public float getEffectiveMana(float currentMana) {
        float mult = getAttribute(AttributeType.MANA_COST_MULTIPLIER);
        return mult > 0 ? currentMana / mult : currentMana;
    }

    // 冷启动：NPC 生成时调用，赋予默认法杖
    public void equipDefaultWand() {
        // 由 engine 层传入默认法杖的 preset + modifiers
    }

    private void recalculateAll() {
        // 清空 effectiveAttributes
        // 对每个槽位：取出 modifiers，按修饰器模式叠加
        // ADDITION: accumulate sum
        // MULTIPLY_BASE: accumulate multiply
        // MULTIPLY_TOTAL: accumulate multiply
        // 最终：v = base * (1 + sum_mult_base) * (1 + sum_mult_total) + sum_add
    }
}
```

### 2.2 NPC 生成时初始化

`WandscapeNpc` 构造/首次 tick → 调用 `component.equipDefaultWand()` → 赋予基础法杖。

`EntityComponentBridge` 中创建 `EquipmentComponent` 实例，注入默认法杖数据。

### 2.3 NPC 可同时装备多件装备

即使目前只有法杖，`EquipmentComponent` 也支持 `EnumMap<EquipmentSlot, ...>`。未来加戒指时：
- 不改组件结构
- 只加 `EquipmentSlot.RING`
- 戒指的修饰器自动参与 `recalculateAll()`

---
## 第三部分：任务属性权重（SchedulerSystem）

### 3.1 权重表

SchedulerSystem 硬编码以下权重：

| 任务类型 | range | max_mana | mana_regen | mana_cost_mult | move_speed | 说明 |
|---------|-------|----------|------------|----------------|------------|------|
| 建筑 (TransformOp) | 0.4 | 0.1 | 0.1 | -0.1 (低=好) | 0.1 | 距离优先 |
| 合成 (BlockInteractOp: craft) | 0.0 | 0.2 | 0.3 | -0.3 (低=好) | 0.0 | 魔力效率优先 |
| 分解 (BlockInteractOp: decompose) | 0.0 | 0.2 | 0.2 | -0.2 | 0.0 | 同上 |
| 采集 (BlockInteractOp: gather) | 0.3 | 0.1 | 0.2 | -0.1 | 0.1 | 范围+续航 |
| 仪式 (RitualOp) | 0.0 | 0.4 | 0.3 | -0.2 | 0.0 | 魔力优先 |
| 实体交互 (EntityInteractOp) | 0.2 | 0.1 | 0.1 | -0.1 | 0.3 | 速度优先 |

> 以上权重为初版数值，后续可调整。

### 3.2 打分公式

```
attributeScore = Σ( effectiveAttribute_i × weight_i )

其中 weight_i 对 "高偏好" 属性为正数，对 "低偏好" 属性（mana_cost_multiplier）为负数

distanceScore = max(0, 1 - distance / maxDistance) × distanceWeight

score = attributeScore + distanceScore
```

对 mana_cost_multiplier 取负权重等价于：**倍率越低（法杖越好），对得分的负贡献越小，总分越高。** 这与 mana_cost_multiplier 低=好的直觉一致。

### 3.3 SchedulerSystem 简化

**删除的逻辑：**
- `WandCarrier.satisfies(requirements)` — 能力门禁
- `wandProvider.findWand()` + `WandEquipOp` 注入 — 仓库调度
- `bestLevel * 0.2` — 旧等级打分
- `schedulerRetryCount` + `MAX_RETRIES=30` — 等法杖重试
- `WandRequirementUnmet` 失败路径

**保留/新增的逻辑：**
- 检查 NPC 是否有法杖 → 没有则 `equipDefaultWand()`
- 遍历 idle NPC，对每个任务打分：
  ```java
  float score = 0;
  for (AttributeWeight w : taskWeights) {
      score += carrier.getAttribute(w.type) * w.weight;
  }
  score += distanceScore;
  ```
- 选择得分最高的 NPC

---

## 第四部分：要删除的完整类型系统

### 4.1 枚举和值类型（全部删除）

| 类型 | 文件 | 角色 |
|------|------|------|
| `BehaviorType` (enum, 8值) | `shared/data/BehaviorType.java` | 行为类型标记 |
| `BehaviourTag` (enum, 8值) | `core/types/BehaviourTag.java` | core 层镜像标记 |
| `BehaviourLevel` (record, 1..5) | `core/types/BehaviourLevel.java` | 级别值对象 |
| `WandBehaviorData` (interface) | `shared/data/WandBehaviorData.java` | behaviors 视图 |
| `WandBehaviorDataImpl` (record) | `wand/internal/WandBehaviorDataImpl.java` | 实现 |
| `AbilitySet` (record) | `shared/data/AbilitySet.java` | 并集计算 |

### 4.2 波及的 API

| 接口/类 | 影响 |
|----------|------|
| `WandApi` | 删除 `getBehaviorLevel()`、`computeAbilities()`；`getBehaviorData()` 简化或删除 |
| `WandApiImpl` | 删除所有 behaviors 相关方法 |
| `WandDataValidator` | 删除 behaviors 验证逻辑 |
| `TypeBridge` | 删除 BehaviorType ↔ BehaviourTag 转换 |

### 4.3 删除文件清单

| 文件 | 原因 |
|------|------|
| `BehaviorType.java` | 被 AttributeType 取代 |
| `BehaviourTag.java` | 不再需要 core-shared 双枚举 |
| `BehaviourLevel.java` | 无级别概念 |
| `WandBehaviorData.java` | 被 EquipmentPreset 取代 |
| `WandBehaviorDataImpl.java` | 同上 |
| `AbilitySet.java` | 装备间不再需要 behavior 合并 |
| `WandRequirementDeriver.java` | 不再需要 behavior 需求推导 |
| `WandProvider.java` | 不再仓库搜索法杖 |
| `WandProvisionSystem.java` | 不再仓库搜索法杖 |
| `WandEquipExecutor.java` | 不再有 warehouse→NPC 装备管线 |
| `WandReturnExecutor.java` | 同上 |
| `WandLifecycle.java` | 法杖不再有仓库生命周期 |
| `WandLifecycleState.java` | 同上 |
| 对应测试文件 | 同上 |

**替换关系：**

| 旧 | 新 |
|----|----|
| `WandCarrier` | `EquipmentComponent` |
| `WandBehaviorData` | `EquipmentPreset` + `AttributeModifier` |
| `BehaviorType` | `AttributeType` |
| `AbilitySet.merge()` | `EquipmentComponent.recalculateAll()` |
| `WandRequirementDeriver` | 无替代（能力门禁删除） |

---

## 第五部分：JSON 数据格式变化

### 5.1 法杖配方（3 种）

```json
{
  "type": "wand",
  "id": "basic_wand",
  "display_name": "基础法杖",
  "slot": "wand",
  "color": "#FFD700",
  "attributes": [
    { "type": "range", "operation": "addition", "amount": 0 },
    { "type": "mana_cost_multiplier", "operation": "addition", "amount": 0.0 }
  ],
  "cost": { "earth": 16 },
  "unlock_requirement": { "min_magic": 0 }
}
```

3 种预设：

| ID | 名称 | 颜色 | 属性修饰器 | 费用 | 解锁 |
|----|------|------|-----------|------|------|
| `basic_wand` | 基础法杖 | #FFD700 | range:+0, mult:+0.0 | earth:16 | magic:0 |
| `adept_wand` | 进阶法杖 | #CD853F | range:+1, mult:-0.2 | earth:32, iron:16 | magic:50 |
| `master_wand` | 大师法杖 | #FF4500 | range:+2, mult:-0.4 | gold:64, ender:32 | magic:150 |

> `basic_wand` 也是 NPC 默认法杖（无需消耗资源）。

### 5.2 药水配方 — 删除 wand_level

`mana_potion.json`、`stamina_potion.json` 中的 `wand_level` 字段删除。

### 5.3 其他配置 — 删除 wandLevel

- `BuildingConfig.NodeConfig.wandLevel` → 删除
- `ElementMappingConfig.SynthesizeMeta.wandLevel` → 删除
- `CraftWandRecipe.wandLevel` → 删除
- `WorkItem.wandRequirementOverrides` → 删除

### 5.4 法杖 NBT 结构变化

**旧 NBT:**
```nbt
{
  behaviors: { building: 1, gathering: 2 },
  wand_color: "#FFD700",
  range: 2,
  mana_cost_multiplier: 0.9
}
```

**新 NBT:**
```nbt
{
  preset_id: "adept_wand",
  wand_color: "#CD853F"
}
```

法杖物品只存 `preset_id` 和 `wand_color`，属性修饰器由 engine 层从预设注册表查。NBT 大幅简化。

---

## 第六部分：波及的代码修改

### 6.1 NPC 装备相关

| 文件 | 修改 |
|------|------|
| `WandCarrier.java` | → 重写为 `EquipmentComponent.java` |
| `EntityComponentBridge.java` | 创建 EquipmentComponent，注入默认法杖预设 |
| `WandscapeNpc.java` | 初始化时调用 `equipDefaultWand()`，`returnEquippedWands()` 逻辑变更 |

### 6.2 Wand 包简化

| 文件 | 修改 |
|------|------|
| `WandApi.java` | 删除 behavior 方法；`getWandColor()` 改为 `getColor()` 归入 EquipmentPreset API |
| `WandApiImpl.java` | 同上 |
| `WandDataValidator.java` | 删除 behaviors 验证，改为验证 preset_id 合法 |
| `WandPresetLoader.java` | 改为加载 `attributes` 字段（非 behaviors） |
| `WandItem.java` | 不受影响（本身只读 wand_color） |

### 6.3 SchedulerSystem 新逻辑

| 旧代码 | 新代码 |
|--------|--------|
| `wc.satisfies(task.requirements)` | 删除 |
| `wandProvider.findWand(reqs, colonyId)` | 删除 |
| `bestLevel * 0.2` | 删除 |
| 无 | `carrier.getAttribute(type) * weight` 遍历任务权重表 |
| 无 | NPC 无法杖时 `carrier.equipDefaultWand()` |

### 6.4 AtomicOp

`WandEquipOp` / `WandReturnOp` → 删除。不再需要在任务序列中注入装备/卸装操作。

### 6.5 失败恢复

`FailureAnalyzerSystem` 中：
- `WandRequirementUnmet` 不再产生 → 删除相关路径
- `behaviorsCover()`, `behaviorsSum()`, `wandExistsInWarehouse()`, `findPresetForRequirements()` → 全部删除

### 6.6 引导/注册

| 文件 | 修改 |
|------|------|
| `EngineBootstrap.java` | 取消 WandProvisionSystem/WandEquipExecutor/WandReturnExecutor 注册 |
| `CoreBootstrap.java` | SchedulerSystem 不再需要 WandProvider 参数 |
| `CoreBootstrapConfig.java` | 删除 WandProvider 字段 |
| `World.java` (ecs) | 删除 `wandLifecycle` 字段 |

### 6.7 UI

- `CraftingStationScreen.java` — 删除 `locked_reason = "wand_level"` 分支
- `CraftingStationPacket.java` — 删除 `wandLevel`、`hasNonZeroWandLevel()`
- `RequestProductionTaskPacket.java` — 删除 `wandLevel`、`convertWandLevel()`

### 6.8 测试文件

| 文件 | 操作 |
|------|------|
| `BehaviorTypeTest.java` | 删除 |
| `AbilitySetTest.java` | 删除 |
| `WandDataValidatorTest.java` | 简化 |
| `WandPresetLoaderTest.java` | 修改（新 JSON 格式） |
| `WandRequirementDeriverTest.java` | 删除 |
| **新增** `EquipmentComponentTest.java` | 测试属性修饰器叠加计算 |
| **新增** `SchedulerScoringTest.java` | 测试新打分公式 |

---

## 第七部分：架构文档更新

| 文件 | 修改内容 |
|------|----------|
| `architecture/packages/wand.md` | WandItem 简化，PresetLoader 新格式，删除装备流程 |
| **新增** `architecture/packages/equipment.md` | 装备槽 + 属性系统 + EquipmentComponent 参考 |
| `architecture/packages/production.md` | JSON 格式规范更新（attribute 替代 behavior） |
| `architecture/packages/engine.md` | 删除 WandProvisionSystem 等引用 |
| `architecture/packages/core.md` | 新增 AttributeType / ModifierOperation / EquipmentPreset / EquipmentComponent |
| `architecture/README.md` | 更新依赖图 |
| `docs/decisions.md` | 记录此次重构决策 |

---

## 第八部分：总结统计

- **整文件删除**：~15 个 Java 文件 + 5 个测试文件
- **新文件**：约 6 个（`AttributeType.java`, `ModifierOperation.java`, `AttributeModifier.java`, `EquipmentPreset.java`, `EquipmentSlot.java`, `EquipmentComponent.java`）
- **大幅修改**：~5 个文件（SchedulerSystem、FailureAnalyzerSystem、WandPresetLoader、EntityComponentBridge、WandscapeNpc）
- **小幅修改**：~10 个文件（删除 wand_level 引用、简化 API、引导注册）
- **JSON 修改**：7→3 个法杖配方 + 2 个药水配方
- **架构文档**：7 个文件（含 1 个新增）

---

## 第九部分：实施步骤

> 共 **21 步**，每步完成后 `./gradlew build` 可编译。
> 策略：NPC 暂时同时持有 `WandCarrier`（旧）和 `EquipmentComponent`（新），逐步迁移消费者，最后删除旧系统。

---

### 阶段 1 — 新建核心类型

**Step 1：创建 5 个基础类型文件**

| 文件 | 路径 | 说明 |
|------|------|------|
| `AttributeType.java` | `core/types/` | enum: RANGE, MANA_COST_MULTIPLIER, MAX_MANA, MANA_REGEN, MAX_HP, MOVE_SPEED |
| `ModifierOperation.java` | `core/types/` | enum: ADDITION, MULTIPLY_BASE, MULTIPLY_TOTAL |
| `AttributeModifier.java` | `core/types/` | record(AttributeType, float, ModifierOperation) |
| `EquipmentSlot.java` | `core/types/` | enum: WAND（未来可扩展） |
| `EquipmentPreset.java` | `core/types/` | record(id, displayName, slot, List<AttributeModifier>, color) |

验证：`./gradlew build` 通过（新建文件，不动现有代码）。

**Step 2：创建 EquipmentComponent**

路径：`core/component/EquipmentComponent.java`

```
class EquipmentComponent {
  equipped: Map<EquipmentSlot, String>           // slot → presetId
  effectiveAttributes: EnumMap<AttributeType, Float>

  equip(slot, presetId, modifiers)
  equipDefaultWand()     // 设置 range=1, mult=1.0
  unequip(slot)
  hasEquipment(slot): boolean
  getEquippedPreset(slot): String
  getEffectiveMana(currentMana): float     // = currentMana / MANA_COST_MULTIPLIER
  getAttribute(type): float
  recalculateAll()     // 修饰器叠加
}
```

验证：`./gradlew build` 通过。

---

### 阶段 2 — NPC 生命周期

> 此阶段 NPC 同时持有 WandCarrier（旧）和 EquipmentComponent（新）。

**Step 3：CoreBootstrap 注册 EquipmentComponent**

修改 `core/CoreBootstrap.java`：
- `bootstrap()` 中注册 `EquipmentComponent` 组件仓库
- `createNpc()` 中 `world.addComponent(entity, wand)` 之后追加一行：创建默认 EquipmentComponent 并 add

```java
EquipmentComponent eq = new EquipmentComponent();
eq.equipDefaultWand();
world.addComponent(entity, eq);
```

- `createNpc` 日志增加 EquipmentComponent 信息

> NPC 现在同时拥有 WandCarrier 和 EquipmentComponent。旧查询仍能用 WandCarrier 找到 NPC。

验证：`./gradlew build` 通过。进入游戏 NPC 正常生成。

**Step 4：EntityComponentBridge 创建 EquipmentComponent**

修改 `npc/internal/EntityComponentBridge.java`：
- `onNpcJoinWorld()` 中：在 `CoreBootstrap.createNpc()` 调用后，追加 EquipmentComponent 的创建（可选，因为 Step 3 已经在 createNpc 里做了）
- 实际无变动——如果 Step 3 中 createNpc 已经创建了默认 EquipmentComponent，这里不需要改
- 但 NPC_COMPONENTS 数组可以提前加入 `EquipmentComponent.class`（为后续做准备）

验证：`./gradlew build` 通过。

**Step 5：WandscapeNpc 删除 returnEquippedWands（死亡丢弃）**

修改 `npc/entity/WandscapeNpc.java`：
- 删除 `returnEquippedWands()` 方法（NPC 死亡直接丢弃法杖，不返回仓库）
- 删除 `onRemovedFromLevel()` 中调用 `returnEquippedWands()` 的代码行

验证：`./gradlew build` 通过。NPC 死亡不再归还法杖。

---

### 阶段 3 — 消费者迁移

**Step 6：TaskExecutionSystem 切换到 EquipmentComponent**

修改 `core/system/TaskExecutionSystem.java`：
- NPC 查询：`WandCarrier.class` → `EquipmentComponent.class`
- `wc.bestManaEfficiency()` → `eq.getAttribute(AttributeType.MANA_COST_MULTIPLIER)`
- 删除 `injectWandReturnIfNeeded()` 方法
- 删除 `onPackageComplete()` 中调用 `injectWandReturnIfNeeded()` 的代码行
- 删除 `world.wandLifecycle` 的引用

验证：`./gradlew build` 通过。NPC 任务执行法力消耗按新属性计算。

**Step 7：SchedulerSystem 切换到 EquipmentComponent（第1步 — 简化）**

修改 `core/system/SchedulerSystem.java`：

**删除的旧逻辑：**
- NPC 查询中 `WandCarrier.class` → `EquipmentComponent.class`
- 方法 `satisfies()` → 整块删除
- `private satisfies(WandCarrier, Map<...>)` → 删除
- `wandProvider` 字段 → 删除
- `WandEquipOp` 注入块 → 删除
- `schedulerRetryCount` 相关 → 删除
- `bestLevel * 0.2` → 删除
- `score()` 方法中所有 wand/behavior 引用 → 删除

**临时打分（Step 8 会替换）：**
```java
float proximity = 10f / (10f + distance);
float manaEff = eq.getAttribute(MANA_COST_MULTIPLIER);
float score = proximity * 0.6f + (1f - manaEff) * 0.4f;
```

**新增：**
- NPC 无法杖时 `eq.equipDefaultWand()`

**构造函数简化：**
- 删除 `WandProvider` 参数 → 改为无参构造

验证：`./gradlew build` 通过。调度系统正常分配任务。

**Step 8：SchedulerSystem 新增属性加权打分（第2步）**

修改 `core/system/SchedulerSystem.java`：
- 添加任务类型→属性权重表（硬编码）
- `score()` 公式改为：
```java
float score = 0;
// 遍历该任务类型的属性权重
for (var w : getWeightsForTask(task)) {
    score += eq.getAttribute(w.type) * w.weight;
}
// 距离分
float distScore = max(0, 1 - distance/maxDistance) * DISTANCE_WEIGHT;
score += distScore;
```

- `getWeightsForTask()` 按 AtomicOp 类型返回权重数组：
  - TransformOp → range=0.4, mana_cost_mult=-0.1, max_mana=0.1, mana_regen=0.1
  - BlockInteractOp(craft) → max_mana=0.2, mana_regen=0.3, mana_cost_mult=-0.3
  - RitualOp → max_mana=0.4, mana_regen=0.3, mana_cost_mult=-0.2
  - EntityInteractOp → range=0.2, move_speed=0.3, mana_cost_mult=-0.1

验证：`./gradlew build` 通过。NPC 按属性权重执行任务。

---

### 阶段 4 — 删除调度基础设施

**Step 9：删除需求覆盖链**

修改 6 个文件：

| 文件 | 修改 |
|------|------|
| `core/task/GlobalTask.java` | 删除 `requirements` 字段、`schedulerRetryCount` 字段 |
| `core/task/TaskFailureReason.java` | 删除 `WandRequirementUnmet` record、`ColonyEvaluationTooLow` record |
| `core/task/GlobalTaskPool.java` | `addTask()` 中删除 `WandRequirementDeriver.derive()` 调用和 `mergeOverrides()` 调用；删除 `mergeOverrides()` 方法 |
| `core/task/TaskRequest.java` | 删除 `wandRequirementOverrides` 字段 |
| `shared/data/WorkItem.java` | 删除 `wandRequirementOverrides` 字段 |
| `engine/source/BuildingTaskSource.java` | 删除 wandLevel→override 转换代码（对应章节Ⅴ 5.3） |

验证：`./gradlew build` 通过。需求覆盖链完全删除。

**Step 10：删除孤立的调度执行器**

删除 3 个文件：

| 文件 | 理由 |
|------|------|
| `engine/boundary/WandEquipExecutor.java` | 不再被创建，依赖的 AtomicOp.WandEquipOp 尚存 → 可编译 |
| `engine/boundary/WandReturnExecutor.java` | 同上 |
| `core/system/WandRequirementDeriver.java` | Step 9 已删除所有调用 |

验证：`./gradlew build` 通过。

**Step 11：从 AtomicOp 删除 WandEquipOp/WandReturnOp**

修改 `core/op/AtomicOp.java`：
- 从 `sealed interface permits` 列表删除 `WandEquipOp` 和 `WandReturnOp`
- 删除两个 record 定义
- 删除相关 Javadoc

验证：`./gradlew build` 通过。AtomicOp 干净。

**Step 12：清理引导链 + 删除仓库调度系统**

修改 4 个文件 + 删除 3 个文件：

| 操作 | 文件 |
|------|------|
| 修改 | `core/CoreBootstrapConfig.java` — 删除 `WandProvider` 字段、`WandLifecycle` 字段、向后兼容构造器 |
| 修改 | `engine/bootstrap/EngineBootstrap.java` — 删除 WandProvisionSystem 创建、删除 WandEquipExecutor/WandReturnExecutor 注册、更新 CoreBootstrapConfig 构造（去掉 wandProvider 和 wandLifecycle 参数）、更新 FailureAnalyzerSystem 构造（去掉 WAND_PRESET_LOADER 参数） |
| 修改 | `core/CoreBootstrap.java` — 删除 `WandCarrier` 组件注册；SchedulerSystem 构造器不再要 wandProvider（无参）；createNpc 中删除 WandCarrier 创建（EquipmentComponent 已存在） |
| 修改 | `core/ecs/World.java` — 删除 `wandLifecycle` 字段 |
| 删除 | `core/system/WandProvider.java` |
| 删除 | `engine/system/WandProvisionSystem.java` |
| 删除 | `core/task/WandLifecycle.java` + `core/task/WandLifecycleState.java` |

验证：`./gradlew build` 通过。WandCarrier 和整个调度管线消失。

**Step 13：FailureAnalyzerSystem 清理**

修改 `engine/system/FailureAnalyzerSystem.java`：
- `update()` 中删除 `WandRequirementUnmet` 处理分支（`if (task.failureReason instanceof ...)`)
- 删除方法：`handleWandRequirementUnmet()`、`wandExistsInWarehouse()`、`findPresetForRequirements()`、`behaviorsCover()`、`behaviorsSum()`、`isCraftWandInFlight()`、`mapToNbtKey()`
- 删除 `WandPresetLoader` 字段和构造参数
- 保留 `checkAwaitingResources()`（处理通用资源短缺）
- 删除无用 imports

验证：`./gradlew build` 通过。FailureAnalyzer 不再处理法杖。

---

### 阶段 5 — 删除旧类型系统

**Step 14：清理 WandApi / WandApiImpl / WandDataValidator**

修改 3 个文件：

| 文件 | 修改 |
|------|------|
| `shared/api/WandApi.java` | 删除 `getBehaviorLevel()`、`computeAbilities()`、`getBehaviorData()` |
| `wand/internal/WandApiImpl.java` | 删除对应实现方法 |
| `wand/internal/WandDataValidator.java` | 删除 behaviors 验证逻辑 |

验证：`./gradlew build` 通过。

**Step 15：删除旧类型枚举和接口**

删除 7 个文件：

| 文件 | 包 |
|------|-----|
| `BehaviorType.java` | `shared/data/` |
| `BehaviourTag.java` | `core/types/` |
| `BehaviourLevel.java` | `core/types/` |
| `WandBehaviorData.java` | `shared/data/` |
| `WandBehaviorDataImpl.java` | `wand/internal/` |
| `AbilitySet.java` | `shared/data/` |
| `TypeBridge.java` | `shared/bridge/` |

验证：`./gradlew build` 通过。

---

### 阶段 6 — JSON 数据 + 加载器

**Step 16：JSON 配方文件 7→3**

| 操作 | 文件 |
|------|------|
| 删除（7个） | `builder_wand.json`, `gatherer_wand.json`, `crafter_wand.json`, `ritual_wand.json`, `journeyman_builder_wand.json`, `archmage_wand.json`, `legendary_wand.json` |
| 新建（3个） | `basic_wand.json`, `adept_wand.json`, `master_wand.json` |

新格式删掉 `behaviors`、`wand_level`、`range`、`mana_cost_multiplier`，改为 `attributes` 数组。

验证：游戏启动后 /reload 无 JSON 解析错误。检查日志确认 3 个配方加载成功。

**Step 17：WandPresetLoader 新格式**

修改 `wand/internal/WandPresetLoader.java`：
- `WandPreset.fromJson()` 中删除 `behaviors` 读取
- 改为读取 `attributes` 数组（解析为 `List<AttributeModifier>`）
- NBT 中只存 `preset_id` 和 `wand_color`（不再存 behaviors/range/mult）

验证：`./gradlew build` 通过。加载新 JSON 格式无误。

**Step 18：删除 wand_level 残留**

修改 4 个文件：

| 文件 | 修改 |
|------|------|
| `production/data/CraftWandRecipe.java` | 删除 `wandLevel` 字段 + `fromJson()` 中的 wand_level 读取 |
| `building/data/BuildingConfig.java` | 删除 `NodeConfig.wandLevel` |
| `element/internal/ElementMappingConfig.java` | 删除 `SynthesizeMeta.wandLevel` |
| 药水 JSON 文件 | 删除 `wand_level` 字段 |

验证：`./gradlew build` 通过。

---

### 阶段 7 — UI

**Step 19：UI 清理**

修改 3 个文件：

| 文件 | 修改 |
|------|------|
| `production/client/CraftingStationScreen.java` | 删除 `locked_reason = "wand_level"` 的分支渲染 |
| `production/network/CraftingStationPacket.java` | 删除 `wandLevel`、`hasNonZeroWandLevel()`、`locked_reason = "wand_level"` 逻辑；删除 `RecipeEntry.wandLevel` 字段 |
| `production/network/RequestProductionTaskPacket.java` | 删除 `wandLevel` 提取 + `convertWandLevel()` |

验证：`./gradlew build` 通过。打开合成站 UI 无异常。

---

### 阶段 8 — 测试 + 文档

**Step 20：测试文件**

| 操作 | 文件 |
|------|------|
| 删除 | `BehaviorTypeTest.java`、`AbilitySetTest.java`、`WandRequirementDeriverTest.java` |
| 修改 | `WandDataValidatorTest.java`（删除 behaviors 测试）、`WandPresetLoaderTest.java`（新 JSON 格式） |
| 新建 | `EquipmentComponentTest.java`（修饰器叠加计算） |
| 新建 | `SchedulerScoringTest.java`（属性权重打分正确性） |

验证：`./gradlew test` 全绿。

**Step 21：架构文档**

更新 7 个文件：

| 文件 | 修改 |
|------|------|
| `architecture/packages/wand.md` | 重写 NBT 结构、删除装备流程 |
| **新建** `architecture/packages/equipment.md` | 装备槽 + 属性系统 + EquipmentComponent 参考 |
| `architecture/packages/production.md` | JSON 格式 → attribute 替代 behavior |
| `architecture/packages/engine.md` | 删除 WandProvisionSystem/WandEquipExecutor/WandReturnExecutor |
| `architecture/packages/core.md` | 新增 AttributeType/ModifierOperation/EquipmentPreset/EquipmentComponent |
| `architecture/README.md` | 更新依赖图 |
| `docs/decisions.md` | 记录重构决策 |

验证：文档完整。

---

### 实施顺序总表

```
阶段 1：新建类型 ─→ Step 1 → Step 2
阶段 2：NPC 生命周期 ─→ Step 3 → Step 4 → Step 5
阶段 3：消费者迁移 ─→ Step 6 → Step 7 → Step 8
阶段 4：删除调度 ├→ Step 9 → Step 10 → Step 11
             └→ Step 12 → Step 13
阶段 5：删除旧类型 ─→ Step 14 → Step 15
阶段 6：JSON/加载器 ─→ Step 16 → Step 17 → Step 18
阶段 7：UI ─→ Step 19
阶段 8：测试/文档 ─→ Step 20 → Step 21
```

每步完成标志：`./gradlew build` 编译通过，必要时启动客户端验证。核心原则：**每步可独立编译，不遗留断点。**
