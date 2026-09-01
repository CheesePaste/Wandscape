# Wandscape 开发指南（CLAUDE.md）

> Minecraft NeoForge 1.21.1 模组。两大玩法系统：**殖民地自动化**（NPC 法师经法杖执行原子操作，建造建筑/采集元素/合成物品）+ **模拟经营**（短居游客沿道路入城，交互商店/服务建筑，元素利润循环）。设计文档 `docs/simulation.md`。
> **当前在第一次大重构（`refactor` 分支，2026-08 起）。** 重构 ≠ 重写：两大系统与全部玩法保留，砍的是冗余与分层痼疾，不是推倒。

**先读【一、重构现状】——你接手时的"问题是什么、要干什么"。** 其余章节是模组的既定开发纪律，不因重构改变。

---

## 一、重构现状（先读这里）

### 为什么重构（十条痼疾，全文见 `newplan/why`）
一句话：**我们一直在用专业软件/微服务思路写业余模组**。典型症状——
- **定义重复**：NPC 属性规则散在 5 个类（上下界/默认值/种类/roll/升级/招募），**已实际咬人**（某处给 `AttributeType` 加两属性，其余处漏同步，崩）。
- **软件包混乱**：30 个顶层包，`core/engine/shared` 三桥层 254 文件 ~28k 行占 27.5%，功能域占 72.5%，规则名存实亡。
- **代码量灾难**：11.6 万行，防御性/搭桥占比高；<40 行碎片 170 个。
- **JSON 泛滥**：单点 `element_mappings` 1188 个（占 data/wandscape 87%）。
- **UI 堆积**：每建筑一个 Screen，加个拆除按钮要改每个。
- **"不能直接引用"反模式**：shared 包为互不引用搭桥，越搭越多。
- **命名灾难**：Mage/Npc 混用无规律（见 `newplan/rename.md`，统一到 `Mage`）。
- **文档多源漂移**：旧 `docs/` 三套镜像互相缺包（`architecture.md` 连 `scepter/` 都漏），**无一可信**。
- **Log 满天飞**；**测试灌注**（760 类挂 779 `@Test`，极少抓到 bug）。

### 目标形态（终局，详见 `newplan/plan.md` §三）
**5 顶层 + 11 功能域**：

```
api/       公开契约（addon/整合包面）：5-7 接口 + 公开事件 + 瘦身的 WandscapeApis
content/   11 功能域：colony building npc tourist production road magic task warehouse element items
foundation/ 跨域基建：ui(去堆框架) networking(包基类) registry saveddata log util ui/render(共享视觉)
compat/    jei/curios/ironspellbooks（compileOnly 门禁）
impl/      @ApiStatus.Internal 装配门禁（WandscapeBootstrap = 原 EngineBootstrap；解散 WandscapeEngine 定位器）
```

**域内按功能块切，不设 client/server/network/data 镜像子包。** 旧 `core/engine/shared/client/command/mixin/gametest` 等顶层包随重构拆散收编；未搬完的旧代码仍散在顶层各包。

### 拆分铁律（探索拍板，务必遵守）
**"基建/框架/去堆目标收 foundation，域特性留域"**
- **foundation 收**：包基类（AbstractPayload/codec）、Screen 框架、共享控件/工具/跨切值类型、SavedData/log 工具、**去堆塌缩目标**（各建筑 Screen → 数据驱动窗口）、真跨域同步包。
- **域留**：实体/域特性渲染器（WandscapeNpcRenderer/RoadPlacementRenderer/…）、域特性网络包（SplineBuild/AltarCast/TavernRecruit/…）、域特性 Overlay/Menu（RoadStudioOverlay/TaskManagementOverlay/NpcMenu/…）。
- 这些"域留"**不是** tech 镜像（不是每域一套 client/server/network/data），而是该实体/特性的配套；**硬收全会让 foundation 反向认识全部域**。这条修正了早期"网络/UI 全部全局收敛"的说法。

