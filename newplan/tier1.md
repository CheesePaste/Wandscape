# Tier 1 具体方案：死代码清理

> 定位：plan.md「五、修改」的 Tier 1（最高价值、最低风险、先做）的**可执行版**。
> 在 plan.md 骨架（分类/原则/复核）之上，补齐：候选怎么生成、用什么工具、怎么验收、怎么回滚。
> 审计修正（2026-08-30）：图谱不可信（见下）、`deprecated/` JSON 是兼容载荷不是废码、8 万行目标跨 Tier 不属 Tier 1。

## 0. 一句话

把"确定性最高、风险最低"的死代码一次性删掉，一删一个 commit（可回滚），删完 build+test 绿 + 候选 grep 零命中。**不追行数指标，只认候选表核算。**

## 1. 范围（只做三类，别的都不做）

| 类别 | 内容 | 验收 |
|------|------|------|
| 死类 | 无任何编译引用、无字符串引用、无反射入口的 main 类 | build 绿 + grep 类名全仓零命中 |
| 废 JSON | 无任何代码加载路径的 resources 数据（**不是**目录名带 deprecated） | grep 该资源路径零命中 |
| 陪葬测试 | 被测类被删的 `<Class>Test`；测死代码的测试 | 相关测试删尽，`./gradlew test` 绿 |

### 明确不做 & `do not touch`（防翻车）

1. **`data/wandscape/buildings/deprecated/`（14 文件/23.7k 行）不删**。它是 building config 的 `deprecated` 标志载荷：`projection/network/ProjectionNetwork.java:111` `// deprecated buildings stay functional but are hidden from placement`。删了旧建筑读不出来。**废 JSON 判定以「无代码加载路径」为准，永远不看目录名。**
2. **反射入口类不自动删**：`wandscape.mixins.json` 的 4 个 mixin（MixinServerLevel / MixinLevelTicks / MixinOverviewCamera / MixinSplineEditorCamera）、`@EventBusSubscriber`（2 类）、含 `@SubscribeEvent` 的类（30）、`@Mod` 主类、`WandscapeClient`、`Config`、潜在 `META-INF/services` 注册类。这些编译引用为 0、但靠反射/注解扫描活着。
3. **无用 log 挪到横切「Log 治理」**，Tier 1 不删 log——log 判定主观，会污染"确定性高先做"，且与「禁顺手重构」冲突。
4. **不搬类、不改名、不改结构、不改注册 id**（那些是 Tier 2/3/4）。

## 2. 候选怎么生成（核心：图谱不可信，靠双通道闭环）

### 认知前提（本次实测教训，必须记住）

codebase-memory 图谱的 in-degree 被当作 0-引用判据是**错的**。用 `max_degree=0` 拉出前 100 个"0 引用类"，抽查 4 个全是活跃类：

| 类 | 图谱判 | 实测 |
|----|--------|------|
| `Config.java` | 0 引用 | **177 处引用** |
| `AchievementService` | 0 引用 | EngineBootstrap import + `.register()` + 事件方法引用订阅 |
| `EnqueueHelper` | 0 引用 | BuildingApiImpl / BuildingRepairHandler / BuildingSavedData 十余处调用 |
| `BuildingInteractHandler` | 0 引用 | 多个网络包 import + 调用 |

原因：图谱的 CALLS/USAGE/IMPORTS 边没把类级引用、跨包 import、事件 method-ref 完整建边。**结论：图谱只当"建议候选生成器"，永不作删除闭环。** 你不信它是对的。

### 通道 A —— 建议候选（三源交叉，只用来缩小范围）

1. **图谱 in-degree=0**（`search_graph, label=Class, max_degree=0`）→ 列成"疑似表"。
2. **IDEA「Unused declaration」inspection**（见 §4 工具）→ 逐成员级标灰，最贴"引用"语义。
3. **PMD / SpotBugs 死成员清单**（见 §4）→ 补死字段/死方法/死局部。

⚠️ 通道 A 三者的产物**全部**只是"疑似"，必须过通道 B。

### 通道 B —— 删除闭环（唯一拍板权）

对通道 A 命中的每个类，**逐条**检查：

1. **编译引用**：`grep -rn "\b<短类名>\b" src/`。命中（非 self/非注释）→ 存活，摘出。
2. **字符串/数据引用**：grep 类名与 FQN 于整个仓库（`.java` + `src/main/resources` + `*.toml` + `*.json` + `*.txt`）。命中 → 存活或降级为"仅改字符串"。
3. **反射入口白名单**：类上是否有 `@Mixin / @EventBusSubscriber / @SubscribeEvent / @GameTest / @Mod / @Mod.EventBusSubscriber / @RegisterExtension / accessor?` 任一 → 存活，除非人工确认整类确实死透（这类进 Tier 3 判，不在 Tier 1 删）。
4. **注册面**：是否经 ResourceLocation / DeferredRegister / 注册 id 进 game registry。命中 → 存活（连注册都算"活着"，除非连注册也被禁用——那是行为改动，留给后续）。

**只有 A 建议 ∩ B 全通过 = 才入「待删表」。** 待删表一行一条：文件路径 / 行数 / 通道A来源 / 通道B证据。

### 待删表核算规则（净减量法则的量化）

