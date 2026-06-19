# 08 — 编码约定 + 反模式

## 包可见性

```
module/
├── block/ / entity/ / be/ / screen/ → public（注册框架要求）
├── data/                            → public（JSON 反序列化 record）
├── api/                             → public（对外接口）
└── internal/                        → package-private（实现细节）
```

## API 模式

- **定义**：`shared/api/XxxApi.java` 纯接口
- **实现**：`<module>/internal/XxxApiImpl.java` package-private
- **注册**：`WandscapeApis.setXxxApi(new XxxApiImpl())`
- **调用**：`WandscapeApis.getXxxApi().method()`

禁止 `WandscapeApis` 之外 `new` 其他模块实现类。

## NBT 规范

- 只用 MC 原生 `CompoundTag` / `ListTag`，不用第三方序列化
- 传出前 `tag.copy()` 防外部修改
- NBT key 定义为 `private static final String TAG_XXX = "xxx"`

## 注册规范

- `DeferredRegister` 在 `@Mod` 构造器 `.register(modEventBus)`
- 命名 `snake_case`，与 JSON id 一致
- BlockItem 随方块一起注册

## 事件规范

- 事件类在 `shared/event/`，继承 `net.neoforged.bus.api.Event`
- 字段 `private final` + 构造器注入 + getter
- 优先级 `EventPriority.NORMAL`（默认）
- 事件仅通知/解耦，需顺序→用 API

## 反模式（禁止）

| 反模式 | 后果 | 正确做法 |
|--------|------|---------|
| 跨模块直接 import 类 | 循环依赖 | WandscapeApis 或 EventBus |
| 硬编码数值 | 改需改代码 | Constants 或 TOML |
| NBT 传出不 copy | 内部状态被外部破坏 | `return tag.copy()` |
| 事件处理器做重 IO | 阻塞事件总线 | 异步处理 |
| 猜测 MC 类名 | 编译错误 | `minecraft-source` skill |
| 静默 catch | 问题不可追踪 | 至少 `LOGGER.warn()` |
| BE 直接调 Engine/World/taskPool | 跨层耦合 | BuildingTaskSource 是唯一入口 |
| 另起炉灶任务分发 | 绕过 Scheduler/ECS/EventBus | TaskRequest → GlobalTaskPool → SchedulerSystem |
| 模块绕过引擎直接 new World | 多 World 实例并存 | WandscapeEngine 单例，ServerStarting 时 bootstrap 一次 |
| 单方块建筑不写 pattern | 结构验证分支代码 | 统一写 `[[0,0,0]]` |

## 常量

- 可调参数 → `config/wandscape-common.toml`
- 不可调常量 → `WandscapeConstants`
- 模块内部常量 → `<module>/internal/XxxConstants`
