# 法杖系统

文档编号：NEW-02
版本：1.0
状态：法杖物品 + NBT 结构 + 行为标签 + 能力并集
依赖：01-shared-api

---

## 一、职责边界

- 注册法杖物品（单一物品 ID：`wandscape:wand`）
- 定义和校验 NBT 结构
- 提供能力并集计算（多个法杖 → 总能力映射）
- 提供法杖属性查询（颜色、范围、魔力效率、行为等级）
- 注册创造模式物品栏

**不包含：**
- 法杖如何获取（制作站负责）
- 法杖如何被 NPC 使用（NPC 系统负责）
- 法杖配方的 JSON 定义（16-data-driven-config 负责）

---

## 二、NBT 结构

### 2.1 完整结构

```json
{
  "wand_color": "#A020F0",
  "behaviors": {
    "building": 3,
    "gathering": 2,
    "ritual": 1
  },
  "range": 2,
  "mana_cost_multiplier": 0.8
}
```

### 2.2 字段约束

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| wand_color | 十六进制字符串 | 是 | — | `#[0-9A-Fa-f]{6}` 格式 |
| behaviors | 键值对 | 是 | — | 键=行为标签ID，值=等级(≥1) |
| range | 整数 | 否 | 1 | 1/2/3/5 |
| mana_cost_multiplier | 浮点数 | 否 | 1.0 | 越低越省魔，范围 0.3~1.0 |

### 2.3 NBT 校验规则

```java
// WandDataValidator.java
public class WandDataValidator {
    public static boolean isValid(CompoundTag tag) {
        if (!tag.contains("wand_color")) return false;
        String color = tag.getString("wand_color");
        if (!color.matches("#[0-9A-Fa-f]{6}")) return false;

        CompoundTag behaviors = tag.getCompound("behaviors");
        if (behaviors.isEmpty()) return false;
        for (String key : behaviors.getAllKeys()) {
            if (BehaviorType.fromId(key) == null) return false; // 无效行为标签
            if (behaviors.getInt(key) < 1) return false;        // 等级必须 ≥ 1
        }

        if (tag.contains("range")) {
            int range = tag.getInt("range");
            if (range < 1 || range > 5) return false;
        }

        if (tag.contains("mana_cost_multiplier")) {
            float eff = tag.getFloat("mana_cost_multiplier");
            if (eff < 0.3f || eff > 1.0f) return false;
        }

        return true;
    }
}
```

---

## 三、法杖物品

### 3.1 单一物品 ID

```java
// 所有法杖共享此 ID
public static final DeferredItem<Item> WAND = ITEMS.register("wand",
    () -> new WandItem(new Item.Properties().stacksTo(1)));
```

法杖永不损坏（不实现 `isDamageable` 和 `isBarVisible`）。

### 3.2 物品模型

法杖物品的模型颜色由 `wand_color` NBT 动态染色（使用 `ItemColor` / tint 机制），配一个统一的 2D 法杖贴图 `textures/item/wand.png`。

---

## 四、核心 API 实现

### 4.1 行为等级查询

```java
public int getBehaviorLevel(ItemStack wand, BehaviorType type) {
    if (!wand.hasTag()) return 0;
    CompoundTag behaviors = wand.getTag().getCompound("behaviors");
    return behaviors.getInt(type.getId());
}
```

### 4.2 能力并集

```java
public AbilitySet computeAbilities(List<ItemStack> wands) {
    Map<BehaviorType, Integer> result = new HashMap<>();
    for (ItemStack wand : wands) {
        if (!wand.hasTag()) continue;
        CompoundTag behaviors = wand.getTag().getCompound("behaviors");
        for (String key : behaviors.getAllKeys()) {
            BehaviorType type = BehaviorType.fromId(key);
            int level = behaviors.getInt(key);
            result.merge(type, level, Math::max);
        }
    }
    return new AbilitySet(Collections.unmodifiableMap(result));
}
```

所有 NPC 默认拥有 `ritual:1`（基础物流能力），在 `computeAbilities` 结果中自动合并。该逻辑实现在 NPC 系统（07）调用法杖 API 时，此处只记录能力并集的合并规则。

**重要**：`ritual:1` 与 NPC 是否持有法杖无关——即使 NPC 背包为空（如复活后），默认 `ritual:1` 仍然生效，确保 NPC 始终可执行物资传送、魔力池充能等基础操作。

### 4.3 NPC 默认 ritual:1

```java
// 在 NPC 系统中调用 computeAbilities 时：
AbilitySet wandAbilities = computeAbilities(npc.getWands());
// 自动合并基础能力（无论是否有法杖）
Map<BehaviorType, Integer> merged = new HashMap<>(wandAbilities.abilities());
merged.merge(BehaviorType.RITUAL, 1, Math::max);
return new AbilitySet(merged);
```

---

## 五、配置驱动

法杖的 NBT 预设（每种法杖的默认属性）定义在 JSON：

```json
// data/wandscape/wands/builder_wand.json
{
  "id": "builder_wand",
  "display_name": "建筑法杖",
  "default_color": "#FFD700",
  "behaviors": {
    "building": 1
  },
  "default_range": 1,
  "default_mana_cost_multiplier": 1.0
}
```

---

## 六、独立测试方案

### 单元测试（JVM，无需 MC 运行时）

1. **NBT 校验测试**：传入合法/非法 NBT，验证 `WandDataValidator.isValid()`
2. **能力并集测试**：创建多个模拟 ItemStack（带不同 NBT），验证 `computeAbilities()` 输出正确的最高等级映射
3. **默认值测试**：缺失 range/mana_cost_multiplier 时返回默认值
4. **NPC 默认 ritual:1 测试**：空法杖列表仍包含 ritual:1

### 集成测试（需要 MC 运行时）

1. 通过 `/give` 获取法杖，检查 NBT 是否正确写入
2. 创造模式物品栏中法杖可正常获取
3. 法杖物品模型颜色随 wand_color 正确渲染

---

## 七、文件结构

```
src/main/java/com/wandscape/wand/
  WandItem.java
  WandDataValidator.java
  WandApiImpl.java
  WandDataRegistry.java
src/main/resources/
  assets/wandscape/textures/item/wand.png
  assets/wandscape/models/item/wand.json
  data/wandscape/wands/*.json
```
