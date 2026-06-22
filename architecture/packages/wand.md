# wand/ — 法杖系统

## 关键类

- **WandItem** (item/) — 法杖物品，永不损坏。NBT 存储行为标签和等级。所有法杖共用同一物品 ID `wandscape:wand`，通过 NBT "behaviors" 标签区分类型
- **WandApiImpl** (internal/) — WandApi 实现：NBT 读取 + AbilitySet 并集计算
- **WandPresetLoader** (internal/) — 从 `data/wandscape/wands/*.json` 加载法杖预设。WandPreset 记录：id + displayName + defaultColor + nbt(behaviors/range/mana_cost_multiplier/wand_color)。engine 层 WandProvisionSystem 和 WandEquipExecutor 依赖此加载器
- **WandDataValidator** (internal/) — NBT 数据校验
- **WandBehaviorDataImpl** (internal/) — WandBehaviorData 只读视图实现

## NC 法杖装备流程

1. **SchedulerSystem** 检测到 task.requirements 无 NPC 满足 → 通过 WandProvider 查询仓库
2. **WandProvisionSystem** (engine 层) 扫描 ColonyItemBank 中所有 `wandscape:wand` 物品，按 NBT "behaviors" 匹配需求 → 返回 preset ID
3. Scheduler 向 NPC 私有队列注入 WandEquipOp + WandReturnOp，然后 assign 任务
4. **WandEquipExecutor** 从 Bank 消耗 wand 物品 → 读 WandPresetLoader 解析能力 → WandCarrier.equip() 合并能力 → 更新 NPC 手持
5. 主任务执行完毕后 **WandReturnExecutor** 从 WandCarrier.unequip() → 将 wand 物品（含 preset NBT）存回 Bank

## NBT 结构

法杖物品的 `DataComponents.CUSTOM_DATA` 存储：
- `behaviors` (CompoundTag)：能力标签（如 `building:3`），声明能力领域和等级
- `wand_color` (String)：颜色 hex 码，用于渲染和同型匹配
- `range` (int)：射程
- `mana_cost_multiplier` (float)：魔力消耗倍率

## JSON

位置：`data/wandscape/wands/*.json`。4 个预设：builder_wand(BUILDING:1)/gatherer_wand(GATHERING:1)/crafter_wand(CRAFTING:1)/ritual_wand(RITUAL:2)

## 注册

- 物品：`wandscape:wand`

## 依赖

- shared/api/WandApi, shared/data/AbilitySet, shared/data/WandBehaviorData
- shared/registry/WandscapeApis
- core/component/WandCarrier（通过 engine 层 equip/unequip）
