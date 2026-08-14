# dataconfig/ — JSON 数据加载框架

WandscapeDataLoader 继承 `SimpleJsonResourceReloadListener`，按 category 扫描 `data/wandscape/<category>/*.json`，支持 `/reload` 热重载。

SimpleDataRegistry\<T\>：泛型注册表，按 category 隔离。

## 数据流

```
data/wandscape/<category>/*.json
  → WandscapeDataLoader.prepare() 按category扫描
  → apply() 按category分发到对应 SimpleDataRegistry
  → 各模块 parser 将 JsonElement 转为强类型对象
```

## 加载确定性（整合包可定制性）

- 扫描**跨命名空间**：`data/<ns>/<category>/*.json`，任意命名空间都能新增数据。
- 派生 id = 文件名（不含命名空间）。同名文件跨命名空间冲突时 **mod 自身命名空间 `wandscape` 优先**，其余按命名空间+路径排序——`apply()` 确定性加载，不依赖 HashMap 顺序。
- **覆盖 mod 数据**：用 `wandscape` 命名空间 + 同文件名，且数据包优先级 ≥ mod jar。包优先级由 `ResourceManager`（`FallbackResourceManager`）确定性解析。
- **整体屏蔽某文件**：pack.mcmeta `filter/block` 正则（如 `"path": "element_mappings/minecraft_oak_log\\.json"`），对 ResourceManager 加载的所有内容生效。

## 依赖

- MC: SimpleJsonResourceReloadListener
- shared/registry/WandscapeDataRegistry
