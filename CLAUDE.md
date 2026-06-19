# Wandscape — CLAUDE.md

Minecraft NeoForge 1.21.1 模组。殖民地自动化管理：NPC 法师通过法杖执行原子操作，建造建筑、采集元素、合成物品。

## 构建命令

```bash
./gradlew build          # 编译
./gradlew runClient      # 启动测试客户端
./gradlew runServer      # 启动测试服务端
./gradlew test           # 运行单元测试（含核心引擎 63 个测试）
./gradlew runGameTestServer  # 运行 GameTest
```

## 核心原则

所有代码和设计决策必须遵循这五条。违反任一条需在 PR 中说明理由。

1. **高兼容性**：不修改原版行为，不硬编码方块/物品引用。功能通过 JSON 数据驱动，方块映射用标签。
2. **原子化设计**：每个模块只做一件事。模块间通过接口 + 事件通信，不跨模块直接引用类。复杂功能拆解为原子操作序列。
3. **轻度不硬核**：不引入生存难度惩罚。关停是效率降级而非建筑损坏。数值门槛低、递增平缓。
4. **稳定性优先**：所有可能失败的路径必须有兜底（寻路失败→传送、魔力不足→等待、元素不足→物资等待）。不允许静默失败或崩溃。
5. **文档即代码**：修改设计必须同步更新 `docs/` 中对应编号的文档。新增包/注册/事件/JSON格式 必须同步更新 `architecture/` 中对应文件。

## 项目导航

| 目录 | 用途 | 何时查阅 |
|------|------|---------|
| `docs/` | 模块设计文档（00-21），每模块一文件 + 路线图(17) + 已解决存档(98) + 待澄清(99) | 开始写某个模块的代码前 |
| `architecture/` | 项目结构快照：包树、依赖图、注册表、事件、JSON格式 | 需要知道"某样东西在哪"或"某事件谁发谁听" |
| `src/` | 实际 Java 代码 | 实现时 |

**docs/ 和 architecture/ 的分工**：docs/ 描述"应该做成什么样"（设计意图、行为规则），architecture/ 描述"现在是什么样"（包结构、注册位置、事件流向）。前者是计划书，后者是快照。

## 工作流

### 写代码前

