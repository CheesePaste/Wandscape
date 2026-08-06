# 数据格式 — 合成配方

位置：`src/main/resources/data/wandscape/craft_recipes/<id>.json`

解析：`production/ProductionRecipeLoader`（类目 `craft_recipes`）。同一个类目注册两个 registry：`type=="wand"` → craftWandRecipes；`type=="potion"` → potionRecipes；type 缺省按 "wand"。**WandPresetLoader 也读同一类目**（各自 parser 过滤）。

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
  "craft_station": "potion_station",
  "id": "mana_potion",
  "output": {"item": "wandscape:mana_potion"},
  "cost": {"water": 16, "wood": 4},
  "input_items": ["minecraft:glass_bottle"],
  "unlock_requirement": {"min_colony_level": 1}
}
```

现有：mana_potion、stamina_potion。

## 说明

- Synthesize（合成站）配方**不从 JSON 加载**，运行时从 ElementMappingConfig 推导（`SynthesizeRecipe.fromElementMapping`，cost = buildCost）。
- `RecipeUnlockRequirement`：仅 `min_colony_level`，缺省 1；NONE = min 1。服务端放置/生产时二次校验解锁（`RecipeUnlockChecker`）。
- 生产 channel_ticks：synthesize/decompose = 10×qty、craft_wand = 1200×qty、brew_potion = 120。
