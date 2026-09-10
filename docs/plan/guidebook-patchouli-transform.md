# 手册改造与引导合并方案评估（Guidebook × Patchouli）

> 撰写：2026-09-08 | 分支：1.21.1 | 决策状态见 §6

## 目录
0. [TL;DR 结论](#0-tldr-结论)
1. [背景与目标](#1-背景与目标)
2. [调研事实（来自源码与发行渠道）](#2-调研事实)
3. [目标架构（推荐：方案 A 双渲染单源）](#3-目标架构)
4. [修改难度评估（核心）](#4-修改难度评估核心)
5. [分阶段落地路线](#5-分阶段落地路线)
6. [决策点](#6-决策点)
7. [ADR 草稿](#7-adr-草稿)

---

## 0. TL;DR 结论

- **"跟着书去玩"无需自研任务引擎**：帕秋莉手册（Patchouli）原生就是干这个的（advancement 锁定条目 + `turnin` 待办 + 内建 `quest` 目标页 + 条目四态图标 + 书内进度条）。参考 Botania 手册的玩法条目即可验证。
- **可集成性确认**：Modrinth 有官方 NeoForge 发行 `Patchouli-1.21.1-93-neoforge`（源码 1.21.x 分支 = MC 1.21.1，含 NeoForge 模块）。blamejared maven 已在 build.gradle 中，compileOnly 门禁照 JEI/Curios 模式即可。
- **奖励确定去掉（已决策）**：教程不再给任何领取式奖励；引导跟随完全由"成就 = 手册进度"承载，元素币/经验本身的游戏内激励不动。
- **总难度评估：中，约 8–15 个专注工作日（换代范围）**，大头在两个中/高项：内容单源化（md→Patchouli 编译），与 tutorial 域删除+每步成就化。推荐先做 P0 破冰验证可集成，其余风险性低。
- 兜底策略：未装 Patchouli 时继续用现有 `GuidebookScreen` + `MarkdownParser` 读同一份 md（只读，不锁进不去），零回归风险。

---

## 1. 背景与目标

### 1.1 玩家痛点（触发本次改造）
1. 右上角教程浮层（`foundation/ui/tutorial` 的 10 步 HUD）**太长、挡屏幕**。
2. **法杖制作没有讲解**（10 步全是建筑/经营线；法杖表只在 `crafting_guide.md` 里，玩家不知道去哪看）。
3. **线性强制**：`TutorialProgressService.computeStep()` 0..10 连续单调，必须走完；以后新玩法线会让教程无限变长。
4. 指南书（Guidebook）与教程完全解耦（ADR 2026-09-02），是两套系统，玩家要分别学会打开。

### 1.2 目标
- 一份手册承载"教学 + 参考"，玩家**跟着书玩**：书里的条目按进度解锁、打勾、推进。
- 再过不挡屏的问题（HUD 浮层删除）。
- 内容写一次、两处渲染：装 Patchouli 的玩家获得完整跟玩体验；没装则退回现有 Markdown 阅读器看同一份内容。
- 以后加玩法 = 加新条目/新分类，不动旧引导。

---

## 2. 调研事实

### 2.1 Patchouli 机制（源码结论，`_refs/Patchouli`）

- **数据模型**（`Xplat/.../client/book`）：
  - `book.json` → 分类（`BookCategory`，支持 `parent` 子分类）→ 条目（`BookEntry`）→ 页面（`BookPage[]`）。
  - 条目字段：`category`（全限定）、`name`、`icon`、`sortnum`、`priority`、`secret`、`read_by_default`、**`advancement`（锁）**、**`turnin`（完成态）**、`pages`、`extra_recipe_mappings`。
- **锁定/完成逻辑**（`BookEntry.updateLockStatus()` + `computeReadState()`）：
  - 条目锁定 = `advancement` 未达成（客户端用 `ClientAdvancements.hasDone(...)`，以服务端成就为准）。
  - 条目状态 `EntryDisplayState`：`UNREAD / PENDING(待办) / NEUTRAL / COMPLETED`。`turnin` 未达成 → PENDING；任一 `quest` 页完成 → COMPLETED。
  - 分类锁定 = 其下所有条目锁定（无直接 advancement 字段）。
- **目标页**（`page/PageQuest.java`，类型 `patchouli:quest`）：`trigger` 填 advancement id 自动判定完成；不填则显示"标记完成"手动按钮（存客户端 `PersistentData`）。
- **页面类型**（`ClientBookRegistry.pageTypes`）：`text / image / crafting / smelting / blasting / smoking / campfire / smithing / stonecutting / entity / multiblock / link / relations / quest / spotlight / empty / template`。crafting 页自动取配方展示并可进 JEI。
- **文本语法**（`BookTextParser`，`$(...)` 命令集）：`$(bold)`/`$(italic)`/`$(br)`/`$(k:键位)`/`$(item)x</item>`/`$(entity:)`/`$(l:条目id#锚点)`/`$(t:tooltip)`/`$(c:/命令)`(可点击命令)/`$(playername)`/`$(#rrggbb)` 等，可注册自定义命令。
- **接入 API**（`api/PatchouliAPI.java`）：`openBookGUI(bookId)`、`openBookEntry(book, entry, page)`（服务端 + 客户端版）；`patchouli:guide_book` 物品用 Book id 数据组件。
- **i18n**：`book.json` 的 `i18n:true` 支持 `name`/页文本用 lang key；内容目录按语言分（`en_us/`、`zh_cn/`）——与现有 `guidebook/{en,zh_cn}/*.md` 布局同构。
- **书外观**：`book_texture`/`model`/颜色可配（可贴合 Wandscape 中世纪主题）。
- 其它书内功能：收藏夹（`GuiButtonBookBookmark`）、搜索索引、背包页内按钮（`GuiButtonInventoryBook`）、`/openbook` 命令、全局进度条（`show_progress`）。

### 2.2 为什么选 Patchouli 而不是自研节点式任务书（对照 FTB Quests）
- FTB Quests（上轮 clone 于 `_refs/FTB-Quests`）是节点画布 + 奖励 + TeamData 服务端进度的全量任务引擎；把这套搬进 Wandscape 要自研数据模型、进度存档、同步、UI 画布——**是大工程**，且和"手册"是两件事。
- Patchouli 把"进度解锁 + 引导顺序 + 打勾"都做掉了，奖励被我们此前已决策去掉，剩余需求（跟玩、参考、内容单源）恰好落在 Patchouli 的成熟能力上。**自研任务书在该需求集下是过度设计**。

### 2.3 Wandscape 现有可复用地基（代码事实）
- 代码发成就：`content/colony/service/AchievementService.java`——数据驱动成就（`data/wandscape/advancement/*.json`，`trigger: minecraft:impossible`）+ `pa.award(holder, criterion)` 幂等授予（事件快路径 + 100 tick 周期补偿扫描）。
- 教程行为标记已存在：`ColonyItemBank.recordPlayerDeposit / recordPlayerSynthesize / recordPlayerRoadPlace`（§探索报告），可直接喂给新成就条件。
- 教程触发点 7 处（现调 `TutorialApi.sendToPlayer`）：`ColonyCreateRequestPacket`、`WarehouseMenu`、`BuildingInteractHandler`、`ProjectionPlacePacket`、`RequestGatherTaskPacket`、`RequestProductionTaskPacket`、`RoadSegmentListener`。
- build.gradle 已配 blamejared maven；compileOnly 可选依赖有 JEI/Curios/Goety/Iron's 先例可循。

---

## 3. 目标架构

### 3.1 双渲染、单内容源

```
内容单一来源：assets/wandscape/guidebook/{zh_cn,en}/*.md（现状 28 篇，建议扩展 frontmatter + 指令块）
        │
        │  build 期脚本 tools/gen_patchouli.py（照 gen_icons.py 模式，生成物提交进仓库）
        ▼
data/wandscape/patchouli_books/guide/{zh_cn,en}/...  （Patchouli book JSON，生成物）

运行时：
  装 Patchouli  → PatchouliAPI.openBookEntry → 帕秋莉书（锁定/待办/quest页/配方/JEI）
  没装          → GuidebookScreen + MarkdownParser → 只读手册（同内容，跳过 frontmatter）
```

入口统一走一个外观门 `GuideFacade.open(entryId)`：
- H 键（`WandscapeClient` 三处处理 + `MedievalScreen` + 面板内）、`guide_book` 物品右键、顶栏 ?、`/wandscape guide [page]`、各建筑屏 H 帮助——全部路由到 `GuideFacade`。
- doc_id（md 文件名）与 patchouli entry id 对齐（`getting_started` ↔ `guide:getting_started`），天然一致。

### 3.2 跟玩主干：每教学步 = 一个成就（服务端权威）

```
每个教学步：
  data/wandscape/advancement/intro/<step>.json     （code-fired impossible，show_toast:false）
  data/wandscape/patchouli_books/guide/.../<step>.json
      entry: advancement: wandscape:intro/<step>      → 未做=锁
             pages: [ text(说明+下一步链接), quest(trigger同上) ]   → 做了=打勾
```

- 现有 10 教学步 → 10 个 `intro_*` 成就 + 10 个条目；追加**法杖制作**步（合成台造第一支法杖，`crafting` 页复用 `data/wandscape/craft_recipes/*.json` 配方展示）。
- 触发：`AchievementService` 现有事件/扫描体系扩展（building category、deposit/synthesize 标记、bakery 库存、tavern 招募、旅舍过夜等条件大多是现成 API 可查）。
- 删除 `content/tutorial` + `foundation/ui/tutorial`（HUD 浮层、SavedData、2 个 packet、`TutorialApi`）与 7 处触发点。

### 3.3 md 方言约定（方案 A，供 transpiler 对齐 Patchouli 概念）

每个 md 文件头加 frontmatter，正文按既定 dialect 撰写：

```markdown
---
category: guide:welcome        # 分类
icon: wandscape:guide_book     # 条目图标
sortnum: 10
advancement: wandscape:intro/town_hall   # 锁定
turnin: wandscape:intro/town_hall        # 完成态
---

# 市政厅

## 这一步要做什么
建好市政厅，NPC 就开始为你干活。
```
- `##`（二级标题）分页；一个 md = 一个 entry，天然映射 Patchouli 的分页。
- dialect 最小集与 Patchouli 页类型映射：`##`子页→`text`页、行内粗体/链接→`$(bold)`/`$(l:)`、`[图片](xxx =WxH)`→`image`页、`:crafting: <配方id>`→`crafting`页、`:quest: <adv>`→`quest`页。
- **写一遍**：此 md 同时被兜底 MarkdownParser 直接渲染（P1 只是让解析器跳过 frontmatter 块）；Patchouli JSON 由脚本生成，双渲染同源。

---

## 4. 修改难度评估（核心）

> 档位：S=小半天内，M=1–3 天，H=3+ 天（专注，`./gradlew build` 作门槛）。人日为粗估，含踩坑缓冲。

| # | 分项 | 涉及文件 / 产出 | 难度 | 风险 | 人日粗估 |
|---|---|---|---|---|---|
| 1 | **P0 破冰：Patchouli compat 接入** | `build.gradle` + `compat/patchouli/` + 一本最小空书 | **S** | 中 | 0.5–1 |
| 2 | **入口收口 GuideFacade** | H 键 3 处 + 物品右键 + 顶栏 ? + `/wandscape guide` + 各屏 helpDocumentPath，新建 `GuideFacade` | **M** | 中 | 1–2 |
| 3 | **tutorial 删除 + 每步成就化** | 删 `content/tutorial`(5 文件)+`foundation/ui/tutorial`(4 文件)；`Wandscape.java` 注册；7 触发点；新增 ~11 个 `intro_*` 成就 + `AchievementService` 条件 | **M** | 中 | 1.5–3 |
| 4 | **内容单源化（transpiler + 内容 dialect）** | 新 `tools/gen_patchouli.py`（md→book JSON），28 篇 × 2 语加 frontmatter/指令块，生成 104 个 JSON | **H** | 中 | 3–6 |
| 5 | **fallback 只读兼容** | `MarkdownParser` 跳过 frontmatter、`DocumentLoader` 不读取;（可选）quest 标记渲染 | **S** | 低 | 0.5 |
| 6 | **法杖/内容条目** | crafting 页 12 法杖：前置为成就/条目，正文来自 crafting_guide 重排 | **S–M** | 低 | 1 |
| 7 | **外观打磨** | `book.json` book_texture/model 定制契合 `MedievalColors`；show_progress | **S** | 低 | 0.5–1 |
| 8 | **文档三件套 + ADR + api-ledger** | `docs/adr.md`、`domain-notes.md` §八、`data-formats.md`、`api-ledger.md` 标注阶段 | **S** | 低 | 0.5–1 |
| | **合计** | | | | **8–15** |

### 4.1 分项细化（为何是这个难度）

**#1 Patchouli 接入（S，风险中）**
- 内容：`compileOnly "vazkii.patchouli:Patchouli:1.21.1-93-NEOFORGE"`（blamejared，需 P0 验证确切坐标与 artifact 自包含性——Patchouli 是 Xplat+NeoForge 模块结构）；建 `compat/patchouli` 包，注意 `compat/` 域 compileOnly 门禁与现有 JEI/Curios 一致；注册一本最小 `wandscape:guide` 空书用于 `/openbook wandscape:guide` 验证。
- 风险点：① 坐标/依赖拆分跑不通 → 降级方案本地 libs/ flatDir（已有 JEI 本地 jar 先例）；② Patchouli 维护节奏慢，1.21.x 分支仍活动（近期 #836）但长期存疑 → 已在 §4.2 记录。
- 验证：`./gradlew build` + dev 运行开书（patchouli jar 放 run/mods + 手动装配）。

**#2 入口收口（M）**
- 5 个入口分散在不同层（Client 键处理、panel controller、MedievalScreen、GuideBookItem、GuideCommand），`GuideFacade` 要把运行时可路由到"帕秋莉条目 or 兜底 md 文档"的抽象缝起来：有 Patchouli → `PatchouliAPI.openBookEntry`；没有 → `GuidebookScreen`。
- `GuideCommand` 的 tab 补全硬编码 26 页（`GuideCommand.java:34-42`）→ 改为来自 book 内容或保留硬编码并维护（M 里的主要噪声项）。
- 各建筑屏 `helpDocumentPath` 的映射（doc→entry 名一致即可，代价低）。

**#3 tutorial 删除 + 成就化（M）**
- 删除面：`TutorialProgressSavedData`（含其 SavedData 键 `wandscape_tutorial_progress`——新档不再写、旧档密钥删除属"开发期不承诺存档兼容"，直接断档，符合硬规则 7）、`TutorialProgressService`、2 个 packet、`foundation/ui/tutorial/*`、`WandscapeApis.setTutorialApi`。
- 新增面沿用 `AchievementService` 模式：`~11` 个 `intro_*.json` + `checkIntro*` 方法（条件多可用现成 API：`BuildingApi` 分类、`WarehouseApi` 存款标记、`production` 合成标记、`TavernApi` 招募计数、`HotelStayHandler` 过夜；法杖制作需在合成台产出事件处补一条授予）。
- 主要成本在删除时清理引用（编译期兜底防漏）与"行为→成就"语义对齐（见 §6 决策 D2）。

**#4 内容单源化（H，最大单项）**
- transpiler 要做：frontmatter 解析 + `##` 分页 + **md 行内语法 → `$()`** + 图片/表格/指令块映射 + 按语言命名空间输出 book JSON + 生成 `index`（分类）。28 篇 × 2 语 = 56 个 md → 约 100+ 个 JSON 生成物。
- 坑：Patchouli **没有 md 表格**——`crafting_guide` 的法杖表要重排为 per-杖 `crafting` 页或文本列表；`getting_started` 八步要拆/重构成"一步一条目"结构；图片路径与 `$(img:)` 语法对齐；`action:wandscape:overview_mode` 类自定义跳转无 Patchouli 直译（可用 `$(c:...)` 或进 `GuideFacade` action 协议）。
- 缓解（可缩半）：首版 transpiler 只做"每 md = 一个 text 页"，不做细粒度分页/翻译，让 book 先跑通；分页/rich 页第二版补。此设计决定见 §6 D1。

**#5 fallback 兼容（S）**
- `MarkdownParser` 顶部跳过 frontmatter 块；`DocumentLoader` 不变；（可选）渲染 `:quest:` 块时读客户端成就镜像显示锁/勾——**首版不做**，保持只读（§6 D3）。

**#6 法杖条目（S–M）**
- 合成台法杖讲解：新增 `crafting` 页即可自动展示配方（`craft_recipes` 数据已在），无手写合成表；解锁挂 `colony_level` 类成就（可复用现有 level 成就）或直接开放。

**#7 外观（S）**：纯资源自定义书皮纹理/`book.json` 颜色字段，贴合 `MedievalColors`。

**#8 文档（S）**：见 §7 ADR 草稿。

### 4.2 风险清单与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| Patchouli 依赖拉取失败或开不了书（构建/运行时隙缝） | 中 | P0 先行；坐标/artifact 问题用本机 `libs/` flatDir 兜底（已有 JEI 本地 jar 先例）；若确认该分支不可集成，改走文档备选并重评估（+1 周量级） |
| transpiler 语法覆盖不全/长期维护 | 中 | 第一版窄语法子集 + 生成物提交仓库可审计；md→book 映射表写入 `docs/data-formats.md`（P1 落） |
| 多人语义：成就授予对象 | 中 | 见 §6 D2——现 `AchievementService.grant` 授予 founder；行为类（deposit/synthesize）建议授予**操作玩家** |
| 删除 tutorial 后引漏 | 低 | `./gradlew build` 编译期兜底；grep `TutorialApi`/`tutorial` 清零后再删 |
| 每语言重复（Patchouli i18n vs 目录分语） | 低 | P1 验证 `book.json i18n:true` 是否可单套 JSON + lang key；否则照现有 28×2 目录复制比而沿用（脚本生成不受影响） |
| 发行流程新增可选依赖 | 低 | mods.toml/发布页标注 `optional: patchouli`；检查清单里加一条 |

### 4.3 备选方案 B（内容直接写 Patchouli JSON，兜底阅读器改读 JSON）难度对照
- 兜底渲染器要从"md 解析"重写为"Patchouli 页模型渲染"（页拆分、导航、`$()` 文本、tooltip/图片/配方子页至少再实现一遍），约 +3–5 人日；且 28 篇 md 内容全部改写成 JSON 页（+2 日量级）。**仅在以"兜底也要完全等价"为目标时考虑**（§6 D1 默认否）。

---

## 5. 分阶段落地路线

> 验收门槛一律 `./gradlew build`；每阶段做完提交（照 §五 提交守则：代码+文档合一条）。

- **P0 破冰（0.5–1 日）**：`compat/patchouli` + 依赖 + 最小空书 + `/openbook` 验证。**先做这个，架构风险清零后进 P1**，否则转备选。
- **P1 内容单源（对应 #4/#5 首版）**：md frontmatter/dialect 约定 + `tools/gen_patchouli.py`（窄语法子集）+ 生成物提交 + 兜底跳过 frontmatter。验证 i18n 单套 vs 目录分语。
- **P2 跟玩主干（对应 #3）**：删 tutorial 域、7 触发点改发 `intro_*` 成就、`AchievementService.checkIntro*`、新增 11 成就数据与 11 条目；法杖制作条目。
- **P3 入口收口（对应 #2）**：`GuideFacade` 路由全部入口；`/wandscape guide` 补全迁移；每屏 helpDocumentPath 映射验证。
- **P4 richness（对应 #6/#7，可选）**：crafting 页 12 法杖富化、书皮纹理、进度条、收藏。
- **P5 文档（对应 #8）**：ADR（§7）落地、domain-notes §八更新、data-formats 记录 md dialect 与生成物、api-ledger 移除 TutorialApi。

---

## 6. 决策点

> 状态标记：【已定】与【待定】。

- **D1【已定】奖励去掉**：帕秋莉无奖励系统，教程不设领取式奖励；激励回归游戏自身（元素币、经验、成就掉落），不引入自定义奖励层。
- **D2【待定】【内容策略】md→transpiler（方案 A，页级）**：推荐直接采纳，理由是兜底零风险、作者继续用 md。备选 B（内容直接写 Patchouli JSON + 兜底改读）不在偏缺时采纳。
- **D3【待定】【成就授予语义】**：行为类教学步（存物/合成/法杖制作）成就授予**操作玩家**；状态类教学步（建了某建筑）沿用 `AchievementService` 现语义授予 **founder**（或单玩家）。多人服两条语义并存需 P2 先定。
- **D4【待定】【无 Patchouli 的兜底能力】只读**：推荐持保留只读（+跳过 frontmatter），不做锁定/打勾模拟；若未来有需求再升自动做。
- **D5【未决】【法杖讲解位置】**：法杖制作同时是"新手第一步"（合成台造杖）与"高级参考"（12 杖表）。建议前者进新手条目标 ladder（advancement 解锁），后者放 `crafting_guide` 富化为 crafting 页线。

## 7. ADR 草稿

| 日期 | 决策 | 原因 | 影响面 |
|---|---|---|---|
| 2026-09-08(拟) | 引导承载迁移：删除 Tutorial HUD 域，教学并入 Patchouli 手册（advancement 解锁/quest 打勾），无 Patchouli 时由 Markdown 指南书只读兜底；教程不再设奖励 | 解决引导挡屏/强制线/法杖无讲解；"跟书玩"是 Patchouli 原生；双渲染单内容源避免写两遍 | 覆盖 2026-09-02 「新手引导与指南书解耦」决策；涉及 `content/tutorial`、`foundation/ui/tutorial`、`foundation/ui/guidebook`、`compat/patchouli` |

---

> 关联文档：`docs/README.md`（导航）、`docs/domain-notes.md` §八、`docs/adr.md`、`docs/data-formats.md`、`docs/api-ledger.md`。
> 备查源码：`_refs/Patchouli`、`_refs/FTB-Quests`。