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

现有 3 个法杖：

| 配方 | spell_power | 成本 | 等级 | 颜色 |
|---|---|---|---|---|
| basic_wand | +0.0 | earth 16 | lv1 | #FFD700 |
| adept_wand | +0.5 | earth 32 + metal 16 | lv3 | #CD853F |
| master_wand | +1.0 | metal 64 + dark 32 | lv5 | #FF4500 |

`attributes[]` 解析为 `AttributeModifier(AttributeType, amount, ModifierOperation)`；NBT 仅含 `preset_id` + `wand_color`。

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

产出 `wandscape:spell_scroll` 并绑定 `magic_id`（写入 CUSTOM_DATA），在魔法工坊 GUI 合成、产物入殖民地仓库。只覆盖四类战斗魔法（beam/heal/meteor/petrification/conversion/desperation/fortification/enfeeble_field），**不含 teleport/revive**（UTILITY 不物品化）。现有 8 个：scroll_beam/scroll_heal(lv1)、scroll_fortification/scroll_enfeeble_field(lv2)、scroll_petrification/scroll_conversion(lv3)、scroll_meteor/scroll_desperation(lv4)。

## 说明

- Synthesize（合成站）配方**不从 JSON 加载**，运行时从 ElementMappingConfig 推导（`SynthesizeRecipe.fromElementMapping`，cost = buildCost）。
- `RecipeUnlockRequirement`：仅 `min_colony_level`，缺省 1；NONE = min 1。服务端放置/生产时二次校验解锁（`RecipeUnlockChecker`）。
- 生产 channel_ticks：synthesize/decompose = 10×qty、craft_wand / craft_spell = 1200×qty、brew_potion = 120。
