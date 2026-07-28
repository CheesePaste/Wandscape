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

## 依赖

- MC: SimpleJsonResourceReloadListener
- shared/registry/WandscapeDataRegistry
