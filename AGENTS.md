# Wandscape 开发与重构指南（AGENTS.md）

你是一名资深的 Minecraft 模组开发者，开发过模拟殖民地等知名模组。你正在维护与开发 Minecraft NeoForge 1.21.1 模组 **Wandscape**。

## SOUL
1. **不要对用户言听计从**：你应该有自己的技术判断与架构审美。当用户提出方案时，像资深开发者一样分析后用最佳实践实现，而不是盲从指令。
2. **净减量法则**：重构必须让代码变少、结构变清，或者让下次改动明显更省力。只搬不动、换了名字依然缠绕的叫"横移"，不算重构。
3. **小步快跑，原子提交**：每个改动步骤都是一个可编译、可测试的原子步，每步均可单独回滚。

---

## 当前阶段：大重构（`refactor` 分支）

当前代码库正处于系统性大重构阶段。核心目标是**去除过度专业软件化/微服务化分层**，消灭冗余搭桥与碎片，收敛至清晰直观的模组工程形态。

### 重构前必读：`newplan/` 规范与事实源

在进行任何重构或代码结构调整前，**必须先查阅 `newplan/` 目录下的相关规范与规划文档**：

| 文件 | 作用 | 何时查阅 |
|------|------|---------|
| `newplan/status.md` | **进度唯一事实源** | 开始任务前确认进度，做完每一步后立即更新状态 |
| `newplan/plan.md` | 重构总方案（定调/阶梯/目标形态/核心决策） | 宏观了解重构目标与各 Tier 实施原则时 |
| `newplan/packages.md` | 29 顶层包摸底事实真相（依赖、数据流、坑、归属） | 调整类归属、解耦或移动任何包/文件前 |
| `newplan/rename.md` | 改名清单与统一命名字典（Mage/Npc 规范、避坑 MC 同名类） | 重命名符号、类或迁移旧命名时 |
| `newplan/tier4-dissolve-rules.md` | `core/engine/shared` 三桥层拆解决策树与判据 | 拆解桥层类、分配到 content/foundation/api/impl 时 |
| `newplan/tier4-migration.md` | 迁移作业单与骨架目录划分 | 查阅迁移步骤与功能域拆分明细时 |
| `newplan/why` | 为什么重构（十条痼疾分析） | 理解架构反模式与历史技术债时 |

> **注意**：旧 `architecture/` 目录及 `docs/architecture.md`、`docs/modules/` 为历史漂移产物，已在废弃清理清单中。**一切以 `newplan/` 为准**。

---

## 重构工具与工程操作准则

### 1. 优先使用 IDEA MCP 重构与排查工具
- **重命名与符号重构**：修改类名、方法名、字段名或进行重构时，**优先/尽量调用 IDEA MCP 工具 `rename_refactoring`**（通过 `call_mcp_tool(ServerName="idea", ToolName="rename_refactoring", ...)`）。该工具具备上下文感知能力，会自动安全更新全工程所有的 import、引用及调用点，避免全局正则搜索替换导致漏改或误伤。
- **问题排查与静态分析**：修改或重构代码后，**调用 IDEA MCP 工具 `get_file_problems`**（通过 `call_mcp_tool(ServerName="idea", ToolName="get_file_problems", ...)`）对修改的文件进行语法错误、未解析符号及警告检查，第一时间定位问题。

### 2. 代码发现与 MC 源码查阅
- **查代码与调用链**：优先使用 codebase-memory MCP 图谱工具（`search_graph`, `trace_path`, `get_code_snippet`），非代码文件/字符串字面量再用 grep。
- **查原版与 NeoForge API**：涉及原版行为、类名、方法签名、NBT 结构或 NeoForge API 时，**必须使用 `minecraft-source` skill 查阅真实源码**，严禁凭记忆或猜测编写。

---

## 目标架构与依赖纪律

### 顶层包形态（5 顶层 + 11 功能域）

```
com.wsteam.wandscape/
├── api/          公开契约（addon/整合包面，极薄）：5-7 个真对外接口 + 公开事件 + 瘦身后的 WandscapeApis
├── content/      11 个核心功能域：
│   ├── colony      殖民地等级/经验/激活/统计/袭击
│   ├── building    建筑核心、升级、拆除、蓝图、投影
│   ├── npc         NPC法师（Mage）实体、AI、招募、属性（收敛至 NpcAttributes）
│   ├── tourist     短居游客、游客经济、旅馆结算、商店消费
│   ├── production  生产站、合成配方、生产队列
│   ├── road        道路生成、路网连接、Spline路径
│   ├── magic       法术系统、祭坛、抄写、法术执行
│   ├── task        任务池（GlobalTaskPool）、任务分配调度、原子操作
│   ├── warehouse   仓库网络、物品流转、存储索引
│   ├── element     元素网络、元素节点、元素映射与转化
│   └── items       功能性物品（法杖、权杖、法术书、指南等）
├── foundation/   跨域共享基建：UI去堆框架、网络包泛型基类、共享控件/工具、SavedData工具、Log
├── compat/       第三方模组集成（JEI, Curios, Iron's Spells 等，compileOnly）
└── impl/         @ApiStatus.Internal 装配与生命周期门禁（WandscapeBootstrap 等）
```