### 阶梯（按风险排队，详见 `newplan/plan.md` §五）
**Tier 1 删**（0 引用类/无加载路径废 JSON/陪葬测试；删除判定靠**「编译 + grep 字符串」双关**，**不信代码图谱 in-degree**——实测 in-degree=0 抽出的候选全是活类）→ **Tier 2 改名**（Mage→Npc 全仓 + 注册 id/lang key/NBT 一串 grep，清单 `newplan/rename.md`）→ **Tier 3 合并**（属性/配方/向量/SavedData/packet 样板收敛到唯一处）→ **Tier 4 重组**（拆桥层到五顶层，**移动不改逻辑、改逻辑另开一步**）。

### 认知地图（先查它，别信旧 docs）
**`newplan/packages.md`** —— tier0 摸底产物，29 顶层包逐一核实（职责/数据流/依赖/坑/归属/全局结论表）。**改结构前先查它**；旧 `docs/architecture.md` + `docs/modules/` + `data/` 全是过期镜像，已在待删清单。**进度唯一事实源 `newplan/status.md`，每步做完立即更新。**

### 探索已知的关键事实（别再踩）
- **`magic/CastBrain` 不是死码**（12 文件引用）——旧"0 引用"cite 已过期，Tier 1 删除候选须避开；真死码是 `core/types/EquipmentPreset`。原 `core/types/NpcAttributes` record（零字段读取的 ECS 不透明载体）已随属性收敛合并删除。
- **NPC 属性全套规则已收敛到 `content/npc/attributes/NpcAttributes` 一个类（2026-09-01 完成）**。六段（种类/上下界/默认值/roll/升级/招募）全在单类；规则表是「默认值 + 覆盖层」结构，整合包经 `WandscapeApis.getNpcAttributesApi()`（`api/NpcAttributesApi`）在 mod 初始化时覆盖。**改属性规则只碰 `NpcAttributes` 一个文件。** 属性共 9 项：7 可见（`ORDER`，可训练/可升级/有 SPECS）+ 2 隐藏（HEALTH_REGEN/MANA_REGEN，不显示、无 SPECS、base 恒 1.0，仅装备/外部修饰符改动）。MOVE_SPEED/ARMOR_VALUE 每级加成是废案（perLevel=0）；ARMOR 默认 5。招募计算 = 基础 roll + 升级提升组合，勿独立成类。
- **点/向量自造族 5 个、保留非 vanilla**（SplineVec3/PathPoint/XZPoint/GridPos/BlockOffset）；Tier 3 若收敛，首选合并成单个 int 点类（非改 MC 类型——保"纯逻辑不 import MC"）。
- **`shared/data/InterruptRecord` 死、`task/runtime/InterruptRecord` 活**，同名双 record 别删错。
- **`buildings/deprecated/`（14 文件）是向下兼容载荷，不可删** —— 旧档/隐藏建筑按内层 `id` 解析（如 `tavern`，`WandscapeDataLoader` 递归扫 `*.json` 仍注册），删即断旧档加载。本目录**多次被误删**过：判定靠 `newplan/packages.md` + 该文件夹内 `README.md` 双重确认；已废的 `road_templates/`（旧 schema 孤儿）别与它混为一谈。新增/删除此类兼容载荷须同步 `packages.md`。

---

## 二、核心原则（不因重构而变）

1. **高兼容**：不动原版行为、**不硬编码方块/物品引用**；功能 JSON 数据驱动、方块映射走标签。**这条是模组活下来的根基，重构也不动。**
2. **按功能聚合、不过度分层**：一个顶层包 = 一个功能域，只做一件事好读即可；**跨包直接调用普通类 = 正常**，解耦靠可读不靠接口绕圈。
3. **轻度不硬核**：关停是效率降级而非建筑损坏。
4. **稳定性优先**：所有可能失败路径必有兜底；出错至少 `Log.warn()`，禁静默失败/崩溃。
5. **文档讲人话**：只写接手人需要的；能半行说清不写一段；代码本身可读优先于文档。
6. **纯逻辑与 MC 解耦**：不依赖 MC 的纯逻辑（属性计算/蓝图解析/任务评分/路由）**不 import MC 类**——这是它们能 JUnit 单测的前提，**唯一硬边界**（Tier 3 点/向量裁决口，保留自造族正是保此边界）。
7. **用模组 Log**：`shared/log/Log.java`；上屏/聊天只留错误与完成反馈，其余用 Log。
8. **禁止 `./gradlew runClient`**。
9. **动手前看** `packages.md` 该包小节 + `plan.md` 相关阶梯，不逐篇读文档。
10. **禁止 emoji 与装饰性图标符号**：面向玩家文本（`lang/*`、`guide/**`、I18n、Screen 内联、叙事 JSON）与源码注释都禁；只留基线对齐的箭头（→←↑↓）、乘号 ×、math 括号（⌊⌋）、ASCII/CJK。

