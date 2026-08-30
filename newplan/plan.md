# Wandscape 重构方案（业余版·修订）

> 目的：给重构定调——定义侦察、修改、复核的方法与**终点目标**，不追求重构后多完美，只求可控、快速、成果可预期，能维持开发。
> 问题清单：`why`；进度：`status.md`。
> 修订（2026-08-30）：补入核心侦察结论、目标包形态（30→5 顶层）、API 收敛、文档裁决、决策记录；结构解剖参考自 `_refs/`（MineColonies/Create/Botania 浅克隆，不入库）。本次修订经用户拍板（见【四、决策记录】）。

## 一、定调

1. **北极星是维持开发速度，不是架构优美。** 加一个"拆除按钮"从碰 20 个文件降到 3 个，就是胜利。
2. **净减量法则。** 重构步骤必须让代码变少（行数/文件数）**或让下次改动明显更省力**。只搬不动、换了名字还是同样的缠绕，叫"横移"，不算重构。
3. **风险分级，小步快跑。** 顺序永远是：删（零风险）→ 改名（编译兜底）→ 合并（需人工判断）→ 重组（高风险）。确定性高的先做，把信心和显性成果先落袋。
4. **一个 commit = 一个可编译可测的原子步，每步可回滚。**
5. **做到病痛消失就停，不追完美。** 正式阶段做完后，把"顺手清理"养成日常习惯。
6. **禁止顺手重构。** 一个改动只做一件事：不改格式、不改旁边类、不改注册 id。
7. **重构必须能被打断。** 阶段之间随时能回去做真实需求，代码仍然健康。

## 二、侦察

