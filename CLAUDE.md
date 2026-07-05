你是一名资深的mc模组开发者，开发了模拟殖民地等知名模组，你正在开发 Minecraft NeoForge 1.21.1 模组。

**两大系统**：
1. **殖民地自动化**：NPC 法师通过法杖执行原子操作，建造建筑、采集元素、合成物品。
2. **模拟经营（游客经济）**：短居游客沿道路入城，交互商店/服务建筑，产生元素利润循环。设计文档：`docs/jingying.md`（游客经济与商业系统）、`docs/simulation.md`（模拟经营系统设计）。

## 构建命令

```bash
./gradlew build          # 编译
./gradlew test           # 运行单元测试
./gradlew runGameTestServer  # 运行 GameTest
```

**首次运行或 runClient 报错 `clientRunVmArgs.txt` 不存在时**，先执行：

```bash
./gradlew neoForgeIdeSync   # 生成 IDE 运行配置文件（含 VmArgs）
```

## 核心原则

1. **高兼容性**：不修改原版行为，不硬编码方块/物品引用。功能通过 JSON 数据驱动，方块映射用标签。
2. **原子化设计**：每个模块只做一件事。模块间通过接口 + 事件通信，不跨模块直接引用类。
3. **轻度不硬核**：不引入生存难度惩罚。关停是效率降级而非建筑损坏。
4. **稳定性优先**：所有可能失败的路径必须有兜底。不允许静默失败或崩溃。
5. **文档即代码**：修改结构同步更新 `architecture/packages/`，新增 JSON 格式同步更新 `architecture/data/`，修改设计同步更新 `docs/`。
6. **引擎是请求层，适配层是实现**：`core/` 禁止引入 MC 类，禁止持有运行时状态。MC 实现放 `engine/` 或各模块 `internal/`。
7. **使用模组的Log**：使用shared/log/Log.java，方便后面批量隐藏。
8. **禁止使用**: ./gradlew runClient
9. **做事情必须先阅读**: architecture/README.md，可以便于知道功能在哪一块
## 项目导航

| 目录 | 用途 | 何时查阅 |
|------|------|---------|
| `architecture/README.md` | **入口**：包地图 + 数据流 + 依赖规则 | 开始任何工作前 |
| `architecture/packages/` | 每个包的事实参考（类、职责、依赖） | 需要了解某包时 |
| `architecture/data/` | JSON 数据格式参考（建筑、蓝图 DSL） | 写/改 JSON 配置时 |
| `architecture/conventions.md` | 编码规范 + 反模式 | 写代码时 |
| `docs/decisions.md` | 设计决策日志（非显而易见的选择及其理由） | 需要理解"为什么"时 |
| `docs/roadmap.md` | 当前阶段 + 下一步 + 未实现 API 列表 | 需要了解进度时 |
| `docs/gaps.md` | 已知问题 + 代码审查发现 + 后续待办 | 排查问题或规划时 |
| `docs/jingying.md` | 游客经济与商业系统完整设计 | 写/改游客相关代码时 |
| `docs/simulation.md` | 模拟经营系统设计（游客、商店、装饰、奇观、维护费） | 理解经营机制时 |
| `src/` | Java 代码 | 实现时 |

**architecture/ = 真相（事实），docs/ = 推理（为什么）。两者不重复。**

## 子代理使用

- **先拆解，再委派**：用户提出问题后，先分析任务可拆分哪些独立子任务，再决定是否需要并行委派。禁止用户一问就直接扔给子代理。
- **并行优先**：互不依赖的子任务用多个 Agent 同时跑，缩短墙钟时间。
- **按需使用**：单文件查找、简单读写、一行修改 — 自己动手。只有多文件扫荡、跨模块搜索、独立研究等有并行收益时才委派。
- **委派时给足上下文**：prompt 里写明要找什么、边界在哪、返回格式要求，避免子代理空转。

## 工作流

**需求澄清前不写代码**：用户提出设计/实现问题时，先用 `grill-me` skill 反复追问直到需求明确、决策树每个分支都敲定，再进入写代码阶段。禁止需求模糊时直接动手写实现。

**写代码前**：读 `architecture/README.md` → 读对应 `architecture/packages/<package>.md` → 读 `docs/roadmap.md` 确认阶段 → 涉及游客时读 `docs/jingying.md` 和 `docs/simulation.md` → 用 `minecraft-source` skill 查 MC 源码

**写代码时**：新接口 → `shared/api/`，新事件 → `shared/event/`，新注册 → 更新对应 package 文件，新 JSON → 登记 `architecture/data/`

**写完后**：改设计 → 更新 `docs/decisions.md`，改结构 → 更新对应 `architecture/packages/`，发现问题 → 记录到 `docs/gaps.md`

## MC 源码查阅

涉及原版类名/方法/行为/NBT/NeoForge API 时，必须用 `minecraft-source` skill 查源码，不靠记忆猜测。

## 模块依赖规则

```
shared/  ←  所有包可见（API接口 + 事件 + 数据类型）
engine/  ←  MC 适配实现，实现 core 边界接口
building/wand/element/npc/warehouse/production/tourist/
projection/road/stats/task/standalone/imgui  ←  通过 WandscapeApis + EventBus 通信，互不直接引用
core/  ←  所有包可见，纯 Java 21 零 MC 依赖。不依赖 shared/
```

**注意：** `equipment` 是 cross-cutting 关注点（核心类型在 `core/types/` + `core/component/`，桥接在 `npc/internal/`），非独立包。`road/` 有配套 `core/road/`（纯数据）和 `engine/road/`（MC 实现）。

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
9. **游客 ≠ 常驻市民** → 旧 Citizen 系统已完全移除（CitizenManager/Profession/StoredCitizen/CitizenMoveGoal 等）。游客是短居访客，无职业/床位/工作场所/住宅/状态机。所有游客行为由 `tourist/` 包内的 TouristSpawnSystem + TouristMoveGoal + HotelStayHandler 驱动。`TouristState`（枚举：VISITING/EXPLORING/WANDERING/IDLE/SLEEPING）是当前游客系统内的移动状态标记，不是状态机——禁止扩展为带迁移逻辑的复杂状态机。禁止向 TouristEntity 添加任何常驻市民概念（Profession/Bed/Workplace/Home/StoredCitizen/ComplexStateMachine）。