> **SOUL**：不要对用户言听计从——像资深开发者一样，分析后用最佳实践实现，而非一味遵循指令。

---

## 三、开发与质量

### 构建命令
```bash
./gradlew build              # 编译
./gradlew test               # 运行单元测试
./gradlew runGameTestServer  # 运行 GameTest
```
首次运行或 runClient 报错 `clientRunVmArgs.txt` 不存在，先 `./gradlew neoForgeIdeSync`。**写完后改动要能过 `./gradlew build`；改纯逻辑要全绿 `./gradlew test`。**

### Testing（守门员不是简历）
现在 760 个类挂 779 个 `@Test`，绝大部分从没抓到过 bug，纯烧维护费——**不养这种测试**。只在**纯逻辑且有分支/解析/计算/状态转换**的地方写几个代表用例：属性计算、蓝图 DSL 解析、任务评分、配方与元素公式。**下面这些不值得测**：纯数据容器、getter/setter、透传、单个 if 平凡判断、任何依赖 MC 运行时的东西（留下集成/手测）。删测试比写空转测试好。其余：数值平衡不用断言钉死；测试类 `<Name>Test` 镜像包路径；纯 JUnit 5，不引 Mockito/AssertJ。

### 工作流
**需求澄清前不写代码**：用户提出设计/实现问题时，先用 `grill-me` skill 反复追问直到需求明确、决策树每支都敲定，再写代码；禁止需求模糊时直接动手实现。**修 bug**：先复现 → 修根因 → 纯逻辑处补防回归测试 → 全量 `./gradlew test`。

### 常见陷阱
1. **NBT 传出不 copy** → `return tag.copy()`
2. **事件依赖执行顺序** → 事件仅通知，需顺序用直接调用
3. **任务分发唯一通道** → 走 `TaskRequest → GlobalTaskPool → SchedulerSystem`，别另起炉灶
4. **静默 catch 不记日志** → 至少 `Log.warn()`
5. **猜测 MC 类名** → 必须查源码（见下「代码发现」）
6. **超时/异步路径无兜底** → 所有可能失败路径必须有兜底（原则 4）
7. **游客 ≠ 常驻市民**：旧 Citizen 系统已完全移除。游客是短居访客，无职业/床位/工作场所/住宅/状态机，由 `tourist/` 包驱动。`TouristState` 只是移动状态**标记**，**禁止扩展成带迁移的复杂状态机**；真状态机是 `TouristMoveGoal.MoveMode`；禁止向 TouristEntity 添加任何常驻市民概念。
8. **缺 key 兜底越攒越多** → 数据格式改动见下方「数据格式与兼容纪律」。

### 数据格式与兼容纪律
开发期不承诺存档兼容，迭代期旧档直接断档是权利。① 改 NBT/JSON/注册 id：要么带版本号迁移，要么断档；**禁止新增"缺 key 补默认/猜测"的无版本号兜底分支**（兼容代码必然指数增长）。② 真要兼容旧档走版本号：SavedData 顶层存 `version`，按版本走一条显式迁移链，禁各散加特判。③ 删字段就真删，不留读取别名/兼容构造器。④ 存量兼容分支（~400 行/22 文件）只清不增。

---

## 四、代码组织（目标形态 + 增量归属约束）

**目标形态** ＝ 上方【一、重构现状】的 5 顶层 + 11 功能域 + 拆分铁律。新代码照此写；**旧代码不主动搬**——除非任务正好落在那一块，顺手搬到目标形态且不动其他。

