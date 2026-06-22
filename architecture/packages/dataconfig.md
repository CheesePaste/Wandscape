# dataconfig/ — JSON 数据加载框架

## 关键类

- **WandscapeDataLoader** — 继承 `SimpleJsonResourceReloadListener`。按 category 扫描 `data/wandscape/<category>/*.json`。支持 `/reload` 热重载
- **SimpleDataRegistry\<T\>** — 泛型注册表：parser(String id, JsonElement) → T。按 category 隔离

## 注册方式

```java
// 在模块初始化时注册
dataLoader.register("buildings", BuildingConfig::fromJson);
dataLoader.register("wands", WandPreset::fromJson);
```

## 数据流

```
data/wandscape/<category>/*.json
  → WandscapeDataLoader.prepare() 按category扫描
  → apply() 按category分发到对应 SimpleDataRegistry
  → 各模块的 parser 将 JsonElement 转为强类型对象
```

## 依赖

- MC: SimpleJsonResourceReloadListener
- shared/registry/WandscapeDataRegistry
