# equipment/ — 装备系统

替代旧 WandCarrier 系统。统一管理 NPC 各槽位装备、属性计算。核心类型分散在 core/types/ 和 core/component/。

**装备系统是 cross-cutting 的关注点——核心类型在 core/，桥接在 npc/，调度在 core/system/。**

## 核心类型 (core/types/)

- **EquipmentSlot** (enum) — 装备槽位枚举。当前 WAND，预留 RING/AMULET/ROBE/BOOTS
- **EquipmentPreset** (record) — 装备预设（从 JSON 加载）：id/displayName/slot/modifiers/color
- **AttributeType** (enum) — NPC 属性类型：RANGE / MANA_COST_MULTIPLIER / MAX_MANA / MANA_REGEN / MAX_HP / MOVE_SPEED
- **AttributeModifier** (record) — 属性修饰器：type(AttributeType) + amount(float) + operation(ModifierOperation)
- **ModifierOperation** (enum) — 修饰操作：ADD / MULTIPLY

## 核心组件 (core/component/)

- **EquipmentComponent** — NPC 装备组件。管理多个 EquipmentSlot 上的装备，计算基础属性值 + 装备修饰器 → 有效属性值。提供 equip()/unequip()/hasEquipment()/getEquippedPreset()，内置默认 WAND 修饰器 + NPC 基础属性值

## 桥接 (npc/internal/)

- **EntityComponentBridge** — NPC join/leave 时创建/移除 EquipmentComponent。onNpcJoinWorld 创建 EquipmentComponent 实例

## 消费者

| 类 | 包 | 用途 |
|----|-----|------|
| CoreBootstrap | core/ | 注册 EquipmentComponent 组件存储 |
| SchedulerSystem | core/system/ | 查询 NPC 装备信息进行评分和任务分配 |
| TaskExecutionSystem | core/system/ | 运行时获取 NPC 属性影响任务行为 |
| WandPresetLoader | wand/internal/ | 从 JSON 加载装备预设（attributes[]） |

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
