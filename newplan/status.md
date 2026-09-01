# 重构进度跟踪

> 此文件是重构进度的唯一事实源。每个阶段做完、每发现一个新问题，都更新这里。
> 起点：`newplan/why`（2026-08-29 写出的十条痼疾）
> 约定重写：`CLAUDE.md`（重构状态、代码组织约定、Testing、重构进行中）

上次更新：2026-08-30

## 阶段 0 — 确认问题（已完成）

写清了十条痼疾（见 `why`），并核实补充：

- 死代码确认：`core/types/NpcAttributes` 全仓库 0 引用。⚠️ **2026-08-30 tier0 实测推翻**：`NpcAttributes` 被 `core/CoreBootstrap` + `npc/internal/EntityComponentBridge` 引用（活类），「0 引用死码」系文档漂移，Tier 1 删除候选须避开它；真正 0 引用死码是 `core/types/EquipmentPreset`。同理 `magic/internal/CastBrain` 非死码（12 文件引用），plan「CastBrain 死代码」过期。
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

## 阶段 2 — 结构重构（进行中）

方案：`plan.md`（业余版重构方案）。目标：提取真正有用的模块，拆掉无意义分层。按风险分档排队：

- **Tier 1 删除**（2a，已完成）：0 引用类 + 私有死字段/死方法清理。**执行记录见 `tier1-candidates.md`**。完成判定：`./gradlew test` 与 `./gradlew build` 均绿，删除了 2 个 0 引用死类（`shared/data/InterruptRecord`、`core/types/EquipmentPreset`）、37 处私有未读死字段、13 处真死私有方法；经人工+编译+全仓 grep 严格核验保全了全部事件监听器、命令建议器与内部活跃调用。
- **Tier 2 改名**（2b）：**完成首批执行（2026-09-01）**，范围与方向经用户逐条拍板修正，见 `tier2-rename.md`。
  - **词表裁决（用户拍板）**：殖民地法师标准词 = **npc**（推翻原"→ Mage"方向，`wandscape_npc`/`npc_id`/lang `npc.*` 等字符串全保留）；法术 = **magic**（推翻"→ Spell"，`magic_id` 等字符串保留）；**wand 与 scepter 是两物**（NPC 建造法杖 `WandItem` vs 玩家右键权杖 `ScepterItem`），id/类名都保留；Citizen 查实为**游客角色标签**（TouristCommand 的 mage/citizen 两类游客），非 npc 内容。
  - **执行 11 项**（`compileJava` + `build -x test` 全绿）：撞名类改名 `System→EcsSystem`（task/ecs）、`AttributeModifier→NpcAttributeModifier`、`Inventory→NpcInventory`；删除死码 `StatsService`（空壳 TODO）、`HouseApi`（无实现）、`EquipmentSlot`（零使用）；`getFuckPureResourceId_NotContainFuckedNBT→stripBlockStateSuffix` 并收口 2 处内联同逻辑；api 钩子 `MageWandItem→NpcInteractHook`、`NpcBindingItem→NpcSneakInteractHook`；`GuideTestScreen→GuideScreen`（生产屏误名 Test）、`GuideTestPacket→GuideDocOpenPacket`（channel `guide_doc_open`）；RoadStudio/SplineEditor 重复包合一（删 `SplineEditorEnterPacket`，两命令共用 `road_studio_enter`）；building 域 39 处死 import 清理。
  - **核查后取消 3 项**（过度改名）：npc 域 Mage\* 类（`MageResume`/`MageAttributeRoller` 实为**法师游客**概念、`MageHut\*` 与玩家可见建筑 id `mage_hut` 绑定）；Scanner 继承（基 `CreativeScannerBlock`=完整创造版、子 `ScannerBlock`=裁剪生存版是**正确设计**非倒置，且类名与注册 id 匹配）；`MageResume#touristName`（简历即游客所留，字段语义正确）。
  - **文档审计结论**：原 §1.1「7 个编译期咬人」仅 3 个真成立（`System`/`AttributeModifier`/`EquipmentSlot`）；`I18n`/`Position`/`Activity` 全仓 0 处共 import MC 类，系假设性 churn 不执行。