- 每一批删完，必重跑一轮（**级联**：删 A 后 B 的引用归零，B 才进候选）——循环到不再新增为止。
- 只统计**真正删除**的文件与行数，横移/改名不计入净减。

## 3. 执行顺序（一个 commit = 一类、一个原子步）

| # | commit 主题 | 内容 | 验收 |
|---|-----------|------|------|
| 1 | `refactor: 删除 0 引用死类 N 类 / M 行` | 通道 B 全过的纯死类 | build 绿；grep 每个类名零命中 |
| 2 | `refactor: 清理无加载引用 JSON K 个` | 通道 B 全过的 resources 数据 | grep 资源路径零命中 |
| 3 | `refactor: 删除陪葬测试 X 个`（复用 #1 的名称使 test 连带） | 被测类被删导致的 `<Class>Test`；测死代码的测试 | `./gradlew test` 绿；被测类 grep 零命中 |
| 4 | `refactor: 级联死代码（第 2 轮）` | 因 1–3 新增的 0 引用，重复流程 | build+test 绿 |

每步独立提交、每步可 `git revert` 单独回滚。一批内不含第二类改动（禁顺手重构）。

## 4. 外部工具（网上有无现成的？结论 + 怎么用）

**没有 MC 专用死码工具**；一般 Java 工具能用，但反射/数据引用（NBT 键 / lang key / ResourceLocation / 注册 id / mixin / services）是**所有静态工具的盲区**，必须 grep 兜底。按性价比排四级：

### A. 零依赖首选（本机 IDEA）
`Analyze > Run Inspection by Name > "Unused declaration"`（ID `UnusedDeclaration`）。逐成员级、最贴"引用"语义，public 成员默认不报（反射/框架按 entry point 手动标）。可建一个**只开这一条**的 profile（`.idea/inspectionProfiles/`）用 `inspect.bat <proj> <profile> <out>` 无声导出 XML。
**提醒**：10 万行会慢，`inspect.bat` 退出码恒为 0，问题只能看 XML。

### B. CLI 规则级（补死字段/死变量）
- **PMD**（Gradle 内置 `pmd` 任务）：`unusedcode`/`bestpractices` 集 → `UnusedPrivateField`、`UnusedPrivateMethod`、`UnusedLocalVariable`、`UnusedImports`。source 级，配 `onlyAnalyze: com.wsteam.wandscape`。**抓"死变量"最直接**。
  ⚠️ PMD 2024 转 SCM Rights（copyleft）；社区 fork **Pike**（LGPL）。本地用无所谓，进仓/发布留意许可证。
- **SpotBugs**（`com.github.spotbugs` Gradle 插件）：字节码级，`DLS_DEAD_LOCAL_STORE`、`UUF_UNUSED_FIELD`、`UPM_UNCALLED_PRIVATE_METHOD`（Style 类）。对 lambda/匿名类更准。配 `onlyAnalyze` + `effort=MAX`。

### C. 入口可达性级（最接近"真死码"语义，最重，Tier 3 后再说）
**ProGuard `-printusage`**：从 `-keep` 入口递归可达性判死码，输出被删类/方法/字段清单（usage.txt）。**前提是反射入口全写成 -keep**（mixin/事件/注册/模组入口），否则误杀。备选 **java-callgraph2**（Maven Central，方法调用图 + `!entry!` 标记）。在本项目偏重，留作 Tier 3 后整仓"应删清单"验证。

### D. 兜底（不可省）
**ripgrep 字符串引用** + 手动读加载路径。没有替代。

**推荐组合**：A 保底（零配置贴语义）→ B 补死变量/死字段 → C 作假死码复核 → D grep 闭环必做。图谱入 A 当建议源，但甩绳子给 B 闭环。

## 5. 审计对 plan.md 的三处修正汇总

1. **图谱 in-degree 不可作 0-引用判据**（实测 4/4 高危候选全是活类，见 §2）。原"引用计数定生死（图谱查 in-degree）"应改为"图谱给建议，编译+grep 定生死"。
2. **`deprecated/` JSON 不是废码**（building 兼容载荷）。原 Tier 1"废弃 JSON"表述易误导；改以"无加载路径"为准。
3. **8 万行目标是 Tier 1–3 跨阶段目标**，非 Tier 1 验收线（Tier 1 纯删除到不了）。Tier 1 验收按候选表核算净减 + build/test 绿，不预设行数线。

## 6. 复核清单（每 commit 前）

- [ ] TG: `./gradlew build` 绿（编译是头号刑具）
- [ ] `./gradlew test` 绿（仅当涉及 test 删除）
- [ ] 每个已删类：`grep -rn "\b<类名>\b" src/` 零命中
- [ ] 每个已删资源：`grep -rn "<资源路径>" src/` 零命中
- [ ] 反射入口白名单复查（mixin/event/@Mod 未被误删）
- [ ] `git status` 只含本步文件；净增行数 → 停下问值不值（横移嫌疑）
- [ ] 每批后跑一轮新候选（级联），到不新增为止
- [ ] 更新 `newplan/tier1.md` 的待删表核算与 `status.md`

## 7. 不变式红线（沿用 plan.md）

- 高兼容不硬编码（方块/物品走标签 JSON）；不删任何会进 game registry 的注册物。
- 纯逻辑不 import MC（本步不涉及，但别破坏）。
- 禁顺手重构：一批只动一类。
