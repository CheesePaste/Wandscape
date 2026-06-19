# 注册表目录

所有 NeoForge DeferredRegister 实例的集中索引。新增注册项时在此登记。

## DeferredRegister 声明位置

| Register 类型 | 变量名 | 声明位置 | 用途 |
|---------------|--------|---------|------|
| Blocks | `BLOCKS` | Wandscape.java (主类) | 所有模组方块 |
| Items | `ITEMS` | Wandscape.java (主类) | 所有模组物品（含 BlockItem） |
| CreativeModeTab | `CREATIVE_MODE_TABS` | Wandscape.java (主类) | 创造模式物品栏标签 |
| EntityTypes | `ENTITIES` | npc/ | 模组实体（NPC） |
| BlockEntityTypes | `BLOCK_ENTITIES` | building/ | 模组方块实体 |
| MenuTypes | `MENUS` | 各模块 screen/ | 模组 GUI 菜单 |
| DataComponentTypes | `DATA_COMPONENTS` | wand/ | 法杖数据组件 |

## 注册项清单

### 方块

| 注册 ID | 声明模块 | 对应 BE | BlockItem |
|---------|---------|---------|-----------|
| (待实现) | | | |

### 物品

| 注册 ID | 声明模块 | 类型 |
|---------|---------|------|
| `wandscape:wand` | 02 wand-system | WandItem (NBT 行为标签) |

### 实体

| 注册 ID | 声明模块 | 实体类 |
|---------|---------|--------|
| (待实现) | | |

### 方块实体

| 注册 ID | 声明模块 | 绑定的方块 |
|---------|---------|-----------|
| (待实现) | | |

### 菜单

| 注册 ID | 声明模块 | 对应的 Screen |
|---------|---------|---------------|
| (待实现) | | |

## 注册约定

1. **一个 DeferredRegister 只在一个地方声明**：同类型注册项统一注册，分散声明会导致重复注册崩溃
2. **注册 ID 命名**：`wandscape:<snake_case_name>`，用小写+下划线
3. **BlockItem 随方块一起注册**：在同一个模块中 `registerSimpleBlockItem`
4. **创造模式物品栏**：每个标签在 `CREATIVE_MODE_TABS` 中注册，通过 `BuildCreativeModeTabContentsEvent` 添加物品

## 阶段 1 状态

- `BLOCKS`、`ITEMS`、`CREATIVE_MODE_TABS` 已在 `Wandscape.java` 中声明并注册到 modEventBus
- `wandscape:wand` 法杖物品已注册 (WandItem)
- `wandscape:wandscape_tab` 创造模式物品栏标签已注册
- WandApi + ElementApi 在 `commonSetup` 中注册到 WandscapeApis
- WandPresetLoader + ElementMappingLoader 通过 WandscapeDataLoader 注册
- 待实现：08 building-core 方块/BE 注册

> **维护规则**：新增注册项时在对应表格添加一行。删除时移除行并确认没有遗留引用。
