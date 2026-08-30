# 重构进度跟踪

> 此文件是重构进度的唯一事实源。每个阶段做完、每发现一个新问题，都更新这里。
> 起点：`newplan/why`（2026-08-29 写出的十条痼疾）
> 约定重写：`CLAUDE.md`（重构状态、代码组织约定、Testing、重构进行中）

上次更新：2026-08-30

## 阶段 0 — 确认问题（已完成）

写清了十条痼疾（见 `why`），并核实补充：

- 死代码确认：`core/types/NpcAttributes` 全仓库 0 引用。
- 属性重复定义确认：`NpcAttributes`、`MageHutAttributes`、`MageAttributeRoller`、`WandscapeNpc` 字段、`TouristEntity` 字段，至少五处。**2026-08-30 已实际咬人**：某处给 `AttributeType` 加 `HEALTH_REGEN/MANA_REGEN` 两属性，`MageHutAttributes.SPECS` 只 7 项、`MageAttributeRoller` 只滚 7 项，人肉同步靠不住漏了。由此次 bug 立【增量归属约束】——功能域改动只落自己包、一个概念收敛进唯一命名类（CLAUDE.md 已加）。
- 测试灌注确认：760 个类挂 779 个 `@Test`，从未拦截过几次回归。
- 文档死引用确认：CLAUDE.md 原引用 `docs/roadmap.md`、`docs/jingying.md`，均已不存在。
- 文档多源漂移确认：`architecture/README.md` 自称历史快照；`docs/architecture.md` + `docs/modules/` + `architecture/packages/` 三套镜像共存且互相漂移。

## 阶段 1 — 约定去专业软件化（进行中）

目标：CLAUDE.md 与工作约定改成"业余模组开发者"的常识，删掉无意义的专业软件仪式。

- [x] 重写 CLAUDE.md
  - [x] 废除"互不直接引用 / getXxxApi() 搭桥 / 事件通信"约定，改为按功能包直接调用
  - [x] 保留唯一硬边界：纯逻辑不 import MC 类（保单测能力）
  - [x] 文档即代码 → 文档讲人话：只写接手人需要的，不为写而写
  - [x] Testing 从"补强断言、结构不变式"降为"守门员不是简历"：只为纯逻辑有分支/解析/计算处写几个代表用例，禁止堆量
  - [x] 工作流简化：不做先后读 architecture/packages → roadmap → per-包 的仪式链
  - [ ] ~~提交流程简化~~（CLAUDE.md 已给多 AI 并行约定；若实际操作仍烧钱，再进一步砍）
- [x] 新建本文件（进度跟踪）
- [x] 立「数据格式与兼容纪律」（CLAUDE.md 新增节 + plan.md 决策 #6）：开发期不承诺存档兼容、禁无版本号兜底分支、删字段就真删、存量 ~400 行只清不增。前提来自兼容审计：全仓向下兼容代码约 360-450 行 / 22 文件 / 占 0.35-0.45%，无版本号机制、无 @Deprecated，全靠"缺 key 补默认"内联分支——成本函数是版本数指数，立规优先于删码。

## 阶段 2 — 结构重构（未开始，方案已定）

方案：`plan.md`（业余版重构方案）。目标：提取真正有用的模块，拆掉无意义分层。按风险分档排队：

- **Tier 1 删除**（2a）：0 引用类 + 无加载路径废 JSON + 陪葬测试。**具体方案见 `tier1.md`**（含双通道候选生成 / 工具分级 / 级联重跑 / 复核清单）。完成判定：build + test 绿 + 候选类名全仓 grep 零命中，净减由候选表核算给出。**注意三点**：① 图谱 in-degree 实测不可信（4/4 高危候选全是活类，见 tier1.md §2），删除闭环靠「编译 + grep」；② `data/wandscape/buildings/deprecated`（14 文件/23.7k 行）是 building 兼容载荷，**不删**；③ 8 万行目标是 Tier 1–3 跨阶段目标，非 2a 验收线。
- **Tier 2 改名**（2b）：Mage→Npc 全仓统一（连注册 id/lang key/NBT 字符串一起 grep）。完成判定：grep 旧名零命中。
- **Tier 3 合并**（2c）：消灭五处属性定义，收敛到一处（纯逻辑 + 数据驱动），样例等值验证。
- **Tier 4 重组**（2d）：拆除 core/engine/shared 三层的搭桥类，按功能域重排。移动不改逻辑。
- **横切三件套**（3/4/5 阶段）：UI 去堆（抽公共 Screen 样板先行）、lang 分文件（样板先行）、Log 治理（删除随 2a 顺手做，输出点审计放最后）。

## 其他（日志）

