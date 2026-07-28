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
10. **先问图谱，再读文档**：结构查询（类定义/调用链/位置）优先用 codebase-memory-mcp 的 `search_graph`、`trace_path`、`get_code_snippet`，只有数据流/业务规则/设计意图才读架构文档
## 项目导航

| 目录 / 工具 | 用途 | 何时查阅 |
|-------------|------|---------|
| `architecture/README.md` | **入口**：包地图 + 数据流 + 依赖规则 | 开始任何工作前 |
| **codebase-memory-mcp**（`search_graph`/`trace_path`/`get_code_snippet`） | 知识图谱——查类定义、调用链、方法签名、文件位置 | 需要结构信息时**优先使用** |
| `architecture/packages/` | 每个包的设计意图（数据流、业务规则、决策理由） | packages 已精简60%，**只在需要设计上下文时阅读** |
| `architecture/data/` | JSON 数据格式参考（建筑、蓝图 DSL） | 写/改 JSON 配置时 |
| `architecture/conventions.md` | 编码规范 + 反模式 | 写代码时 |
| `docs/decisions.md` | 设计决策日志（非显而易见的选择及其理由） | 需要理解"为什么"时 |
| `docs/roadmap.md` | 当前阶段 + 下一步 + 未实现 API 列表 | 需要了解进度时 |
| `docs/gaps.md` | 已知问题 + 代码审查发现 + 后续待办 | 排查问题或规划时 |
| `docs/jingying.md` | 游客经济与商业系统完整设计 | 写/改游客相关代码时 |
| `docs/simulation.md` | 模拟经营系统设计（游客、商店、装饰、奇观、维护费） | 理解经营机制时 |
| `src/` | Java 代码 | 实现时 |

**architecture/ = 真相（事实），docs/ = 推理（为什么）。两者不重复。** packages/ 配合 codebase-memory-mcp 图谱使用：图谱查结构，文档查设计。

## 目录速查

```
docs/
├── jingying.md              # 游客经济与商业系统完整设计
├── simulation.md            # 模拟经营系统（游客/商店/装饰/奇观/维护费）
├── roadmap.md               # 当前阶段 + 下一步 + 未实现 API 列表
├── decisions.md             # 设计决策日志（非显而易见的选择及理由）
├── gaps.md                  # 已知问题 + 代码审查发现 + 后续待办
├── building-editor.md       # 建筑编辑器设计
├── building-3d-preview.md   # 建筑 3D 预览
├── overview_mode.md         # 俯瞰（鸟瞰）模式设计
├── spline_road_editor.md    # 样条道路编辑器
├── roadImprovement.md       # 道路改进方案
├── elemental_affinity.md    # 元素亲和系统
├── event-task.md            # 事件驱动任务
├── micro_interactions.md    # 微交互设计
├── narrativeGeneration.md   # 叙事生成
├── tourist_level_colony_exp.md # 游客等级与殖民地经验
├── transport.md             # 物资运输
├── ui_design_rts.md         # RTS 风格 UI 设计
├── handover-element-icons.md # 元素图标设计交接文档
└── plan/                    # 实现计划

architecture/
├── README.md                # 入口：包地图 + 数据流 + 依赖规则
├── conventions.md           # 编码规范 + 反模式
├── packages/                # 每个包的设计意图（数据流/业务规则/决策理由）
│   ├── building.md          # 建筑系统
│   ├── wand.md              # 法杖系统
│   ├── element.md           # 元素系统
│   ├── npc.md               # NPC 系统
│   ├── warehouse.md         # 仓库系统
│   ├── production.md        # 生产/合成系统
│   ├── tourist.md           # 游客系统
│   ├── road.md              # 道路系统
│   ├── projection.md        # 投影/蓝图系统
│   ├── blueprint_editor.md  # 蓝图编辑器
│   ├── stats.md             # 统计系统
│   ├── task.md              # 任务系统
│   ├── engine.md            # MC 桥接层
│   ├── core.md              # 纯 Java 引擎核心
│   ├── shared.md            # 公共 API + 事件 + UI（面板/覆盖层/主题）
│   ├── overview.md          # 俯瞰模式
│   ├── imgui.md             # ImGui 调试界面
│   ├── standalone.md        # 独立模块
│   ├── command.md           # 调试命令
│   ├── dataconfig.md        # 数据配置
│   ├── equipment.md         # 装备系统（cross-cutting）
│   └── op.md                # 原子操作
└── data/                    # JSON 数据格式参考
    ├── blueprints.md        # 蓝图 DSL
    ├── buildings.md         # 建筑配置
    └── spline_template.md   # 样条模板
```

## codebase-memory-mcp 图谱速查

项目已索引（22,450 节点，52,481 边）。结构查询优先用 MCP 工具：

| 场景 | 工具 | 示例 |
|------|------|------|
| 找类/接口定义 | `search_graph query="BuildingConfig"` | 返回定义位置+方法列表 |
| 找方法/字段 | `search_graph query="EnqueueHelper"` | 返回类+所有方法 |
| 读源码 | `get_code_snippet qualified_name="...BuildingConfig"` | 返回完整源码 |
| 查调用链 | `trace_path "EnqueueHelper.enqueue" direction="outbound"` | 谁调用谁 |
| 查实现关系 | `search_graph query="implements BuildingApi"` | 找实现类 |
| 查包结构 | `get_architecture` | 总览+热度+社区检测 |

> 读 packages/*.md 前先跑 `search_graph`，可能连打开都不需要。

> **大改后重新索引**：增删类/重命名/改结构后，图谱会过时。跑 `codebase-memory-mcp cli index_repository '{"repo_path":"."}'` 重新索引。

## 子代理使用

- **先拆解，再委派**：用户提出问题后，先分析任务可拆分哪些独立子任务，再决定是否需要并行委派。禁止用户一问就直接扔给子代理。
- **并行优先**：互不依赖的子任务用多个 Agent 同时跑，缩短墙钟时间。
- **按需使用**：单文件查找、简单读写、一行修改 — 自己动手。只有多文件扫荡、跨模块搜索、独立研究等有并行收益时才委派。
- **委派时给足上下文**：prompt 里写明要找什么、边界在哪、返回格式要求，避免子代理空转。

## 工作流

**需求澄清前不写代码**：用户提出设计/实现问题时，先用 `grill-me` skill 反复追问直到需求明确、决策树每个分支都敲定，再进入写代码阶段。禁止需求模糊时直接动手写实现。

**写代码前**：读 `architecture/README.md` → 用 `search_graph` 查对应包的关键类/接口 → 如需要业务规则或数据流上下文再读 `architecture/packages/<package>.md` → 读 `docs/roadmap.md` 确认阶段 → 涉及游客时读 `docs/jingying.md` 和 `docs/simulation.md` → 用 `minecraft-source` skill 查 MC 源码

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
