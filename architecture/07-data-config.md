# 07 — 数据配置 (`dataconfig/`)

JSON 热重载框架。所有 `data/wandscape/` JSON 的统一加载入口。

## 源文件 (2 文件)

| 文件 | 作用 |
|------|------|
| `internal/SimpleDataRegistry.java` | `WandscapeDataRegistry<T>` 的内存实现：`Map<String, T>` + `BiFunction<String, JsonElement, T>` 解析器。`/reload` 时清空重建 |
| `internal/WandscapeDataLoader.java` | **中央 JSON 加载器**：继承 `SimpleJsonResourceReloadListener`。扫描 `data/<ns>/<category>/*.json`。各模块调用 `register(category, parser)` → 获得 `SimpleDataRegistry<T>`。`/reload` 时清空所有 registry + 重解析 |

## 加载机制

```
WandscapeDataLoader (在 Wandscape 构造器中创建为 static final)
  │  通过 @SubscribeEvent onAddReloadListener 注册到服务端
  │
  ├── "wands"      → WandPresetLoader       → SimpleDataRegistry<WandPreset>
  ├── "element_mappings" → ElementMappingLoader → SimpleDataRegistry<ElementMappingConfig>
  ├── "buildings"  → BuildingConfigLoader    → SimpleDataRegistry<BuildingConfig>
  └── (阶段 3+) "recipes" / "rituals" / "multiblocks"
```

`/reload` 热重载全部，缺失文件→警告不崩溃，格式错误→警告+跳过。

## JSON 目录结构

```
data/wandscape/
├── wands/                    (4 文件) → 03-wand.md
├── element_mappings/         (5 文件) → 04-element.md
├── buildings/                (3 文件) → 05-building.md
├── tags/block/               decomposable.json → 04-element.md
├── recipes/                  (阶段 3)
│   ├── crafting/
│   ├── workstation/
│   └── potion/
├── rituals/                  (阶段 5)
└── multiblocks/              (阶段 5)
```

## 依赖

- `shared/registry/WandscapeDataRegistry<T>` — 实现的接口
- MC `SimpleJsonResourceReloadListener` — 继承的基类
