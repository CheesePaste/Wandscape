# wand/ — 法杖系统

## 关键类

- **WandItem** (item/) — 法杖物品，永不损坏。所有法杖共用同一物品 ID `wandscape:wand`，通过 NBT `preset_id` 区分预设类型
- **WandApiImpl** (internal/) — WandApi 实现：NBT 读取颜色/射程/魔力消耗倍率
- **WandPresetLoader** (internal/) — 从 `data/wandscape/craft_recipes/*.json`（过滤 type="wand"）加载法杖预设。WandPreset record：id + displayName + defaultColor + nbt(仅 preset_id + wand_color) + attributes(List\<AttributeModifier\>)

**已删除旧类型：** WandDataValidator、WandBehaviorDataImpl（旧 behaviors NBT 系统已整体移除）

## 装备流程

装备系统由 `EquipmentComponent`（core/component/）统一管理，不再通过旧 WandCarrier：

1. **SchedulerSystem** 检测到 NPC 未装备合适法杖 → 通过 EquipmentComponent 查询 NPC 当前装备
2. 玩家手动装备/系统自动装备 → `EquipmentComponent.equip()` 写入装备槽位
3. `EquipmentComponent` 管理多个 EquipmentSlot 上的装备，计算基础属性值 + 装备修饰器 → 有效属性值
4. NPC 执行任务时通过 `world.get(npcId, EquipmentComponent.class)` 获取属性加成

## NBT 结构（简化）

法杖物品的 `DataComponents.CUSTOM_DATA` 存储：
- `preset_id` (String)：预设 ID（如 `"builder_wand"`），用于反查 WandPresetLoader
- `wand_color` (String)：颜色 hex 码
- `range` (int)：射程（可选，默认读预设）
- `mana_cost_multiplier` (float)：魔力消耗倍率（可选，默认读预设）

## JSON 格式

位置：`data/wandscape/craft_recipes/*.json`（type="wand"）。新 attributes[] 格式：

```json
{
  "type": "wand",
  "id": "builder_wand",
  "display_name": "Builder's Wand",
  "wand_color": "#FFD700",
  "attributes": [
    { "type": "range", "operation": "add", "amount": 4 },
    { "type": "mana_cost_multiplier", "operation": "multiply", "amount": 0.5 },
    { "type": "max_mana", "operation": "add", "amount": 100 }
  ],
  "output": { "item": "wandscape:wand" },
  "cost": { "earth": 32, "wood": 16 },
  "unlock_requirement": { "min_magic": 1 }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| type | string | `"wand"` |
| id | string | 配方唯一标识 |
| attributes[] | array | 法杖属性修饰器列表（替代旧 behaviors NBT） |
| attributes[].type | string | `RANGE`/`MANA_COST_MULTIPLIER`/`MAX_MANA`/`MANA_REGEN`/`MAX_HP`/`MOVE_SPEED` |
| attributes[].operation | string | `ADD`/`MULTIPLY` |
| attributes[].amount | float | 修饰值 |
| output.item | string | `"wandscape:wand"` |
| cost | map | 制作消耗元素量 |

**旧 behaviors NBT 系统（`{"building": 3}`）已完全移除**，由新 attributes[] 系统替代。法杖配方共 3 个（basic_wand/adept_wand/master_wand）。

## 注册

- 物品：`wandscape:wand`

## 依赖

- shared/api/WandApi
- shared/registry/WandscapeApis
- core/component/EquipmentComponent（装备管理）
- core/types/AttributeType / AttributeModifier / ModifierOperation / EquipmentSlot
