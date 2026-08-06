# dataconfig/ — 数据加载框架

`src/main/java/com/wsteam/wandscape/dataconfig/internal/`

## 职责

统一的 JSON 数据加载框架：扫描 `data/wandscape/<category>/*.json`，按注册的类别 + parser 函数解析为对象，供各模块查询。

## WandscapeDataLoader

- extends `SimpleJsonResourceReloadListener`；`/reload` 时自动重新加载（也注册为客户端 reload listener）。
- `register(category, parser)`：注册一个类别 + 解析函数 `BiFunction<String, JsonElement, T>`，返回 `WandscapeDataRegistry<T>`。
- `prepare` 覆写：按每个已注册类别扫描 `data/<ns>/<category>/*.json`，重键为 `category/id`。
- `apply`：清空全部 registry → 逐条 `loadEntry(id, json)`；单条解析失败只 `Log.warn`（不中断整体加载）。
- parser 返回 null 时跳过该条目（如 WandPresetLoader 过滤非 wand 配方）。

## SimpleDataRegistry

实现 `WandscapeDataRegistry`：`get(id) / getAll() / contains(id)`；`loadEntry`（parser 结果非空才 put）；`clear()`。

## 注册的类别

| 类别 | 解析目标 | 注册方 |
|---|---|---|
| `buildings` | BuildingConfig | BuildingConfigLoader |
| `blueprints` | BlueprintDefinition AST | BlueprintConfigLoader |
| `craft_recipes` | CraftWandRecipe / BrewPotionRecipe / WandPreset | ProductionRecipeLoader + WandPresetLoader（共享） |
| `element_mappings` | ElementMappingConfig | ElementMappingLoader |
| `magic_circles` | MagicCircleSpec | MagicCircleLoader |
| `road_presets` | RoadPreset | RoadPresetLoader（当前无对应 JSON 文件，预设硬编码） |
