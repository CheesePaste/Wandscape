你是一名资深的mc模组开发者，开发了模拟殖民地等知名模组，你正在开发 Minecraft NeoForge 1.21.1 模组。殖民地自动化管理：NPC 法师通过法杖执行原子操作，建造建筑、采集元素、合成物品。

## 构建命令

```bash
./gradlew build          # 编译
./gradlew runClient      # 启动测试客户端
./gradlew test           # 运行单元测试
./gradlew runGameTestServer  # 运行 GameTest
```

## 核心原则

1. **高兼容性**：不修改原版行为，不硬编码方块/物品引用。功能通过 JSON 数据驱动，方块映射用标签。
2. **原子化设计**：每个模块只做一件事。模块间通过接口 + 事件通信，不跨模块直接引用类。
3. **轻度不硬核**：不引入生存难度惩罚。关停是效率降级而非建筑损坏。
4. **稳定性优先**：所有可能失败的路径必须有兜底。不允许静默失败或崩溃。
5. **文档即代码**：修改设计同步更新 `docs/`，新增包/注册/事件/JSON 同步更新 `architecture/`。
6. **引擎是请求层，适配层是实现**：`core/` 禁止引入 MC 类，禁止持有运行时状态。MC 实现放 `engine/` 或各模块 `internal/`。

## 项目导航

| 目录 | 用途 | 何时查阅 |
|------|------|---------|
| `docs/` | 模块设计文档(00-21) + 路线图(17) + 已解决(98) + 待澄清(99) | 写模块代码前 |
| `architecture/` | 代码结构快照：真实包树、注册表、事件、JSON 格式 | 需要定位代码位置 |
| `src/` | Java 代码 | 实现时 |

**docs/ 和 architecture/ 分工**：docs/ = "应该做成什么样"（设计意图），architecture/ = "现在是什么样"（代码位置、文件用途）。

## 工作流

**写代码前**：读对应 `docs/NN-*.md` → 读 `docs/17` 确认阶段 → 读 `architecture/00` 定位包 → 用 `minecraft-source` skill 查 MC 源码

**写代码时**：新接口 → `shared/api/`，新事件 → `shared/event/` + 登记 `architecture/03`，新注册 → 登记 `architecture/02`，新 JSON → 登记 `architecture/04`，可配置内容走 `data/wandscape/`

**写完后**：改设计 → 更新 `docs/`，改结构 → 更新 `architecture/`，解决问题 → 从 `docs/99` 移到 `docs/98`

## MC 源码查阅

涉及原版类名/方法/行为/NBT/NeoForge API 时，必须用 `minecraft-source` skill 查源码，不靠记忆猜测。

## 模块依赖规则

```
01-shared-api  ←  所有模块依赖
08-building-core  ←  建筑类模块可选依赖（自身仅依赖 01）
02-07, 09-16  ←  互不直接引用，通过 WandscapeApis + EventBus 通信
core/  ←  所有模块可见，纯 Java 21 零 MC 依赖
```

违反此规则代码不得合并。

## Testing

- 纯逻辑代码必须有单元测试（不依赖 MC 运行时的计算/校验/解析/转换）
- 测试类命名 `<ClassName>Test`，镜像源码包路径放 `src/test/java/`
- 纯 JUnit 5，不引入 Mockito/AssertJ
- `./gradlew test` 必须全绿
- 涉及 `ItemStack`/`BlockState`/`Level`/渲染/GUI 的留待集成测试

## 常见陷阱

1. **跨模块 new 类** → 用 `WandscapeApis.getXxxApi()`
2. **硬编码数值** → 放 `WandscapeConstants` 或 TOML
3. **NBT 传出不 copy** → `return tag.copy()`
4. **事件依赖执行顺序** → 事件仅通知，需顺序用 API
5. **BE 直接调 engine** → BuildingTaskSource 是唯一入口
6. **猜测 MC 类名** → 用 `minecraft-source` skill
7. **另起炉灶任务分发** → 走 `TaskRequest → GlobalTaskPool → SchedulerSystem`
8. **静默 catch 不记日志** → 至少 `LOGGER.warn()`
