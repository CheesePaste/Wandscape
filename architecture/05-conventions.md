# 编码约定与反模式

## 包可见性

```
com.wsteam.wandscape.<module>/
├── api/       → public    (对外接口，供 WandscapeApis 暴露)
├── block/     → public    (方块注册需要 public 构造器)
├── entity/    → public    (实体注册需要 public 构造器)
├── be/        → public    (BE 注册需要 public 构造器)
├── screen/    → public    (Screen 注册需要 public 构造器)
├── data/      → public    (JSON 反序列化的 record)
└── internal/  → package-private  (实现细节，不对外暴露)
```

**规则**：只有注册框架要求 public 的类才放 `api/` `block/` `entity/` `be/` `screen/` `data/` 包。其他实现全部放 `internal/` 并设为 package-private。

## API 设计

```
定义：01-shared-api 的 shared/api/ 包 → 纯接口
注册：各模块在 @Mod 构造器中 WandscapeApis.setXxxApi(new XxxApiImpl())
调用：WandscapeApis.getXxxApi().method()
测试：WandscapeApis.setXxxApi(mock) 替换为 mock 实现
```

**禁止**：`WandscapeApis` 之外任何地方 `new` 其他模块的实现类。

## NBT 规范

- **只用 MC 原生**：`CompoundTag`、`ListTag`、`StringTag` 等，不用第三方序列化库
- **做 key 前 copy**：`CompoundTag` 用于 HashMap key 时必须先 `.copy()`，防止外部修改破坏 hashCode
- **不可变 NBT**：传出 NBT 数据时 `return tag.copy()`，防止外部修改内部状态
- **常量 key**：NBT key 字符串定义为 `private static final String TAG_XXX = "xxx"`

## 注册规范

- 使用 `DeferredRegister`，在 `@Mod` 构造器中 `.register(modEventBus)`
- 命名：`public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID)`
- 注册 ID 用 snake_case，与 JSON 中的 id 一致
- BlockItem 随方块一起注册：`ITEMS.registerSimpleBlockItem("block_id", BLOCK_REF)`

## 事件规范

- 事件类在 `shared/event/` 定义，继承 `net.neoforged.bus.api.Event`
- 字段 `private final` + 构造器注入 + public getter
- 优先级统一 `EventPriority.NORMAL`（默认值，不写）
- 事件只用于通知/解耦，不用于编排执行顺序
- 需要返回值或顺序保证 → 用 API 直接调用

## 常量

- 可调参数 → `config/wandscape-common.toml`（ModConfigSpec）
- 不可调常量 → `WandscapeConstants`（在 01-shared-api）
- 各模块内部常量 → 模块的 `internal/` 包下 `XxxConstants`
- 绝对不硬编码到业务逻辑中

## 测试

- **单元测试**：纯逻辑（计算、匹配、校验）→ JVM 下直接测，不依赖 Minecraft 运行时
- **集成测试**：涉及世界/方块/实体 → `@GameTest` 或 `runGameTestServer`
- **测试 mock**：通过 `WandscapeApis.setXxxApi(mock)` 注入 mock，不用反射
- 每个模块至少覆盖：正常路径 + 边界条件（空值、负数、满载）

## 反模式（禁止）

| 反模式 | 后果 | 正确做法 |
|--------|------|---------|
| 跨模块直接 import | 编译错误 + 循环依赖风险 | 用 WandscapeApis 或 EventBus |
| 硬编码数值 | 调整需改代码 | 放 Constants 或 TOML |
| NBT 传出不 copy | 外部修改破坏内部状态 | `return tag.copy()` |
| 事件处理器做重 IO | 阻塞事件总线 | 异步或延迟处理 |
| 猜测 MC 类名 | 编译错误 | minecraft-source skill 查源码 |
| 静默 catch 不记录日志 | 问题不可追踪 | 至少 LOGGER.warn() |
| 单方块建筑不写 pattern | 结构验证分支代码 | 统一写 `[[0,0,0]]` |
| tick() 中做重计算 | 服务端卡顿 | 缓存结果，事件触发时重算 |

## 新增约定

1. 在本文件中追加
2. 如果是"禁止做 X"，加入反模式表格
3. 说明为什么（反模式的后果）
4. 给出正确做法

> **维护规则**：发现新反模式时添加到"反模式"表。团队达成新约定时在对应节追加。每条反模式必须包含后果和正确做法，让 AI 能判断边界情况。
