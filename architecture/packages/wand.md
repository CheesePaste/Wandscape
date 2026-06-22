# wand/ — 法杖系统

## 关键类

- **WandItem** (item/) — 法杖物品，永不损坏。NBT 存储行为标签和等级
- **WandApiImpl** (internal/) — WandApi 实现：NBT 读取 + AbilitySet 并集计算
- **WandPresetLoader** (internal/) — 从 `data/wandscape/wands/*.json` 加载法杖预设
- **WandDataValidator** (internal/) — NBT 数据校验
- **WandBehaviorDataImpl** (internal/) — WandBehaviorData 只读视图实现

## NBT 结构

法杖物品的 `DataComponents.CUSTOM_DATA` 存储：
- 行为标签：键值对（如 `building:3`），声明能力领域和等级
- 颜色、射程、魔力倍率等

## JSON

位置：`data/wandscape/wands/*.json`。4 个预设：builder_wand/gatherer_wand/crafter_wand/ritual_wand

## 注册

- 物品：`wandscape:wand`

## 依赖

- shared/api/WandApi, shared/data/AbilitySet, shared/data/WandBehaviorData
- shared/registry/WandscapeApis
