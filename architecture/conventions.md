# 编码规范与反模式

## 包可见性

```
module/
├── data/       → public（JSON反序列化record）
├── api/        → public（对外接口）
├── internal/   → package-private（实现细节）
├── block/entity/be/screen/ → public（注册框架要求）
└── client/     → package-private（客户端渲染）
```

## API 模式

- **定义**：`shared/api/XxxApi.java` 纯接口
- **实现**：`<module>/internal/XxxApiImpl.java` package-private
- **注册**：`WandscapeApis.setXxxApi(new XxxApiImpl())` 在 `Wandscape` 构造器或 `commonSetup`
- **调用**：`WandscapeApis.getXxxApi().method()`

## NBT 规范

- 只用 MC 原生 `CompoundTag` / `ListTag`
- 传出前 `tag.copy()` 防外部修改
- key 定义为 `private static final String TAG_XXX = "xxx"`

## 事件规范

- 事件类在 `shared/event/`，继承 `net.neoforged.bus.api.Event`
- 字段 `private final` + 构造器注入 + getter
- 优先级默认 `EventPriority.NORMAL`
- 事件仅通知/解耦，需顺序→用 API

## 注册规范

- `DeferredRegister` 在 `@Mod` 构造器 `.register(modEventBus)`
- 命名 `snake_case`，与 JSON id 一致


## 反模式（禁止）

| 反模式 | 后果 | 正确做法 |
|--------|------|---------|
| 跨模块直接 import 类 | 循环依赖 | WandscapeApis 或 EventBus |
| 硬编码数值 | 改需改代码 | Config.java TOML 配置 |
| NBT 传出不 copy | 内部状态被外部破坏 | `return tag.copy()` |
| 静默 catch | 问题不可追踪 | 至少 `LOGGER.warn()` |
| BE 直接调 Engine/World/taskPool | 跨层耦合 | BuildingTaskSource 是唯一入口 |
| 另起炉灶任务分发 | 绕过ECS/EventBus | TaskRequest → GlobalTaskPool → SchedulerSystem |
| 模块绕过引擎直接 new World | 多World实例并存 | WandscapeEngine 单例 |
| 单方块建筑不写 pattern | 结构验证分支代码 | 统一写 `[[0,0,0]]` |

## 配置来源优先级

1. `Config.java` (TOML) — 用户可改的可调参数
2. `WandscapeConstants.java` — 代码内部默认值（Config未设置时fallback）
3. JSON 数据文件 — 数据驱动内容（建筑/法杖/蓝图/配方/元素映射）