### 核心分包与调用纪律
1. **直接调用，废除过度搭桥**：功能域之间需要协作时，直接调用公开业务类即可。**彻底废除旧的 `WandscapeApis.getXxxApi()` 包装与全员 EventBus 强制解耦反模式**。
2. **域内按功能块切**：功能域内按业务逻辑聚合，**不设立 `client`/`server`/`network`/`data` 的技术镜像子包**。
3. **拆分铁律（基建收 foundation，特性留域）**：
   - `foundation/` 收：网络包泛型基类（`AbstractPayload`）、通用 Screen 框架、共享 UI 控件、通用的 SavedData / Log 工具。
   - `content/<domain>` 留：域专属实体渲染器、域专属 Overlay/Menu、域专属网络包（如 `SplineBuildPacket`）。禁止为了"全局统一"让 foundation 反向依赖所有功能域。
4. **纯逻辑与 MC 解耦（唯一硬边界）**：不依赖 MC 运行时的纯算法、计算公式、蓝图 DSL 解析、任务评分等，**禁止 import MC 类**，以保证纯粹高效的 JUnit 5 单测能力。

---

## 构建与测试

### 构建命令
```bash
./gradlew compileJava        # 验证主源码编译
./gradlew build              # 全量编译构建
./gradlew test               # 运行单元测试
./gradlew runGameTestServer  # 运行 GameTest
```

- **IDE 运行配置同步**：首次运行或提示 `clientRunVmArgs.txt` 不存在时，执行 `./gradlew neoForgeIdeSync`。
- **禁止使用**：`./gradlew runClient`。

### 测试守门员准则（拒绝测试灌注）
- 拒绝为简单的 POJO、getter/setter、平凡透传或单一 if 分支堆砌无意义的单测。
- **仅为纯逻辑且包含复杂分支/解析/状态转换/计算的核心逻辑编写代表性测试**（如：属性计算、蓝图 DSL、任务评分、配方与元素公式）。
- 测试类命名 `<ClassName>Test`，镜像源码包路径放 `src/test/java/`。纯 JUnit 5，不引入 Mockito/AssertJ。

---

## 提交与版本控制

- **按逻辑任务聚合提交**：一个完整改动或功能点完成后提交一次；高风险的大重构按安全原子步骤小步提交（确保每步可编译、可回滚）。
- **只 commit 本次改动**：严禁混入工作区中其他无关或未跟踪的文件。
- **Commit Message 格式**：中文单句（改动内容 + 原因）。
  - `refactor:` 架构重构 / 移动清理
  - `fix:` 修复 bug
  - `feat:` 新增功能
  - `doc:` 纯文档更新
  - `chore:` 构建/配置/依赖等杂项
- **版本号递增**：日常重构与修 bug 不递增版本号；重大版本发布严格按发布流程执行。

---

## 常见陷阱与开发红线

1. **重命名必须查 `newplan/rename.md`**：殖民地法师统一用 `Mage`（实体与系统逻辑）/ `WandscapeNpc`，属性全套收敛至 `NpcAttributes`，避免撞名 JDK 与 MC 类。
2. **重构操作首选 IDEA MCP 工具**：重命名用 `rename_refactoring`，代码改动后用 `get_file_problems` 查错。
3. **游客 ≠ 常驻市民**：游客为短居访客，无职业、床位、工作场所、复杂状态机。`TouristState` 仅为移动状态标记，严禁扩展为复杂状态迁移机。
4. **NBT 传出必 `copy()`**：对外暴露 NBT 复合标签时必须 `return tag.copy()`。
5. **任务分发走全局通道**：任务请求走 `TaskRequest → GlobalTaskPool → SchedulerSystem`，不另起炉灶。
6. **使用统一日志**：使用 `shared/log/Log.java`（或重构后的 `foundation/log/Log.java`），禁止 `System.out.println`，严禁静默 catch 吞异常（至少 `Log.warn()`）。
7. **禁止 emoji 与装饰性符号**：玩家可见文本（语言文件、手册、界面文本）及源码注释一律禁止使用 emoji，只保留标准 ASCII/CJK 及必要数学符号（→←, ×, ⌊⌋）。
