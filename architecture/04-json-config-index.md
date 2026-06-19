# JSON 配置索引

所有 `data/wandscape/` 下 JSON 文件的格式、路径、加载模块索引。

## 目录结构

```
data/wandscape/
├── wands/                    # 法杖预设 → 02 wand-system 加载
├── buildings/                # 建筑定义 → 08 building-core 加载
├── recipes/                  # 生产配方
│   ├── crafting/            #   制作站配方 → 10 production-stations 加载
│   ├── workstation/         #   工作站配方(分解/合成) → 10 production-stations 加载
│   └── potion/              #   魔药配方 → 10 production-stations 加载
├── element_mappings/         # 方块↔元素映射 → 03 element-system 加载
├── rituals/                  # 仪式定义 → 05 atomic-operations 加载
├── multiblocks/              # 多方块结构 → 13 ritual-altar 加载
└── tags/                     # 方块标签 → 03 element-system 使用
    └── decomposable.json     #   #wandscape:decomposable 方块标签
```

## 各类格式摘要

### 法杖 (`wands/<id>.json`)

加载模块：02 wand-system

```json
{
  "id": "builder_wand",
  "display_name": "建筑法杖",
  "default_color": "#FFD700",
  "behaviors": { "building": 1 },
  "default_range": 1,
  "default_mana_cost_multiplier": 1.0,
  "unlock_magic_value": 0
}
```

必填：id, display_name, default_color, behaviors
可选：default_range(默认1), default_mana_cost_multiplier(默认1.0), unlock_magic_value(默认0)

### 建筑 (`buildings/<id>.json`)

加载模块：08 building-core

必填：id, display_name, category(basic/node/functional/wonder), block_id, pattern, block_mapping, comfort, magic, wonder, maintenance_cost, queue.capacity, queue.task_types
可选：shutdown_penalty, unlock_requirement, 以及 category 对应的特殊配置块 (node_config, tavern_config, etc.)

### 配方 (`recipes/<station>/<id>.json`)

加载模块：10 production-stations

必填：id, type, station, output, cost, channel_ticks, required_level
可选：output.nbt, unlock_magic_value

### 元素映射 (`element_mappings/<block>.json`)

加载模块：03 element-system

必填：block, build_cost, decompose_yield, decomposable

### 仪式 (`rituals/<id>.json`)

加载模块：05 atomic-operations（Operation D 执行时查询）

必填：id, display_name, channel_ticks, needs_altar, required_ritual_level, mana_cost, element_cost
可选：unlock_wonder_value

### 多方块 (`multiblocks/<id>.json`)

加载模块：13 ritual-altar（祭坛多方块验证）

必填：id, pattern.layers (y_offset + grid), mapping, controller_pos, result_block

## 加载机制

- 使用 `WandscapeDataLoader`（继承 `SimpleJsonResourceReloadListener`，监听 `data/wandscape/` 目录）
- `WandscapeDataLoader` 在 `Wandscape.java` 中通过 `AddReloadListenerEvent` 注册
- 支持 `/reload` 热重载所有 JSON
- 缺失 JSON 文件 → 警告日志，不崩溃
- JSON 格式错误 → 警告日志 + 跳过该文件
- 各模块通过 `WandscapeDataRegistry<T>` 查询加载的数据

## 阶段 1 状态

- `WandscapeDataRegistry<T>` 泛型查询接口已定义在 `shared/registry/`
- `WandscapeDataLoader` 框架已实现在 `dataconfig/internal/`，支持 `BiFunction<String, JsonElement, T>` 解析器
- `WandscapeDataLoader` 通过 `Wandscape.DATA_LOADER` 注册到服务端 reload 事件
- `wands/` 类别已注册：4 个法杖预设 JSON (builder/gatherer/crafter/ritual)，由 WandPresetLoader 加载
- `element_mappings/` 类别已注册：5 个元素映射 JSON (cobblestone/oak_log/stone_bricks/stone/dirt)，由 ElementMappingLoader 加载
- `tags/block/decomposable.json` 方块标签已创建
- 待实现：buildings/ recipes/ rituals/ multiblocks/ 类别

## 阶段 1 状态

- `buildings/` 类别已注册：3 文件（town_hall, forest_node, earth_node），由 08 building-core 加载
- `WandscapeDataLoader.register()` 新增 parser 参数支持，BuildingConfigLoader 注册时注入 Gson 解析器

> **维护规则**：新增 JSON 类别时在"目录结构"中添加一行，在"各类格式摘要"中添加一节。修改必填/可选字段时更新对应节的说明。
