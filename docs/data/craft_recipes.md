# 数据格式 — 合成配方

位置：`src/main/resources/data/wandscape/craft_recipes/<id>.json`

解析：`production/ProductionRecipeLoader`（类目 `craft_recipes`）。同一个类目注册四个 registry：`type=="wand"` → craftWandRecipes；`type=="potion"` → potionRecipes；`type=="spell"` → spellRecipes；`type=="misc"` → miscRecipes（权杖/指南针/仓库终端/盟誓戒指等杂项物品）；type 缺省按 "wand"。**WandPresetLoader 也读同一类目**（各自 parser 过滤）。

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
| carpenter_wand 木工 | lv1 | work_speed +0.4 | earth 1100 + wood 1300 | #8B6F47 |
| apprentice_wand 学徒 | lv1 | spell_power +0.25, max_mana +30 | fire 800 + water 800 + wood 800 | #7FB8D0 |
| pyromancer_wand 烈焰 | lv5 | spell_power +0.7 | fire 7000 + metal 5000 | #FF6A00 |
| workshop_wand 工坊 | lv5 | work_speed +0.5, spell_speed +0.15 | earth 6000 + wood 6000 | #9AA5B1 |
| bulwark_wand 铁壁 | lv10 | max_hp +40, armor_value +5 | earth 14000 + metal 9000 + water 7000 | #7A7A7A |
| mana_spring_wand 秘泉 | lv10 | max_mana +150, spell_speed +0.2 | water 17000 + wood 13000 | #3B6FA0 |
| gale_wand 疾风 | lv10 | move_speed +0.2, spell_speed +0.4 | wind 17000 + fire 13000 | #4FB8B0 |
| craftsman_wand 工匠 | lv20 | work_speed +0.9, max_mana +100 | earth 45000 + wood 45000 | #C67B30 |
| bastion_wand 堡垒 | lv20 | move_speed −0.18, max_hp +55, armor_value +8 | earth 45000 + metal 35000 + wood 25000 | #4A4A52 |
| arcane_wand 奥术 | lv20 | spell_power +0.8, spell_speed +0.3 | fire 35000 + dark 30000 + water 30000 | #8B5CE6 |
| oblivion_wand 湮灭 | lv30 | max_hp −40, armor_value −5, spell_power +2.0 | fire 75000 + dark 60000 + metal 45000 | #7A2EA6 |
| genesis_wand 创世 | lv30 | spell_power −1.0, work_speed +1.6, max_mana +200 | wood 95000 + earth 70000 + water 50000 | #C8B74A |

`attributes[]` 解析为 `AttributeModifier(AttributeType, amount, ModifierOperation)`；NBT 仅含 `preset_id` + `wand_color`。**装备到 NPC 法杖槽时按 preset_id 查预设，把属性加成写入 ECS EquipmentComponent.WAND 槽**（`WandscapeNpc.syncWandAttributes`，2026-08-26 起生效；此前 attributes[] 仅解析不应用）。创造模式物品栏按预设自动补发全部 12 个变体。旧 basic/adept/master 已删除，`basic_wand` 保留为中性默认预设（无 JSON 文件，`EquipmentComponent.equipDefaultWand` 硬编码）。

## 药水配方（type: "potion"）

**已删除（2026-08-26）**：mana_potion / stamina_potion 两个配方 JSON 移除——输出物品从未注册，产出入仓为无图标数据条目，玩家不可见且无实际用途。代码中的 `BrewPotionRecipe` 类型、`brew_potion` 蓝图、合成站药水路由仍保留（数据驱动，配方为空时优雅降级：合成站 GUI 与 JEI 不列药水，`RequestProductionTaskPacket`/`executeBrewPotion` 对未知 recipe_id 兜底拒绝）。

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
| scroll_beam / scroll_heal | lv1 | fire 650 + earth 550 / water 650 + wood 550 |
| scroll_petrification | lv1 | earth 700 + metal 500 |
| scroll_meteor | lv1 | fire 700 + metal 500 |
| scroll_fortification | lv10 | metal 8000 + earth 7000 |
| scroll_enfeeble_field | lv10 | dark 8000 + wind 7000 |
| scroll_conversion | lv20 | dark 24000 + fire 23000 |
| scroll_desperation | lv20 | dark 24000 + fire 13000 + water 10000 |

## 杂项物品配方（type: "misc"）

```json
{
  "type": "misc",
  "craft_station": "crafting_station",
  "id": "magic_compass",
  "display_name": "魔法指南针",
  "output": {"item": "wandscape:magic_compass"},
  "cost": {"earth": 600, "wind": 600},
  "unlock_requirement": {"min_colony_level": 1}
}
```

与法杖 `type:"wand"` 同构但产物是**独立注册物品**（权杖/指南针/仓库终端/盟誓戒指等右键行为法器、功能物品），不带 preset 属性 NBT。一律走合成站 `production:craft`，按 recipe_id 解析，**type 不参与合成机制分发**（见 recipe-unify 计划，后续收敛为单条通用配方）。

| 配方 | 解锁 | 成本 |
|---|---|---|
| peace_wand / follow_wand / shelter_wand / hostile_wand | lv1 | 见各自 json |
| magic_compass 魔法指南针 | lv1 | earth 600 + wind 600 |
| advanced_magic_compass 高级魔法指南针 | lv10 | earth 5000 + wind 5000 |
| ultimate_magic_compass 终极魔法指南针 | lv20 | earth 30000 + wind 25000 + dark 15000 |
| warehouse_terminal 仓库终端 | lv20 | earth 45000 + metal 65000 + dark 40000 |
| oath_ring 盟誓戒指 | lv1 | earth 800 + water 800 |
| oath_ring_mid 中级盟誓戒指 | lv10 | earth 8000 + water 8000 |
| oath_ring_high 高级盟誓戒指 | lv20 | earth 30000 + water 30000 |

**定价基准（2026-08-28）**：杂项物件（权杖/指南针/戒指/终端）为 QoL/便利性物品，按 **utility + 主题** 定价而非法杖档。每个物件档次成本 = 同档卷轴档（约法杖 1/2）附近，控制权杖一律低于法杖档（lv1 上限 1,800 < 法杖 2,400）；同档内按功能价值拉开差距（指南针 < 戒指 < 传送 < 终端）。元素按物品主题多样化（指南针=土+风、传送=暗、戒指=土+水、终端=土+金属+暗），避免全堆土/水瓶颈。**warehouse_terminal 例外**：无限容量背包过于 OP，定价 150,000（约 lv20 最贵法杖 105,000 的 1.4 倍），作为刻意的强回收 sink，其 dark 40,000 ≈ lv20 最稀缺元素日收入的 ~5.5 天，达到"忍痛"门槛。

## 说明

- Synthesize（合成站）配方**不从 JSON 加载**，运行时从 ElementMappingConfig 推导（`SynthesizeRecipe.fromElementMapping`，cost = buildCost）。
- `RecipeUnlockRequirement`：仅 `min_colony_level`，缺省 1；NONE = min 1。服务端放置/生产时二次校验解锁（`RecipeUnlockChecker`）。
- 生产 channel_ticks：synthesize/decompose = 10×qty、craft_wand / craft_spell = 1200×qty、brew_potion = 120。
- **JEI 展示**：`ElementRecipe` 携带输出物品 CUSTOM_DATA NBT（法杖 preset_id + wand_color、卷轴 magic_id），JEI 渲染具体变体（悬停 tooltip 显示预设名+属性 / 绑定魔法）；元素成本不封顶 64，>64 数量正常显示。
