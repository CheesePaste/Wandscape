# wand/ — 法杖系统

## 装备流程

装备由 EquipmentComponent（core/component/）统一管理，不再通过旧 WandCarrier：

1. SchedulerSystem 检测 NPC 未装备合适法杖 → 查询 EquipmentComponent
2. 玩家/系统装备 → `EquipmentComponent.equip()` 写入槽位
3. 计算基础属性值 + 装备修饰器 → 有效属性值
4. 执行任务时通过 `world.get(npcId, EquipmentComponent.class)` 获取属性加成

## JSON

位置：`data/wandscape/craft_recipes/*.json`（type="wand"）。attributes[] 格式替代旧 behaviors NBT。3 个预设（basic_wand/adept_wand/master_wand）。

## 注册

- 物品：`wandscape:wand`

## 依赖

- shared/api/WandApi / shared/registry/WandscapeApis
- core/component/EquipmentComponent
- core/types/AttributeType / AttributeModifier / ModifierOperation / EquipmentSlot