1. **读 docs/**：找到对应模块的编号文档，理解设计意图和边界
2. **读 `docs/17-development-roadmap.md`**：确认当前模块处于哪个阶段、依赖哪些前置模块
3. **读 architecture/**：确认包位置、依赖规则、已有注册和事件
4. **查 MC 源码**：涉及原版类/方法时，用 `minecraft-source` skill 查源码，不靠记忆猜测
5. **扫一眼 `docs/99-open-questions.md`**：确认有无已记录的坑
6. **查 `docs/98-resolved-issues.md`**：确认某个设计是否已有定论

### 写代码时

- 新增接口 → 放 `01-shared-api`
- 新增事件 → 在 `01-shared-api` 定义，在 architecture/03-event-catalog.md 登记
- 新增注册 → 在 architecture/02-registration-catalog.md 登记
- 新增 JSON 格式 → 在 architecture/04-json-config-index.md 登记
- 改变模块间交互 → 更新 architecture/01-module-dependencies.md
- 所有可配置内容走 JSON（`data/wandscape/`），不硬编码

### 写完后

- 修改了设计 → 更新对应 `docs/NN-*.md`
- 新增包/注册/事件/JSON → 更新 `architecture/` 对应文件
- 解决了 `docs/99-open-questions.md` 中的问题 → 移到 `docs/98-resolved-issues.md`

## MC 源码查阅

**绝对不要臆断 Minecraft 类名和方法签名。** 涉及以下情况必须用 `minecraft-source` skill 查源码：

- 原版类名、方法名、字段名
- 原版行为逻辑（如村民 AI、寻路、方块实体 tick）
- NeoForge 事件/API 用法
- 原版 NBT 结构

用法：`/minecraft-source <类名或方法名或关键词>`

## 架构

### 核心引擎（`org.magiccolony.core`）— v2 已迁移

2026-06-19 从 MagicColony 独立项目迁移。纯 Java 21 标准库，**与 Minecraft 零依赖**。适配层通过 `boundary/` 接口对接 MC 世界。

关键设计文档：`docs/19-engine-v1-baseline.md`、`docs/20-engine-v2-incremental.md`、`docs/21-engine-architecture-overview.md`

引擎提供：
- **ECS 骨架**：`World` + `System` 有序 tick + `ComponentStore` + `query()` 交集查询
- **Blueprint → TaskSequence**：声明式蓝图编译为原子操作序列，支持事件触发链
- **AtomicOp sealed 层级**（7 种）：`TransformOp / BlockInteractOp / EntityInteractOp / RitualOp / ResourceRequestOp / EmitEventOp / IfConditionOp`
- **纯/副作用 Op 批处理**：纯 Op（EmitEventOp、IfConditionOp）在一个 tick 内连续执行直到撞到副作用 Op
- **事件驱动编排**：`TriggerDeclaration` 声明"任务 X 完成后 source 任务 Y"，`EventBus` 延迟 unsubscribe 保证事件不丢失
- **双层幂等**：EventBus 同 tick 合并 + `dedupKey` 跨 tick 去重
- **System 蓝图**：`SystemBlueprintRegistry` + `SystemBlueprintSystem`，永久 subscribe 基础设施事件
- **条件控制**：`IfConditionOp` + `ConditionEvaluator` 注册表（`resource_below` / `inventory_has` / `inventory_full`）
- **模板变量**：`{{taskId}}`、`{{npcId}}`、`{{task.params.<key>}}`、`{{pos.x/y/z}}`、`{{event.<key>}}`
- **引擎引导**：`Engine.bootstrap(EngineConfig)` → `World`，注入边界实现
- **测试**：183 个 JUnit 5 测试 (63 核心引擎 + 120 适配层: AbilitySetTest 18 + BehaviorTypeTest 13 + ElementTypeTest 12 + ElementMappingConfigTest 12 + WandPresetLoaderTest 10 + WandDataValidatorTest 17 + TypeBridgeTest 19 + ElementApiImplTest 10 + SimpleDataRegistryTest 9)

适配层需要实现 5 个边界接口：`BlockOps`、`EntityOps`、`RitualOps`、`ColonyResourceAccess`、`EventBus`（已有 `SimpleEventBus` 可用）。`MockBoundary` 提供所有接口的 headless mock 实现。

引擎包结构：`org.magiccolony.core.{boundary,component,ecs,event,op,system,task,types}` + `org.magiccolony.demo.MockBoundary`

### Wandscape 模块依赖规则（最高优先级）

```
01-shared-api  ←  所有模块都可以依赖
08-building-core  ←  建筑类模块可选依赖（自身仅依赖 01）
02-07, 09-16  ←  互不直接引用，通过 EventBus 事件 + API 接口通信
```

违反此规则的代码不得合并。

### 模块速查

| 编号 | 模块 | 职责 |
|------|------|------|
| — | core-engine | 已迁移：ECS + Blueprint + Task + EventBus（v2） |
| 01 | shared-api | 接口、事件、枚举、常量 — 纯定义无实现 |
| 02 | wand-system | 法杖物品 + NBT 行为标签 + 能力并集 |
| 03 | element-system | 三层 9 元素 + 方块→元素映射 |
| 04 | warehouse-system | 元素+物品存储 + GUI |
| 05 | atomic-operations | 原子操作 A/B/C/D 执行 |
| 06 | task-system | 全局任务池 + 2s 调度器 + NPC 私有池 |
| 07 | npc-system | NPC 实体 + 魔力 + 死亡/复活 |
| 08 | building-core | 建筑 JSON 注册 + 三数值 + 维护 + 队列 |
| 09 | node-building | 节点建筑产出元素 |
| 10 | production-stations | 制作站 + 工作站 + 魔药站 |
| 11 | housing-mana-pool | 房屋分配 + 魔力池 |
| 12 | tavern-recruitment | 酒馆招募 NPC |
| 13 | ritual-altar | 多方块祭坛 + 复活仪式 |
| 14 | management-panel | 远程管理面板 + 小地图 |
| 15 | colony-lifecycle | 殖民地创建/删除 + 引导 |
| 16 | data-driven-config | JSON 配置格式规范 |

### 关键设计决策

- **全 NPC 默认 ritual:1**：与装备无关，`computeAbilities()` 自动合并。保证所有 NPC 可执行物资传送。
- **维护成本负数→自动关停**：元素储量扣至负数后建筑关停（时间加倍、产出减半），玩家补足后手动重启。
- **NPC 移动**：<64 方块尝试寻路步行（3s×3 次卡死检测），≥64/寻路失败/卡死 → 私有 self_teleport（Operation D）。魔力不足原地等恢复。
- **操作射程**：`16 + wand.range × 8` 方块。超距自动传送后执行。
- **连续执行**：NPC 完成建筑任务后可直接接同建筑下一个任务（跳过全局匹配），接取前预判魔力。
- **三数值永久标记**：每种建筑首次建造贡献数值，拆除重建不重复。
- **事件通信**：模块间广播用 EventBus（默认 NORMAL 优先级），编排顺序用 API 直接调用。
- **不洗 NBT**：完全使用 MC 原生序列化。`CompoundTag.copy()` 防外部修改。

### 文档

- 设计文档在 `docs/`，编号 00 到 18（Wandscape 模块设计）+ 19-21（核心引擎）。99 为审查问题汇总。
- 核心引擎文档：`docs/19-engine-v1-baseline.md`（V1 基线）、`docs/20-engine-v2-incremental.md`（V2 增量）、`docs/21-engine-architecture-overview.md`（架构总览）

## 代码约定

- 包名 `com.wsteam.wandscape`（Wandscape 模块） / `org.magiccolony.core`（核心引擎）
- 模块对外接口在 `01-shared-api`，实现类 `internal` 可见性
- 核心引擎文件从 MagicColony 迁移，保持原包名 `org.magiccolony.core`，不修改
- NeoForge `DeferredRegister` 注册物品/方块/实体/BE/菜单
- `/reload` 热重载所有 JSON 配置
- JSON 目录：`data/wandscape/`（六类：wands / buildings / recipes / element_mappings / rituals / multiblocks）
- TOML 配置：`config/wandscape-common.toml`，ModConfigSpec 实现，服务端同步客户端

## Testing

### 测试组织

- 核心引擎测试：`src/test/java/com/wsteam/wandscape/core/` - JUnit 5, 零 MC 依赖
- 适配层单元测试：`src/test/java/com/wsteam/wandscape/<module>/internal/` - 仅纯逻辑，不依赖 MC 运行时
- 包含 MC 运行时依赖的集成测试未来使用 `@GameTest` 或 `runGameTestServer`

### 测试要求

- **所有纯逻辑代码必须有单元测试**。纯逻辑定义：不依赖 `Minecraft.getInstance()`、`ItemStack`、`BlockState`、`Level`、`ServerPlayer` 或任何需要 MC 运行时的类
- 测试类命名 `<ClassName>Test`，放在与被测类相同的包路径下（`src/test/java/` 镜像 `src/main/java/`）
- 每个测试方法覆盖一个行为分支：正常路径、边界条件（null/空/负数）、错误路径
- 使用纯 JUnit 5：`@Test`、`@BeforeEach`、`@Nested`、`static import org.junit.jupiter.api.Assertions.*`
- 不需要 Mockito 或 AssertJ — 项目遵循最小依赖原则
- `CompoundTag` 可独立构造（`new CompoundTag()`）无需 MC 运行时
- `./gradlew test` 必须全绿才允许提交

### 应该测什么

- 枚举方法（`fromId`, `getId`, `getTier`）
- 数据校验逻辑（`WandDataValidator.isValid`）
- JSON 解析逻辑（`Xxx.fromJson` 静态方法）
- 并集计算逻辑（`AbilitySet.merge`）
- 类型桥接映射（`TypeBridge` 双向转换）
- 注册表 CRUD（`SimpleDataRegistry` get/contains/clear）

### 不测什么

- 任何接收 `ItemStack` 参数的方法 → 需要 `DataComponents`，留待集成测试
- 任何接收 `BlockState` 参数的方法 → 需要 `BuiltInRegistries`，留待集成测试
- 任何继承 MC 构造器的类（`SimpleJsonResourceReloadListener` 等）
- 渲染、GUI、网络包处理

## architecture/ 文件维护规则

architecture/ 是项目结构的**实时快照**，不是设计文档。每条信息必须有代码对应。

### 何时更新

| 变更 | 更新文件 |
|------|---------|
| 新增/删除/重命名包 | 00-overview.md |
| 模块间依赖关系变化 | 01-module-dependencies.md |
| 新增 DeferredRegister 或注册项 | 02-registration-catalog.md |
| 新增事件类、改变事件发布/订阅方 | 03-event-catalog.md |
| 新增/修改 JSON 配置路径或格式 | 04-json-config-index.md |
| 新增编码约定或发现反模式 | 05-conventions.md |

### 编写格式

- **包路径**：用 `com.wsteam.wandscape.<module>/` 格式，后跟一句话说明
- **注册项**：用 `DeferredRegister<Type> NAME` 格式，标注所在模块
- **事件**：用表格：事件类 → 发布模块 → 订阅模块 → 触发时机
- **JSON 路径**：用 `data/wandscape/<category>/<file>.json` 格式，标注加载模块
- **不写类名**：architecture/ 记录包级结构。具体类名在 `docs/` 或代码注释中
- **保持精简**：每个文件不超过 150 行。超过则拆分子文件

## 常见陷阱

1. **直接 new 其他模块的类** → 改用 `WandscapeApis` 静态 API 查询
2. **硬编码数值** → 放 `WandscapeConstants` 或 TOML 配置
3. **忘记 copy NBT** → 用 `CompoundTag` 做 Map key 前必须 `copy()`
4. **事件优先级依赖** → 事件仅用于通知，不用于编排顺序。需顺序用 API
5. **建筑不写 pattern** → 单方块建筑也写 `"pattern": [[0,0,0]]`，统一起见
6. **猜测 MC 类名** → 用 `minecraft-source` skill 查源码