- 2026-08-29：用户写出 `why`；本次会话重写 CLAUDE.md + 建本文件（阶段 0/1）。
- 2026-08-29：摸底现状（648 文件 / 10.1 万行，shared/* 桥包 100+ 文件，element_mappings 1187 JSON，lang 每语言 2031 行）；给出业余版重构方案并落库 `plan.md`（定调/侦察/修改/复核 + 阶段序列），阶段 2 改为"方案已定"。
- 2026-08-30：**plan.md 修订**：补核心侦察结论（行数构成/四方重复清单/参考解剖判据）+ 目标形态 30→5 顶层包 + API 收敛 + 文档裁决 + 四项用户拍板的决策（顶层形态/域内切法/UI 硬目标/持久化维持）。CLAUDE.md 域内切法条款同步改为"按功能块切 + 网络/UI 收全局"。三参考模组（MineColonies/Create/Botania）浅克隆于 `_refs/`（已 gitignore）供解剖对照。
- 2026-08-30：CLAUDE.md「代码发现」新增第 5 条：动结构/API/UI/数据类改动前先查 `_refs/` 对应参考（含速查表），禁整段照搬。
- 2026-08-30：6 个子代理全仓扫命名问题（648 类，逐一核对 neoforge sources jar 撞 MC/JDK、注册 id/lang/NBT/JSON 字符串面），产出 Tier 2 改名清单 `rename.md`。**用户拍板决策①：殖民地法师统一到 `Mage`（B + 全字典）**——类名、注册 id、网络包 id、lang key（含散文）、NBT 键、指南名一并 npc→mage。清单含批 1 撞 MC/JDK 实锤（core/ecs/System↔java.lang.System、core/types/AttributeModifier↔MC、core/component/Inventory↔MC、core/boundary/EventBus↔NeoForge、shared/data/Activity↔MC、shared/ui/I18n↔MC 等）、批 2 一物多名收敛（词表驱动）、批 3 泛名矫正、批 4 方法名重灾区（`ResourceId.getFuckPureResourceId_NotContainFuckedNBT()` 2026-07-04 commit 0afd43ec 引入，6 处调用）、以及「跟 Tier 4 走、现在别动」清单。
- 2026-08-30：**审计 plan.md 并落 Tier 1 具体方案** → 新建 `tier1.md`；plan.md 三处修正。三大发现：① codebase-memory 图谱 in-degree 不可信（`max_degree=0` 抽 100 个，抽查 `Config`/`AchievementService`/`EnqueueHelper`/`BuildingInteractHandler` 全是活跃类——图谱漏建类级 import / event method-ref 边），0-引用判定改以编译+grep 双关；② `data/wandscape/buildings/deprecated`（14 文件/23.7k 行）是 building 兼容载荷（`ProjectionNetwork` 已证实语义），**不可删**，废 JSON 判定以加载路径为准；③ 8 万行目标是 Tier 1–3 跨阶段，非 Tier 1 验收线。工具调研：无 MC 专用死码工具，组合为 IDEA「Unused declaration」（零配置保底）→ PMD/SpotBugs（补死变量/死字段）→ ProGuard `-printusage`（Tier 3 后真死码复核）→ grep 字符串兜底（必做）。
- 2026-08-30：修 "AttributeType 加 `HEALTH_REGEN/MANA_REGEN` 两属性、其余处漏同步" 崩溃 bug——NPC 属性分散五处的实证（见阶段 0）。据此 CLAUDE.md 立【增量归属约束】（功能域改动只落自己包、一个概念收敛进唯一命名类、禁再往 core/shared/engine 塞），gaps.md 记收敛指引；并委派两侦察：① 全仓扫同类"一处改多处漏"重复收敛遗漏；② 解剖 `_refs/` 三家核心包划分，用于敲定 content/ 分包粒度。
- 2026-08-30：**诊断"文档全不可信"**——`architecture.md` 包图漏 `scepter/`（代码 9 文件、文档无）、`docs/README` 缺包、architecture.md + modules/(20) + data/ 三套镜像各自缺包互相漂移；连"同一份报告"都能描述两套调参（`expToNext` Javadoc 55 vs 代码 25——调过参、注释改了、Javadoc 忘同步）。结论：现有文档无一可信，重构前必须先重建认知。据此立 **tier0（可信全项目文档摸底）** → 新建 `newplan/tier0.md`（8 批探索，每批一个 AI 扫真实代码产 packages.md 节稿；先建空骨架、旧三镜像删除）；plan.md 阶段序列已挂载 tier0 行。
- 2026-08-30：完成三侦察（结论作 tier0 各批**初始假设**，待真实代码核验）：① 参考解剖——三家玩法域公分母 **12~21**（Create content 12 / Botania common 18 / MineColonies core 21），顶层软件层单包收容（foundation/infrastructure/api/network），域内按功能块切不设 client/network/data 子包。② 功能域扫描——content/ 初步落 **8 功能域 + 1 items/equipment 域**：building(并 projection/overview/raid/stats)、task(并 op)、road、magic(并 element)、npc(并 guard + scepter/ring 系统留域)、tourist、production、warehouse；items 收 wand/compass/guidebook + 各域剥出的物品类；shared/core/engine/client/compat/command/dataconfig/mixin/gametest 进 foundation。③ 重复收敛新发现（plan.md 重复清单之外）：`ColonySnapshot` 双 record 逐字节相同（与 NPC 属性 bug 同源，加字段碰 8+ 文件）、`UnlockRequirement` 双 record、4 个 Async-op 执行器"pending+tickAll"样板（注释自认镜像）、5 个 `record X(String id)` 同构、`expToNext` Javadoc 漂移（实为文档漂移、非重复 bug）。