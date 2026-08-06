# wand/ — 法杖模块

`src/main/java/com/wsteam/wandscape/wand/`

## 职责

NPC 法师与玩家的法杖物品：预设（配方 JSON → attributes）、NBT、施法触发。

## WandItem

- 注册 `wand`；不可损坏/无耐久条。
- `use()`：服务器端 8 tick 冷却后调 `MagicCaster.cast` 释放法阵（魔法阵+信标光束）。

## WandPresetLoader

- 注册在 `craft_recipes` 类别，与 ProductionRecipeLoader 共享同一数据源（各自 parser 过滤）。
- `WandPreset.fromJson` 遇 `type!="wand"` 返回 null；解析 `attributes[]` 为 `AttributeModifier(AttributeType, amount, ModifierOperation)`。
- **NBT 仅含 `preset_id` + `wand_color`**。

## 3 个 wand 配方（data/wandscape/craft_recipes/）

| 配方 | spell_power | 成本 | 等级 | 颜色 |
|---|---|---|---|---|
| `basic_wand` | +0.0 | earth 16 | lv1 | #FFD700 |
| `adept_wand` | +0.5 | earth 32 + metal 16 | lv3 | #CD853F |
| `master_wand` | +1.0 | metal 64 + dark 32 | lv5 | #FF4500 |

均 `output:{item:"wandscape:wand"}`、`craft_station:"crafting_station"`、`slot:"wand"`。

## WandApiImpl

`getWandColor(ItemStack)`：读 CUSTOM_DATA 的 `wand_color`，缺省 #FFFFFF。

## 与其他模块关系

- 配方生产走 `production/`（CraftWandRecipe → WandscapeBlockInteractExecutor.executeCraftWand 产出带 NBT 的 wand 入库）。
- NPC 装备 wand 走 `npc/`（NpcEquipPacket + ECS EquipmentComponent）。
- 施法动画/光束走 `magic/`。
