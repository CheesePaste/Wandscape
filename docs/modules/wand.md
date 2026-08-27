# wand/ — 法杖模块

`src/main/java/com/wsteam/wandscape/wand/`

## 职责

NPC 法师的法杖物品：预设（配方 JSON → attributes）、NBT、施法表现载体。

## WandItem

- 注册 `wand`；不可损坏/无耐久条。
- 纯外观/属性载体，无玩家施放行为（玩家法杖右键施放已移除，测试完成）；施法由 `magic/` 的 `MagicCaster.castNpcAt` 驱动。
- **tooltip**（`appendHoverText`）：按 preset_id 显示预设名（`craft_recipe.wandscape.<id>`）+ 逐条属性加成（负数标红）。

## WandPresetLoader

- 注册在 `craft_recipes` 类别，与 ProductionRecipeLoader 共享同一数据源（各自 parser 过滤）。
- `WandPreset.fromJson` 遇 `type!="wand"` 返回 null；解析 `attributes[]` 为 `AttributeModifier(AttributeType, amount, ModifierOperation)`（amount 可为负，支持牺牲型法杖）。
- **NBT 仅含 `preset_id` + `wand_color`**。
- **属性应用**：NPC 装备法杖时按 preset_id 查预设，把 attributes 写入 ECS EquipmentComponent.WAND 槽（见「与其他模块关系」）。此前 attributes[] 仅解析、从不应用。

## 12 个 wand 配方（data/wandscape/craft_recipes/）

| 配方 | 解锁 | 属性（加法） | 成本 | 颜色 |
|---|---|---|---|---|
| `carpenter_wand` 木工 | lv1 | work_speed +0.4 | earth 1100 + wood 1300 | #8B6F47 |
| `apprentice_wand` 学徒 | lv1 | spell_power +0.25, max_mana +30 | fire 800 + water 800 + wood 800 | #7FB8D0 |
| `pyromancer_wand` 烈焰 | lv5 | spell_power +0.7 | fire 7000 + metal 5000 | #FF6A00 |
| `workshop_wand` 工坊 | lv5 | work_speed +0.5, spell_speed +0.15 | earth 6000 + wood 6000 | #9AA5B1 |
| `bulwark_wand` 铁壁 | lv10 | max_hp +40, armor_value +5 | earth 14000 + metal 9000 + water 7000 | #7A7A7A |
| `mana_spring_wand` 秘泉 | lv10 | max_mana +150, spell_speed +0.2 | water 17000 + wood 13000 | #3B6FA0 |
| `gale_wand` 疾风 | lv10 | move_speed +0.2, spell_speed +0.4 | wind 17000 + fire 13000 | #4FB8B0 |
| `craftsman_wand` 工匠 | lv20 | work_speed +0.9, max_mana +100 | earth 45000 + wood 45000 | #C67B30 |
| `bastion_wand` 堡垒 | lv20 | move_speed −0.18, max_hp +55, armor_value +8 | earth 45000 + metal 35000 + wood 25000 | #4A4A52 |
| `arcane_wand` 奥术 | lv20 | spell_power +0.8, spell_speed +0.3 | fire 35000 + dark 30000 + water 30000 | #8B5CE6 |
| `oblivion_wand` 湮灭 | lv30 | max_hp −40, armor_value −5, spell_power +2.0 | fire 75000 + dark 60000 + metal 45000 | #7A2EA6 |
| `genesis_wand` 创世 | lv30 | spell_power −1.0, work_speed +1.6, max_mana +200 | wood 95000 + earth 70000 + water 50000 | #C8B74A |

均 `output:{item:"wandscape:wand"}`、`craft_station:"crafting_station"`、`slot:"wand"`。成本锚点（2026-08-27 重定价）：单支总价 ≈ 该档日收入的 4%~25%（lv1 2,400 → lv30 180k/215k），每个元素组件 ≈ 0.7~3 天该元素日收入（低档攒大半天到一天、高档攒数天）——法杖现为可随时拆卸调换的永久 buff，不再是"死亡即消失"的一次性投入，造价按"每次购买是决策而非零钱"锚定。旧 basic/adept/master 已删除；`basic_wand` 保留为中性默认预设（无 JSON，`EquipmentComponent.equipDefaultWand` 硬编码），新 NPC 出生自带，可从合成 GUI 换成任意 12 支之一。

## WandApiImpl

- `getWandColor(ItemStack)`：读 CUSTOM_DATA 的 `wand_color`，缺省 #FFFFFF。
- `getWandPresetId(ItemStack)`：读 `preset_id`；默认杖/未绑定返回 null。
- `getWandModifiers(presetId)`：preset id → `WandPreset.attributes()`；未知 id 返回 null。

## 与其他模块关系

- 配方生产走 `production/`（CraftWandRecipe → WandscapeBlockInteractExecutor.executeCraftWand 产出带 NBT 的 wand 入库）。
- **NPC 装备 wand 走 `npc/`**：`NpcMenu` 法杖槽变更 → `WandscapeNpc.syncWandAttributes()` 读手部物品 preset_id → `WandApi.getWandModifiers` → `EquipmentComponent.equip(WAND, presetId, modifiers)`（有效属性 = base + Σ）。新 NPC/旧存档加载时经 `onAddedToLevel` / `EntityComponentBridge` 同步一次。施法动画/光束走 `magic/`。
- 创造模式物品栏：`Wandscape.acceptWandPresets` 按全部预设补发 12 个变体（数据驱动）。