### 原则（沿用）
- 只侦察下两步需要的范围，不搞全项目体检。产出是候选改动清单表，不是文档。
- **数字摸底，让数字自己说话**：包、文件、行、JSON 计数。
- **引用计数定生死**（codebase-memory 图谱查 in-degree）：0 引用→直接删；1 引用→内联进调用方；同包 2~N 引用→合并收敛对象；跨包高引用→只改名字不动结构。⚠️ **图谱 in-degree 只作建议、不作闭环**（实测 `max_degree=0` 抽出的前 100 个"0 引用类"中 `Config`/`AchievementService`/`EnqueueHelper`/`BuildingInteractHandler` 全是活跃类，图谱漏建了类级/事件 method-ref 边）。定生死靠「编译 + grep 字符串引用」双关，详见 `tier1.md`。
- **依赖图找搭桥点**：shared/event、shared/api、engine/boundary 的真实使用方，纯转发类→Tier 3，环→Tier 4。
- **痛点反向侦察**：每加一个功能/修一个 bug 记一笔：绕了几个包、碰几个文件。
- **适配 MC 特有坑**：类名被 lang/*.json、NBT 键、注册 id、JSON 数据引用时 grep 字符串一起改，避免运行期崩/存档炸。

### 核心侦察结论（已完成，数字作为计划基线）
1. **摸底**：648 文件 / 101,531 行；顶层 30 个包；core/engine/shared 三桥 254 文件 / ~28k 行（占 27.5%），21 个功能域占 72.5%；<40 行碎片 170 个；15 份 SavedData 样板；30 份网络包样板；NPC 属性五处定义。
2. **行数构成**（抽样 ~1 万行逐行分类，行加权）：真逻辑 55–62%；样板（NBT 存档/packet/注册）18–23%；注释 12–14%；防御性 6–10%。**关键**：防御全删也到不了目标，主要回吐靠样板收敛 + 重复删除；注释是资产不能动。
3. **重复清单**（NPC 属性之外另有四重项）：
   - 配方 record 集群：`parseElementMap` 五法逐字节相同，6 个配方 record 平行同构 → 收敛成 1 个 `CraftRecipe`。
   - 纯 Java 点/向量自造族：`SplineVec3` 是 vanilla `Vec3` 全文复刻，加之 `GridPos`/`PathPoint`/`XZPoint`/`BlockOffset` 各写一份 int 三元组 → 改用 vanilla 类型（与"纯逻辑不 import MC"红线的裁决口见 Tier 3）。
   - SavedData 包装样板 15 份（`FACTORY` + `get()` 同构，注释自认仿写）→ 抽 `SavedDataUtil`/抽象基类。
   - 网络包静态 handler 样板 ~30 份 → 抽泛型 `AbstractPayload`。
   - 中轻：config record 群（Relax/Shop/Service/Wonder 同构）、3 组同名常量分两文件、`shared/data/InterruptRecord` 死代码、TouristEntity↔Shadow 镜像。
4. **参考解剖判据**（MineColonies 2094 文件 / Create 2016 / Botania 1083，三审观点一致）：
   - 顶层包收敛到 3–9（我们 30）；**域可多、顶层必须少**。
   - API 是**平级顶层包**（api/apiimp 或 api/impl），配装配/门禁层，不在深目录。
   - 数据真相放代码/生成物（datagen 或 JSON datalistener），不维护手写镜像。
   - UI 用公共框架/模块组合（MineColonies 4 窗、Botania 2 GUI），不堆每建筑 Screen。
   - 复杂域按纵向切片：契约 → 装配 → 共享基类 → 薄业务类。

## 三、目标形态

### 顶层包地图（29 → 5）
> 依据：`packages.md`（tier0 摸底，29 顶层包实测）+ 用户拍板。数字/依赖据真实代码。
```
com.wsteam.wandscape/
├── api/         公开契约（addon/整合包）——只留真公开面，其余内联回功能域
├── content/     全部功能域（11 域），域内按功能块切，不设 client/network/data 镜像子包
├── foundation/  跨域基建
├── compat/      jei/curios/ironspellbooks 第三方集成（compileOnly）
└── impl/        @ApiStatus.Internal 装配/门禁（薄）
```

### content/（11 功能域）

| 域 | 职责 | 收编（旧包） | 关键子块 |
|----|------|-------------|---------|
| `colony` | 殖民地级纯逻辑：等级/经验、激活冻结/离线倍率、存档；报表；袭击 | engine/colony + stats + raid | `colony/level`、`colony/activation`、`colony/SavedData`、`colony/stats`、`colony/raid` |
| `building` | 建筑内核：注册/注销/持久化/空间索引/贡献/任务队列/右键分流/建造拆除生命周期；建造模式；扫描器 | building + projection + scanner | `building/internal`、`building/placement`、`building/scanner`、`building/config`、`building/block` |
| `npc` | 殖民地自动化核心：法师实体 + 属性收敛 + 战斗 + 招募 + 死亡复活 | npc + guard + scepter系统层 + 招募 | `npc/entity`、`npc/ecs-bridge`、`npc/attributes`、`npc/combat`、`npc/recruit` |
| `tourist` | 短居访客经济：spawn/移动/阴影仿真/商利/离城 | tourist | `tourist/sim`、`tourist/goal`、`tourist/spawn`、`tourist/entity` |
| `production` | 配方/craft 门面 | production | `production/recipe`、`production/craft`、`production/affordability` |
| `road` | 路网：图路由/样条/编辑器/铺路/建造 | road | `road/router`、`road/core`、`road/editor`、`road/place`、`road/saveddata`、`road/transport` |
| `magic` | 施法系统：法术定义/决策脑/施放/光束/法阵/效果 | magic | `magic/cast`、`magic/spell`、`magic/effect`、`magic/circle` |
| `task` | 自动化引擎：蓝图 DSL/任务池/调度/执行 + op 原子操作 + 零 MC 运行内核 | task + op + core(ECS/组件/边界) | `task/engine/dsl/pool/scheduler/source`、`task/op`、`task/kernel` |
| `warehouse` | 经济存储：物品+元素银行/预约/契约 | warehouse | `warehouse/bank`、`warehouse/manager`、`warehouse/menu` |
| `element` | 7 元素值数据/查询层（玩法域，非 foundational） | element + shared/data.ElementType(并入) | `element/mapping`、`element/item`、`element/audit` |
| `items` | 纯物品容器（无系统内核）：各域剥出的物品类。各物品薄（仅 use/interact 派发到域服务），items→npc/warehouse/magic/element 出向依赖按直接调用（正常） | wand/compass/guidebook + scepter/ring 物品 + SpellItem + 便携终端 | `items/wand`、`items/compass`、`items/guidebook`、`items/scepter`、`items/ring`、`items/terminal` |

### foundation/（跨域基建，7 子包）

| 包 | 职责 | 收编 |
|----|------|------|
| `foundation/ui` | **去堆核心**：公共 Screen 框架（模块组装、数据驱动）+ 各建筑 Screen 塌缩成的数据驱动窗口 + 共享控件/theme/markdown/guidance/skin | shared/ui + 各建筑屏幕（塌缩）+ overview 客户端飞行/交互总控 |
| `foundation/networking` | **仅包基建**：AbstractPayload 基类/codec/注册 + 真跨域同步包（BuildingAreaSync/RoadAreaSync/ScreenFeedback/ParticleBurst/MagicCircleCast 等） | shared/network（基类+跨角包） |
| `foundation/registry` | 注册门面(DeferredRegister) + WandscapeConstants + WandscapeDataRegistry + SoundEvent(唯一注册点) | shared/registry + engine/sound + dataconfig |
| `foundation/ui/render` | **仅跨域共享视觉工具**（无域归属）：RenderUtil、BuildingGhostRenderer+VboCache、SpeechBubbleRenderer、WandscapeHighlightRenderer、BuildingPreviewRenderer | shared/client/render + shared/ui/util（共享者） |
| `foundation/saveddata` | SavedDataUtil + 抽象基类（收 15 份样板） | 各域 SavedData 样板 |
| `foundation/log` | Log 统一过滤 | shared/log |
| `foundation/util` | **仅跨切纯值类型**（零 MC）：GridPos/ResourceStack/ResourceId/... + **点/向量合并后 int 点类**。**NPC 属性值类型不在此**（AttributeType/AttributeModifier→npc） | core/types(跨切者) + 点/向量自造族合并 |

> **统一裁决（贯穿 ui/networking/render，用户拍板）——"基建/框架/去堆目标收 foundation，域特性留域"**：
> - **foundation 收**：真公共基建（包基类、Screen 框架、共享控件、共享视觉工具、SavedData/log 工具、跨切值类型）+ **去堆塌缩目标**（各建筑 Screen → 数据驱动窗口）+ 真跨域同步包。
> - **域留**：实体/域特性渲染器（WandscapeNpcRenderer、RoadPlacementRenderer、BuildingAreaRenderer...）、域特性网络包（SplineBuild/AltarCast/TavernRecruit...）、域特性 Overlay/Menu（RoadStudioOverlay、TaskManagementOverlay、BuildingSelectionOverlay、NpcMenu、WarehouseMenu）。
> - 这些"域留"不是 tech 镜像（不是每域一套 client/server/network/data），而是该实体/特性的配套；若硬收全，renders 从实体撕开、packets 从域特性撕开、domain UI 从域撕开，foundation 反向认识全部域。

### compat/
- jei/curios/ironspellbooks 独立顶层（compileOnly 门禁/插件发现隔离）；**修** `WandscapeNpc→IMagicSummon` 直触泄漏（走 compat 接缝）。

### impl/（薄装配/门禁）
- `@ApiStatus.Internal`：`WandscapeBootstrap`（原 EngineBootstrap，唯一装配点）+ 公开 api 实现注册。**解散 `WandscapeEngine` 静态定位器**（getXxx() 搭桥本体，36 跨域引用）——消费方直接 new/直调，不再经 getter。

### api/（公开契约，5-7 接口 + 公开事件）
定位：addon/整合包作者没有它写不出来才留；内部 use 主导的删接口、消费方直连实现类（Tier 4 走）。
```
api/
├── colony/    ColonyApi（查殖民地状态/等级）        ← 交互面板需要
├── building/  BuildingApi（仅查询；place/demolish/cancel 内联）
├── element/   ElementApi（查方块/物品元素 worth）
├── warehouse/ WarehouseApi（存/取/查询 物品+元素）
├── road/      RoadApi（getNetwork）
├── wand/      WandApi（getWandPresetId/Color/Modifiers）
├── magic/     SpellcastingApi（addon 魔法/施法集成）
├── (可选) npc/NpcApi、tourist/TouristApi —— 视 addon 需求定，不默认入面
├── event/     公开事件（真事件流：Colony 生命周期/Raid/Tourist 到离/Element 变化/Warehouse 变化）
└── WandscapeApis  静态注册表（瘦身：只留真公开 getter 作 addon 入口，删 ~15 套 set/样板）
```
**砍削**（内联回功能域）：`HouseApi`（市民系统已删，实质死码——仅 WandscapeApis 注册、零消费，Tier 1 删）、`GuideProgressApi`、`ColonyMetricsApi`、`TavernApi`、`ScepterApi`。`MageWandItem/NpcBindingItem` 是标记接口非契约，随物品去 items。**最终存留以 Tier 2e 引用计数定死**（ColonyApi 43 / BuildingApi 39 内部重度 → 可能只留查询面或直连实现类）。

**稳定机制**：接口新增 default（二进制兼容）、淘汰 `@Deprecated(since, forRemoval)`、不静默删。

### 目标形态约束规则（替代现有「代码组织约定」）
1. **域内按功能块/机器切，不设 client/network/data 镜像子包**（决策 #2）。但**基建/框架/去堆目标收 foundation**（Screen 框架、包基类、共享控件/工具/值类型），**域特性（实体渲染器、域特性网络包、域特性 Overlay/Menu）留域**——它们非 tech 镜像（不是每域一套 client/server/network/data），而是该实体/特性的配套；硬收全会让 foundation 反向认识全部域。
2. **跨域直接调用普通类 = 正常**；防火墙只在 api 面。npc↔magic 双向耦合接受（代码可读优先，不为此加接口/事件环）。
3. **一个概念全地图唯一命名类**（增量归属约束）：NPC 属性→`npc/attributes/NpcAttributes`；7 元素→`content/element`；点/向量→`foundation/util` 一个 int 点类（Tier 3 合 GridPos/PathPoint/BlockOffset/XZPoint）。同域别处只引用、不清写。
4. **纯逻辑不 import MC** 为唯一硬边界：零 MC 内核（task/kernel + task/op + 纯值类型）保持可单测；MC 适配（boundary 实现/渲染/网络/注册）在其边界类内。
5. **数据真相当代码/生成物**：配方等先 datagen 试点（决策 #5）；不维护手写镜像。
6. **mixin 随各自域**（building/colony-raid/road/foundation-ui），不进全局 foundation/mixin 桶；**command 随各自域**（debug 命令后续清理）；gametest `ElementAuditRunner` 归 element。

**净减量核对**：content 11 域 + foundation 7 子包 + api 5-7 接口，对应旧 29 顶层包 + core/engine/shared 桥层解散。目标：建筑加按钮碰 3 文件（UI 框架 + 数据驱动）；属性/品类改动碰 1 个命名类。

### 文档裁决
- **删 `architecture/` 整树**（自认过时的历史快照）。
- **三镜像合一**：`docs/architecture.md` + `docs/modules/` → 一份 `docs/packages.md`（包地图 + 职责 + 坑），文件头写死规则"改包即改它"。
- `docs/decisions.md` 只留真决策；`docs/plan/` 下已完成的一次性设计标记状态；`docs/gaps.md`、`docs/bugs/` 保留（排查清单是真有用）。
- **新文档不产独立任务**：改包/改功能顺手回填 packages.md 对应小节，与代码同 commit（纯文档任务才用 `doc:`）。
- 数据真相放代码/生成物：配方等先试点 datagen（见决策记录 5）。

## 四、决策记录（本次已拍板）

| # | 决策 | 选择 | 理由/备注 |
|---|------|------|----------|
| 1 | 顶层形态 | **Create 风味**：`api/content/foundation/compat/impl` 五顶层 | 域心智模型保留 + 网络/UI 全局 + 顶层降到 5。MineColonies 三包也能活，但复用 `core` 名与"拆 core"目标冲突 |
| 2 | 域内切法 | **按功能块切 + 网络/UI 收全局** | 三家解剖一致否定现有"域内分 client/network/data 子包"约定；**同步改 CLAUDE.md 对应条款** |
| 3 | UI 目标 | **公共 Screen 框架 + 建筑窗口数据驱动/模块组装，硬目标** | MineColonies（模块窗 4 个）/Botania（2 GUI）双证 |
| 4 | 持久化 | **维持 SavedData，抽工具收样板，不迁格式** | 换 Capability/data attachment 动存档格式，撞高兼容红线；存格不动，只减样板 |
| 5 | （待议）datagen 推广 | 先配方试点，样板可行再铺 | 最大工程决策之一，本阶段只试点，不铺全 |
| 6 | 兼容代码处置 | **纪律优先**，不追求删光：开发期不承诺存档兼容、禁止新增无版本号兜底分支（CLAUDE.md「数据格式与兼容纪律」）；存量 ~400 行/22 文件只清不增，随 2a（CastBrain 死代码）/2c（SavedData 收敛）等既有步骤顺手清 | 兼容成本按版本数指数增长，删码治标、立规治本 |

## 五、修改（Tier 阶梯）

**Tier 0 基线**：build + test 全绿、git 工作区干净。

**Tier 1 删除（最高价值，最低风险，先做）**：0 引用类、无加载路径的废 JSON、陪葬测试。删除候选以「编译 + grep 字符串引用」双关判定，**不信图谱 in-degree**；反射入口类（mixin/事件订阅/@Mod/Config）与 `deprecated/` 兼容 JSON（building 旧档载荷，`ProjectionNetwork` 里 deprecated 建筑"隐藏但仍可用"）不删；无用 log 挪到横切 Log 治理。验收：build 绿 + 候选类名全仓 grep 零命中。具体见 `tier1.md`。

**Tier 2 改名（编译器当安全网）**：Mage→Npc 全仓统一，连注册 id/lang key/NBT 字符串一起 grep。验收：build 绿 + grep 旧名零命中。

**Tier 3 合并（用侦察重复清单做靶子）：**
- 属性五处→一处（纯逻辑 + 数据驱动 + 几个单测，样例等值验证）。
- 配方集群 6→1、点/向量自造族→vanilla 类型、SavedData 样板→`SavedDataUtil`、packet 样板→`AbstractPayload`。
- **裁决口**：点/向量改用 vanilla 是否违反"纯逻辑不 import MC"硬边界——按 road 路由算法是否仍需 JUnit 单测逐个裁决，不以偏概全。
- 跨包高引用只改名字不动结构。
验收：样例等值 + build + 相关测试绿。

**Tier 4 重组（最高风险，最后做；拆桥层到五顶层形态）：**
- 四个结构动作：① Api 门脸内联（内部 use >80% 的直接删接口、消费方直连实现类）；② ops 双层合一（engine/boundary + core/boundary 同类概念合并成一份）；③ 自制 ECS 拆除（core/ecs + core/component，15+ 文件）；④ 假事件内联（刷新 UI/通知型回调改直接调用，真事件流保留）。
- **铁的纪律：移动不改逻辑、改逻辑另开一步。** 每步一个 commit，独立分支/worktree 做，做完合回。
验收：目标包地图落地 + 移动前后纯逻辑测试全绿（行为不变即成功）。

**横切四件套**（各当独立 mini 阶段，结构稳定之后最顺）：
- **UI 去堆**（硬目标）：从现有建筑 Screen 抽公共框架 + 模块组装，1 个样板域验证可行性再铺全。
- **lang 分文件**：先切 1 个功能域做样板；顺手拆 switch-case。
- **Log 治理**：危险/无用 log 删除随 Tier 1；输出点审计（上屏/聊天只留错误+完成）放最后。
- **文档收敛**：删 architecture/、三镜像合一、立 packages.md。

## 六、复核

1. **编译是头号复核刑具。** build 绿 = 基本盘。
2. **测试是守门员不是简历。** 纯逻辑相关测试保持绿；测死代码的测试跟删。重构不改变行为，不因重构加新测试。
3. **三查清单**（每次 commit 前）：`grep 旧类名` 零命中；`grep 字符串 id` 与代码一致；改动前后行数对比应净减，净增说明在横移停下问值不值。
4. **运行复核短清单**：行为改动最多 1~2 条手测路径，能跑 GameTest 的用 GameTest，禁止 runClient。
5. **提交复核**：`git status` 只含本步文件；提交后立即更新 status.md。**同一改动返工两次停手重新侦察。**
6. **不变式红线**：高兼容不硬编码（方块/物品走标签 JSON）；纯逻辑不 import MC（Tier 3 有裁决口，裁决后白纸黑字记进 packages.md）。

## 阶段序列

| 阶段 | 内容 | 对应 | 完成判定 |
|------|------|------|----------|
| 0/1 | 问题确认 / 约定去专业化 | — | ✅ / 进行中（本次修订含：顶层形态五包、域内规则改、UI/持久化/API/文档决策） |
| <tier0> | **可信全项目文档摸底**（先重建认知，再改结构——现有文档全不可信，见 tier0.md） | tier0.md | packages.md 每节覆盖全包、无留白；旧三镜像删除；结论表 = content/ 分包依据 |
| 2a | 死代码清理 | Tier 1 | build 绿 + 候选类名全仓 grep 零命中；净减由候选表核算给出（**8 万行是 Tier 1–3 跨阶段目标，不是 2a 验收线**——Tier 1 纯删除到不了，主要回吐靠 Tier 3 样板收敛/重复删除） |
| 2b | Mage→Npc 统一 | Tier 2 | grep 旧名零命中 |
| 2c | 重复收敛（属性+配方+向量+双样板） | Tier 3 | 各集群收敛到一处 + 样例等值 |
| 2d | 拆桥层，五顶层形态落地 | Tier 4 | 目标包地图落地，行为不变 |
| 2e | API 收敛：api/ 顶层 + WandscapeApis 搬迁瘦身 | Tier 4 | api 包启用，内部 0 泄漏（impl 门禁） |
| 3–6 | UI(硬目标) / lang / Log / 文档收敛 | 横切 | 各有样板判据 |

**核心一句话**：先把确定性高的减法做掉，再用编译器兜底改名，合并用侦察清单做靶子，最后重组到五顶层形态——每一步有验收、能停；成果（文件/行数/包结构）由候选表核算得出，不靠拍脑袋。