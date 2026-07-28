# equipment/ — 装备系统

替代旧 WandCarrier。统一管理 NPC 各槽位装备、属性计算。

**cross-cutting 关注点**：核心类型在 core/，桥接在 npc/，调度在 core/system/。

## 核心类型

EquipmentSlot（WAND，预留 RING/AMULET/ROBE/BOOTS）/ AttributeType（RANGE / MANA_COST_MULTIPLIER / MAX_MANA / MANA_REGEN / MAX_HP / MOVE_SPEED）/ AttributeModifier / ModifierOperation（ADD/MULTIPLY）/ EquipmentPreset

## 数据流

```
WandPresetLoader 加载 JSON (attributes[])
  → 法杖物品 NBT (preset_id + wand_color)
  → 玩家/系统装备 → EquipmentComponent.equip()
  → 属性计算: 基础值 + 所有装备修饰器 → 有效属性值
  → SchedulerSystem / TaskExecutionSystem 读取
```

## 依赖

- core/types/EquipmentSlot / AttributeType / AttributeModifier / ModifierOperation / EquipmentPreset
- core/component/EquipmentComponent
- wand/internal/WandPresetLoader
