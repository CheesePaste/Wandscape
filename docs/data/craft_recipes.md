# 数据格式 — 合成配方

位置：`src/main/resources/data/wandscape/craft_recipes/<id>.json`

解析：`production/ProductionRecipeLoader`（类目 `craft_recipes`）。同一个类目注册三个 registry：`type=="wand"` → craftWandRecipes；`type=="potion"` → potionRecipes；`type=="spell"` → spellRecipes；type 缺省按 "wand"。**WandPresetLoader 也读同一类目**（各自 parser 过滤）。

## 法杖配方（type: "wand"）

```json
{
  "type": "wand",
  "craft_station": "crafting_station",
  "id": "basic_wand",
  "display_name": "基础法杖",
  "slot": "wand",
  "wand_color": "#FFD700",
  "attributes": [
    {"type": "spell_power", "operation": "addition", "amount": 0.0}
  ],
  "output": {"item": "wandscape:wand"},
  "cost": {"earth": 16},
  "unlock_requirement": {"min_colony_level": 1}
}
```

现有 12 个法杖（每个预设一支，成本随档位递增，装备槽放杖即应用加成）：

| 配方 | 解锁 | 属性（加法） | 成本 | 颜色 |
|---|---|---|---|---|
| carpenter_wand 木工 | lv1 | work_speed +0.4 | earth 200 + wood 250 | #8B6F47 |
| apprentice_wand 学徒 | lv1 | spell_power +0.25, max_mana +30 | fire 150 + water 150 + wood 150 | #7FB8D0 |
| pyromancer_wand 烈焰 | lv5 | spell_power +0.7 | fire 1800 + metal 1200 | #FF6A00 |
| workshop_wand 工坊 | lv5 | work_speed +0.5, spell_speed +0.15 | earth 1600 + wood 1400 | #9AA5B1 |
| bulwark_wand 铁壁 | lv10 | max_hp +40, armor_value +5 | earth 6000 + metal 4000 + water 3000 | #7A7A7A |
| mana_spring_wand 秘泉 | lv10 | max_mana +150, spell_speed +0.2 | water 7000 + wood 6000 | #3B6FA0 |
| gale_wand 疾风 | lv10 | move_speed +0.2, spell_speed +0.4 | wind 7000 + fire 5000 | #4FB8B0 |
| craftsman_wand 工匠 | lv20 | work_speed +0.9, max_mana +100 | earth 25000 + wood 25000 | #C67B30 |
| bastion_wand 堡垒 | lv20 | move_speed −0.18, max_hp +55, armor_value +8 | earth 25000 + metal 20000 + wood 15000 | #4A4A52 |
| arcane_wand 奥术 | lv20 | spell_power +0.8, spell_speed +0.3 | fire 20000 + dark 15000 + water 15000 | #8B5CE6 |
| oblivion_wand 湮灭 | lv30 | max_hp −40, armor_value −5, spell_power +2.0 | fire 60000 + dark 50000 + metal 40000 | #7A2EA6 |
| genesis_wand 创世 | lv30 | spell_power −1.0, work_speed +1.6, max_mana +200 | wood 80000 + earth 60000 + water 40000 | #C8B74A |

`attributes[]` 解析为 `AttributeModifier(AttributeType, amount, ModifierOperation)`；NBT 仅含 `preset_id` + `wand_color`。**装备到 NPC 法杖槽时按 preset_id 查预设，把属性加成写入 ECS EquipmentComponent.WAND 槽**（`WandscapeNpc.syncWandAttributes`，2026-08-26 起生效；此前 attributes[] 仅解析不应用）。创造模式物品栏按预设自动补发全部 12 个变体。旧 basic/adept/master 已删除，`basic_wand` 保留为中性默认预设（无 JSON 文件，`EquipmentComponent.equipDefaultWand` 硬编码）。

## 药水配方（type: "potion"）

```json
{
  "type": "potion",
  "craft_station": "crafting_station",
  "id": "mana_potion",
  "output": {"item": "wandscape:mana_potion"},
  "cost": {"water": 16, "wood": 4},
  "input_items": ["minecraft:glass_bottle"],
  "unlock_requirement": {"min_colony_level": 1}
}
```

现有：mana_potion、stamina_potion。**归属合成站**（P 阶段 C 起 `craft_station=crafting_station`，随法杖配方一起在合成站 GUI 列出，走 brew_potion 蓝图）。输出物品（`wandscape:mana_potion`/`stamina_potion`）当前未注册，产出入仓为数据条目、无图标。

## 魔法卷轴配方（type: "spell"）

```json
{
  "type": "spell",
  "craft_station": "magic_station",
  "id": "scroll_beam",
  "display_name": "火焰光束卷轴",
  "output": {"item": "wandscape:spell_scroll", "magic_id": "beam"},
  "cost": {"fire": 16, "earth": 8},
  "unlock_requirement": {"min_colony_level": 1}
}
```

产出 `wandscape:spell_scroll` 并绑定 `magic_id`（写入 CUSTOM_DATA），在魔法工坊 GUI 合成、产物入殖民地仓库。只覆盖四类战斗魔法（beam/heal/meteor/petrification/conversion/desperation/fortification/enfeeble_field），**不含 teleport/revive**（UTILITY 不物品化）。现有 8 个，按解锁档分三档（成本 = 同档法杖的约 1/2）：

| 配方 | 解锁 | 成本 |
|---|---|---|
| scroll_beam / scroll_heal | lv1 | fire 120 + earth 105 / water 120 + wood 105 |
| scroll_petrification | lv1 | earth 130 + metal 95 |
| scroll_meteor | lv1 | fire 140 + metal 85 |
| scroll_fortification | lv10 | metal 3500 + earth 3000 |
| scroll_enfeeble_field | lv10 | dark 3300 + wind 3200 |
| scroll_conversion | lv20 | dark 14000 + fire 13500 |
| scroll_desperation | lv20 | dark 14000 + fire 8000 + water 5500 |

## 说明

- Synthesize（合成站）配方**不从 JSON 加载**，运行时从 ElementMappingConfig 推导（`SynthesizeRecipe.fromElementMapping`，cost = buildCost）。
- `RecipeUnlockRequirement`：仅 `min_colony_level`，缺省 1；NONE = min 1。服务端放置/生产时二次校验解锁（`RecipeUnlockChecker`）。
- 生产 channel_ticks：synthesize/decompose = 10×qty、craft_wand / craft_spell = 1200×qty、brew_potion = 120。
- **JEI 展示**：`ElementRecipe` 携带输出物品 CUSTOM_DATA NBT（法杖 preset_id + wand_color、卷轴 magic_id），JEI 渲染具体变体（悬停 tooltip 显示预设名+属性 / 绑定魔法）；元素成本不封顶 64，>64 数量正常显示。
