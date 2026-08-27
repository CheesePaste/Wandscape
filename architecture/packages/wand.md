# wand/ — 法杖系统

## 装备流程

装备直接走 vanilla 物品栏主手槽位（`MAINHAND`），通过 `ItemAttributeModifiers` 赋予属性修饰符：

1. NPC 持有法杖物品于主手
2. 原版 `LivingEntity.detectEquipmentUpdates()` 自动读取 `WandItem.getDefaultAttributeModifiers(ItemStack)` 并应用到实体的 `AttributeInstance`
3. 任务调度与执行系统直接读取实体属性（如 `world.entityOps.getWorkSpeed(npcId)`）

## JSON

位置：`data/wandscape/craft_recipes/*.json`（type="wand"）。attributes[] 格式。12 个预设（carpenter_wand/apprentice_wand/pyromancer_wand/workshop_wand/bulwark_wand/mana_spring_wand/gale_wand/craftsman_wand/bastion_wand/arcane_wand/oblivion_wand/genesis_wand）。由 `WandPresetLoader` 加载并在 MC 运行时构建为 `ItemAttributeModifiers`。

## 注册

- 物品：`wandscape:wand`

## 依赖

- shared/api/WandApi / shared/registry/WandscapeApis
- engine/attribute/WandscapeAttributes
- core/types/AttributeType / AttributeModifier / ModifierOperation