- **Tier 3 合并**（2c，**已完成收尾 2026-08-31**）：消灭五处属性定义，收敛到一处（纯逻辑 + 数据驱动），样例等值验证。**2026-08-31 用户拍板重排**：先啃容易的（`parseElementMap` 配方集群/点/SavedData），全项目体检不做、等 Tier 4 重组后一个包一个包来；具体方案落库 `newplan/tier3.md`。**侦察修正两条**：① `UnlockRequirement` 双 record 系误报（`BuildingConfig.UnlockRequirement`=建筑解锁 vs `production/data/RecipeUnlockRequirement`=配方解锁，概念不同、消费方不同，剔除不合并）；② 点/向量 5 类非"逐字节相同"（SplineVec3=double 数学向量、XZPoint=2D、GridPos/PathPoint/BlockOffset=int 3D 但业务方法各异，BlockOffset 带 Gson 序列化）+ SplineVec3 被 JSON 引用——plan 原句"改用 vanilla 类型"与 CLAUDE.md"保留非 vanilla、合 int 点类"矛盾，以 tier3.md §5 裁决口（建议 A：不硬合，只抽真逐字节重复）为准，启动点=XZPoint 已读（2D、与 PathPoint 非逐字节冲突）。`ColonySnapshot` 双 record 移交 2e（API 收敛），Tier 3 不碰。**#1 parseElementMap 配方集群已完成（2026-08-31）**：抽 `element/internal/ElementMaps.parse(...)` 共享方法，7 处调用改走它、删 7 私有方法；**5 个配方 record 并不同构（组件/逻辑各异），未做 record 收敛**（推翻 plan"6 个配方 record 平行同构"）；**#3 SavedData 样板裁决完成（2026-08-31）**：**砍掉不收敛**。15 个真 SavedData 实为 10 个功能域各自的状态根（ColonyItemBank=仓库银行、RoadSavedData=路网、ColonySavedData=殖民地……），非"样板"；真逐字节重复仅 getOrCreate 内 ~7 行。跨 10 域改 13 文件省 ~100 行，净减量法则下性价比为负；RoadSavedData.load(tag) 单参、TaskPoolSavedData.load(pool,tag,level)、ColonySavedData 强写盘+overworld 形状特殊，硬收逼改签撞"不碰存档格式"红线。plan「foundation/saveddata 收 15 份各域样板」违反其自身拆分铁律（foundation 反向认识全域）系笔误。留待 Tier 4 重组归包时顺手。**收尾判据（用户拍板）**：Tier 3 做得再好，不拆 core/engine/shared 桥层（占 27.5%、254 文件）仍解决不了模组病根，故 2c 到此为止，转向 Tier 4。**遗留未核候选移交后续**（不再本阶段做）：`TouristEntity↔Shadow` 镜像、config record 群（Relax/Shop/Service/Wonder）、4 个 Async-op 执行器 pending+tickAll 样板、5 个 `record X(String id)` 同构——均进 Tier 4 重组时随包归顺，或另行小任务。
- **#6 craft/craft_spell 执行合一完成（2026-08-31）**：用户指出 Workstation/Craftingstation/Potionstation 的合成/合成法杖/合成杂物/抄写本质都是"扣元素→出物品"。实测 craft 系列已被 `CraftRecipeView.resolve` 统一，**craft_spell（抄写）漏在外面、与 craft 执行 80% 逐字节相同**。合并：`CraftRecipeView` 加 `resolveSpell()`（`magicId`→`outputNbt{magic_id}`）；`executeCraft` 加 `action` 参数共用并删 `executeCraftSpell`/`checkCraftSpellPreconditions` 整段（含 1 个死代码 `ItemStack` 构造）；`RequestProductionTaskPacket`/`ProductionEligibility` 的 craft/craft_spell 分支统一走 `CraftRecipeView`。**保留 craft_spell action 与 blueprint id**（魔法工坊专用 + UI transcribe），spell 不塞全局 `resolve`（防制作站造卷轴）。**行为对齐一处**：原 craft_spell 有"输出物品未注册即 return"防御，合并后与 craft 一致（无此防御）——spell 输出 `spell_scroll` 系注册物品，实际不受影响。`./gradlew build`+`test` 全绿。**synthesize 未并入**：推导式（element_mappings 动态查/无原料/5 ticks）≠ 配方式（JSON 配方/可选原料/1200 ticks）。
- **Tier 4 重组**（2d）：拆除 core/engine/shared 三层的搭桥类，按功能域重排。移动不改逻辑。**迁移作业单 `tier4-migration.md`（21 步整树搬 + magic 拆分明细 + 暂缓清单），骨架目录已建（5 顶层/11 content 域/7 foundation 子包）**。
- **Tier 4 骨架迁移完成（2026-08-31）**：21 个旧顶层功能包已搬入 content/（building+projection 合、npc+guard 合、task+op 合、overview+stats 合向 colony、raid→colony、scepter→items 整树、magic 拆 SpellItem→items）。**build 已修好**：IDEA move 漏改 11 处残留旧路径 import（Wandscape.java 3 处、WandscapeClient 1 处、Engine 侧 nav/transport/blueprint 3 处、搬走域自身跨子包 4 处），`compileJava` 绿。**归属判据（决策 #7）**：raid 归 colony（殖民地袭击，非 npc）、scepter 归 items（功能性物品含系统层）；scepter 系统层含 SavedData，被 npc/guard 消费属跨域直接调用。
- **Tier 4 shared/ 桥层消解完成（2026-09-01）**：依据 `tier4-dissolve-rules.md` 将 `shared/` 目录下 139 个类全部消解至 `foundation/`、`content/<domain>` 与 `api/`，彻底清空并移除了 `shared/` 顶层目录。全仓 grep `com.wsteam.wandscape.shared` 零命中，`./gradlew compileJava` 100% 绿。
- **Tier 4 core/ 与 engine/ 桥层消解完成（2026-09-01）**：依据 `tier4-dissolve-rules.md` 将 `core/`（46 类）与 `engine/`（42 类）共 88 个类全部按语义消解分配至对应功能域、基建与装配层，彻底物理清空并移除了 `core/` 和 `engine/` 顶层目录。全仓 grep `com.wsteam.wandscape.core` 与 `com.wsteam.wandscape.engine` 零命中，`./gradlew compileJava` 100% 绿：
  - **core/ 拆解（46 类）**：
    - `core/component` 中 5 个非 ECS 组件（MagicState, EquippedMagic, CastStrategy, NpcTaskQueue, SuspensionContext）及 `core/types` 中 8 个法师属性/战斗决策类型 → `content/npc/{component, types}`
    - `core/ecs`（World, System, ComponentStore, HashMapComponentStore）、`core/component` 6 个 ECS 组件（Position, TaskExecutor, Inventory, ColonyMember, ColonyMetadata, NavigationState）、`core/boundary` 8 接口（BlockOps, EntityOps, MovementOps, RitualOps 等）、`core/event`（CustomEvent, SimpleEventBus 等）、`core/types` 8 个值类型（GridPos, BlockType, ResourceId 等） → `content/task/{ecs, component, boundary, event, types}`
    - `CoreBootstrap`、`CoreBootstrapConfig`、`TemplateResolver` → `impl/`
  - **engine/ 拆解（42 类）**：
    - `engine/colony`、`ColonyApiImpl`、统计与区块加载服务（ColonyMetrics, Stats, Achievement, ChunkLoadManager, ChunkLeaseData）、环境音效 → `content/colony/{service, sound}`
    - `BuildingNoSpawnZoneHandler`、`BuildPlacementGuard`、`BuildingTaskSource`、`BlueprintConfigLoader` → `content/building/`
    - `ProductionEligibility` → `content/production/`
    - `ItemTransportManager`、`TransportItemEntity`、`TransportStartPacket`、`ResourceSupplySystem` → `content/warehouse/{transport, system}`
    - `HostileTargetingHandler`、`WandscapeAttributes`、`RoadWalkPlanner`、`WandscapeNavigation`、`WandscapeNodeEvaluator`、`NavigationSystem` → `content/npc/{nav, system}`
    - `GuideProgressService`、`GuideServerContext` → `content/items/service/`
    - `TaskPoolSavedData`、4 个 Ops MC 实现与 3 个 Op 执行器 → `content/task/boundary/`
    - 粒子与音效（ParticleService, SoundService, WandscapeSounds, ClientSoundHelper） → `foundation/{service, sound, registry}`
    - `EngineBootstrap`、`WandscapeEngine` → `impl/`
