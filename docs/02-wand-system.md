# 法杖系统

文档编号：NEW-02
版本：1.1
状态：法杖物品 + NBT 结构 + 行为标签 + 能力并集 | 单元测试完成：WandDataValidatorTest (17) + WandPresetLoaderTest (10) + AbilitySetTest (18)
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

法杖为 3D 模型（`models/item/wand.json`）：
- **木杆**：`[7,0,7]` → `[9,10,9]`，无 tintindex，始终显示木纹色
- **浮空宝石**：`[6.5,12,6.5]` → `[9.5,15,9.5]`（3×3×3 立方体），tintindex:0，由 `wand_color` NBT 动态染色（`ItemColor` 机制）

贴图 `textures/item/wand_3d.png`（16×16，左半木纹 + 右半宝石色块）。

### 3.3 右键使用

玩家右键法杖时，从视线方向发射一串彩色静止星星粒子（`CastBoltParticle`），颜色取自法杖 `wand_color` NBT。用于调试法杖颜色和粒子效果。

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

### 单元测试（JVM，无需 MC 运行时）— 已完成

测试位置：`src/test/java/com/wsteam/wandscape/wand/internal/` + `src/test/java/com/wsteam/wandscape/shared/data/`

1. **NBT 校验测试** (WandDataValidatorTest, 17 tests)：合法/非法 NBT 全覆盖 — 颜色格式、行为类型有效性、等级边界、range 边界 (1-5)、mana_cost_multiplier 边界 (0.3-1.0)、缺失可选字段
2. **法杖预设解析测试** (WandPresetLoaderTest, 10 tests)：`WandPreset.fromJson` 有效 JSON、缺失必填字段抛异常、可选字段 present/absent、behaviors 多条目、NBT 内容校验
3. **能力并集测试** (AbilitySetTest, 18 tests)：构造函数防御性拷贝、merge 多法杖取最大等级、satisfies 条件匹配、getLevel 查询、EMPTY 常量、不可变验证

待完成（需要 MC 运行时）：
- `WandApiImpl` 集成测试：`computeAbilities(List<ItemStack>)` 方法需要真实 ItemStack
- NPC 默认 ritual:1 测试：集成到 NPC 系统测试中

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
  assets/wandscape/textures/item/wand_3d.png
  assets/wandscape/models/item/wand.json
  data/wandscape/wands/*.json
```
