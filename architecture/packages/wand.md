# wand/ — 法杖系统

## 装备流程

装备由 EquipmentComponent（core/component/）统一管理，不再通过旧 WandCarrier：

1. SchedulerSystem 检测 NPC 未装备合适法杖 → 查询 EquipmentComponent
2. 玩家/系统装备 → `EquipmentComponent.equip()` 写入槽位
3. 计算基础属性值 + 装备修饰器 → 有效属性值
4. 执行任务时通过 `world.get(npcId, EquipmentComponent.class)` 获取属性加成

## JSON

位置：`data/wandscape/craft_recipes/*.json`（type="wand"）。attributes[] 格式替代旧 behaviors NBT。12 个预设（carpenter_wand/apprentice_wand/pyromancer_wand/workshop_wand/bulwark_wand/mana_spring_wand/gale_wand/craftsman_wand/bastion_wand/arcane_wand/oblivion_wand/genesis_wand）。装备到 NPC 时按 preset_id 查预设把 attributes 写入 EquipmentComponent.WAND 槽（`WandscapeNpc.syncWandAttributes`）。

## 注册

- 物品：`wandscape:wand`

## 依赖

- shared/api/WandApi / shared/registry/WandscapeApis
- core/component/EquipmentComponent
- core/types/AttributeType / AttributeModifier / ModifierOperation / EquipmentSlot