- **⚠️ 测试故意不搬、后续大删（用户拍板 2026-08-31）**：`src/test` 未随 src/main 搬——测试类仍散在旧顶层包，被测类已搬去 content/，导致 package-private 方法跨包访问失败（`compileTestJava` 100 个错误，如 ProjectileDodgeTest.willHit/MagicSpellExecutorsTest.meteorIntervalTicks/ProjectionClientStateTest.clampSlotIndex）。**决策：现在不搬测试**（数量过多、维护费高，CLAUDE.md「守门员不是简历」要删大部分），搬完等于搬了再删。故迁移完成判定 = `./gradlew compileJava` 绿（已达成），`./gradlew build`/`test` 暂不要求绿；测试在 main 稳定后单独大删。
- **暂缓清单**：command（归域+debug 大清理）、顶层 client（TransportItemEntityRenderer 随 transport）、mixin（各归各域，依赖域内结构定后）、gametest（ElementAuditRunner 归 element）——待域内结构定死后再归位。
- **桥层拆解规则文档 `newplan/tier4-dissolve-rules.md`（2026-08-31 立）**：给 AI 判断 shared/core/engine 三桥层每类去处的决策规则——§1 决策树（死?/纯逻辑?/服务谁/content?/基建?/装配?/公开契约?/MC适配?）+ §2 五类目标判据速查 + §3 必删必活红线 + §4 已知类别模板（packages.md 实锤）+ §5 产出格式。**用法**：AI 逐类跑决策树产出「类→终点」表（删/content/foundation/impl/api ），标 `?` 交人工；不逐文件读，靠规则自判。拆 shared/core/engine 时作为执行依据。
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
- 2026-08-30：**tier0 摸底产物落库 `newplan/packages.md`**（审核合并批 1–8 节稿，29 顶层包全覆盖无漏包，含 scepter；注 plan.md「30 顶层」为旧数、实际 29）。审核结论：① 承重断言全对（依赖方向 building↔projection 环 / raid→guard 仅 / stats 零 building、WandscapeNpc 84/63、guard 14、core 47/2421/0、engine 42/16、resources 1368/1188/2031/2032/52/14 逐字命中）；② 修正三处——点/向量自造族实为 **5 个（补 building/data/BlockOffset）**、NPC 属性收敛目标统一到 **npc 域 NpcAttributes**（原批 1 说 building、批 6 说 npc 打架）、guidebook 批 4/8 重复去重；③ **新揭 NPC 属性已漂移**：`MageAttributeRoller.roll` 给 MOVE_SPEED/ARMOR_VALUE 每级 +0.02/+0.5，但 `MageHutAttributes.SPECS` 声明 perLevel=0——「拆多文件必然漂移」实证，收敛硬理由；`NpcAttributes.defaults()`(30/0.3/1/1/1/5/200) 与 SPECS 中点不符。**tier0 阶段未完成**：旧三镜像（docs/architecture.md + modules/）删除待用户确认后执行。【全局结论表】= content/ 分包依据（8 功能域 + items + foundation）。
- 2026-08-30：**完成 Tier 1 死代码清理**（依据 `newplan/tier1-candidates.md` + 严格双重校验）：删除 2 个 0 引用死类（`shared/data/InterruptRecord`、`core/types/EquipmentPreset`）、37 处私有未读死字段、13 处真死私有方法；经人工+编译+全仓 grep 严格核验保全了全部事件监听器、命令建议器与内部活跃调用。`./gradlew test` 及 `./gradlew build` 均绿。更新 `tier1-candidates.md` 与 `status.md`。
- 2026-09-01：**Tier 2 改名候选全仓重扫完成，落库 `newplan/tier2-rename.md`（仅审计未改名，用户拍板先出文档）**。方法：机械 grep（撞名/词族/字符串面全局分布）+ 6 并行域子代理 + neoforge sources jar 逐一核实撞名。取代旧 `rename.md`（迁移前、仅参考）。要点：① 撞 MC/JDK 精确 7 件全存活（`ecs/System`↔JDK、`tourist/data/Activity`↔MC schedule.Activity、`npc/types/AttributeModifier`+`EquipmentSlot`↔MC、`foundation/ui/I18n`↔MC、`task/component/Inventory`+`Position`↔MC；其中 `EquipmentSlot` 全库零引用死码可删）；② 词族全量存活：Mage/Npc/Wizard/Citizen（实体 id `wandscape_npc`+42 lang key/语言+NBT `npc_id` 等）、Wand/Scepter/MageWand（物品 id 仍 `*_wand`）、Magic/Cast/Spell（NBT `magic_id`↔`spell_id` 并存）、Tourist/Visitor/Guest、PathPoint↔GridPos 同构双类、AtomicOp 209 处 op 层 vs `*Ops` 边界层、Scanner 继承倒置、TownHall 6 拼写、stats/metrics 三套；③ 新实锤：`RaidTownHall` 实为定位器、`GuideTestScreen` 生产级却叫 Test、`HouseApi` 无实现、`IBubbleTextProvider` 全仓唯一 I 前缀、`RoadStudioEnterPacket`/`SplineEditorEnterPacket` 双包 handleClient 逐行相同、`StatsService` 空壳死代码。文档按角度 1/2/3/4 分组 + §五字符串面风险分 A(纯Java安全)/B(网络id)/C(存档断档) 三档 + §六四批执行顺序建议。