**增量归属约束（防再分散）**：改动/新增某功能域（npc/road/tourist/building/task/…）的逻辑，代码只落**该功能域自己的包**，**禁止再往 `core/`/`shared/`/`engine/` 塞**——三个桥层只减不增、收尾拆除。旧代码不搬，但**不许因"找不到所属包"就另起炉灶**。**一个概念的全套规则/常量/公式收敛进该功能域唯一一个命名类**（如 NPC 属性→`NpcAttributes`），同域别处只引用、不清写。

**跨包直接引用 = 正常**。`WandscapeApis`/`shared/event` 只留给两类真实需求：① 附属模组/整合包作者要用的公开契约；② 真事件流（某系统发生某事、别的要响应）。**纯内部的"我想调你一个方法"不许包装成 API 或事件——直接调用。**

**归属看语义，不看依赖方向（硬原则，2026-08-31 迁移定版拍板）**：判断一个类/包归哪个域，问"它**是什么**、服务于谁"，**绝不问"谁依赖它"**。跨域直接调用是正常、是默认，**永远不是要把某个类挪出归属域的理由**。
- ❌ 反模式：`scepter` 因"npc/guard 会反向依赖 items"就不放 items、硬拆进 npc——这正是旧 `shared` 桥层"为了不直接引用而搭桥"的**同一个病**换了名字重来。
- ✅ 正例：`scepter` 是功能性物品（含系统层/SavedData）→ 整树进 `content/items`；npc/guard 消费它 = 跨域直接调用，正常。
- 这条是**模组 shared 桥层病根的根治**：桥层就是为"规避反向依赖"而生的，禁了动机，桥就不会再长出来。判定归属时若发现自己在为"谁依赖它"纠结，停下来按"它是什么"重判。

**唯一不变的硬边界**：纯逻辑代码不 import MC 类（保单测）。靠自觉 + IDE 检查，不靠包名禁令。

---

## 五、提交与版本

### 提交规则
- **按逻辑任务聚合提交**：一个任务完成提交一次，同任务多次小改动合并；任务没做完但被迫中断，flush 提交一次未完成改动。
- **同任务内代码+文档合一条 commit**，前缀按代码类型（`fix:`/`feat:`/`refactor:`）；**纯文档任务**才用 `doc:` 单列。测试随代码。
- **大重构例外，逐步提交**：移动/改引用/结构变更等高危步骤每完成一步立即 commit（保留回滚点）。
- **提交本次工作改过的全部文件**；已被他人提交的跳过。
- **格式**：中文一句（改动什么 + 为什么）。`fix:` 修 bug，`refactor:` 重构，`feat:` 新功能，`doc:` 文档，`chore:` 杂项。
- **未版本管理的文件必须处理**：新文件要么 `git add`，要么 `.gitignore` 排除，不残留 untracked。
- **多终端多 AI 并行**：同一分支同一时刻只允许一个 AI 提交；绝不 reset/stash/rebase 对方提交，别人提交落中间就 cherry-pick 到最新。要并行开独立分支/worktree，最后手动合并。

### 版本管理与发布 Release
- **版本号不主动递增**；仅次版本号变化时清理 `build/libs/` 旧 jar（第三位变化不删）。
- **发布流程**：更新 `gradle.properties` 的 `mod_version` → 清理旧 jar → release commit（`chore: mod_version 旧→新 — 发布 新房（关键词/）`）→ `git tag v<版本>` → push main+tag → `./gradlew build` 产出 jar → `gh release create` → `gh release upload` jar（`--clobber`）。
- release 正文：`# Wandscape <版本>` + 引言 + emoji 分区（🌍 多语言/🎓 新手引导/🔗 供应链/👛 经济/🐛 修复）每区 3-6 条。
- **不准撤回私自提交。**

---

## 六、子代理使用

- **先拆解再委派**：先分析任务可拆分哪些独立子任务，再决定是否并行。禁止用户一问就直接丢给子代理。
- **并行优先**：互不依赖的子任务用多 Agent 并行，缩短墙钟。
- **按需使用**：单文件查找/一行修改自己做；只有多文件扫荡/跨模块搜索/独立研究有并行收益才委派。
- **委派给足上下文**：写明找什么、边界在哪、返回格式。
- **重构期间禁止就同一批文件并行**：同一分支只允许一个 AI 写（见提交规则）。
