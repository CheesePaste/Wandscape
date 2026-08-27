# equipment/ — 装备系统

装备直接走 vanilla 物品栏槽位（法杖在 MAINHAND，盔甲在 HEAD/CHEST/LEGS/FEET），统一由 vanilla `Attribute` 与 `ItemAttributeModifiers` 驱动。

## 核心类型

AttributeType（MAX_HP / MOVE_SPEED / SPELL_POWER / WORK_SPEED / SPELL_SPEED / ARMOR_VALUE / MAX_MANA / HEALTH_REGEN / MANA_REGEN）/ AttributeModifier / ModifierOperation / WandscapeAttributes

## 数据流

```
WandPresetLoader 加载 JSON (attributes[])
  → 构建 ItemAttributeModifiers (EquipmentSlotGroup.MAINHAND)
  → 法杖物品 NBT (preset_id + wand_color)
  → NPC 装备在主手 → vanilla LivingEntity.detectEquipmentUpdates()
  → 实体 AttributeInstance 自动挂载修饰符
  → SchedulerSystem / TaskExecutionSystem / NpcSpellPowerHandler 读取有效属性
```

## 依赖

- core/types/AttributeType / AttributeModifier / ModifierOperation
- engine/attribute/WandscapeAttributes
- wand/internal/WandPresetLoader
