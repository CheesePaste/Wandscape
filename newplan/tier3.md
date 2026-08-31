# Tier 3 具体方案：重复收敛（合并）

> 定位：plan.md「五、修改」的 Tier 3（用侦察重复清单做靶子）的**可执行版**。
> 在 plan.md 骨架（候选清单/裁决口/验收）之上，补齐：候选怎么核、每 cluster 怎么合并、怎么验证等值、怎么回滚。
> 依赖：Tier 1（死码清理）已完成；Tier 2 改名（Mage→Npc）**推迟到重组后**（用户拍板，见 status 2026-08-30 决策①）。
> 本文件为 tier1.md 的同族文档，格式沿用（范围/候选生成/执行/复核/红线）。

## 0. 一句话

把"确定性高、风险可控"的重复集群逐个收敛到唯一处，一次一个 cluster、一 merge 一 commit（可回滚），样例等值验证 + build 绿。**不追行数指标，只认候选表核算。**

**本次重排（用户拍板，2026-08-31）**：不再是 plan.md 原始的"属性五处→一处"打头，而是**先啃容易的**（`parseElementMap` 配方集群 / 点 / SavedData），**全项目体检不做**——模组重组（Tier 4）之后一个包一个包慢慢来。合并靶子凡属"横移/纯改名"的挪到对应步骤，不混进本 tier。

## 1. 范围（同类合并，别的都不做）

| 类别 | 内容 | 验收 |
|------|------|------|
| 重复方法 | 逐字节相同的私有静态方法（如 `parseElementMap`×7）抽成共享实现 | build 绿 + 原类内方法删除 + 行为不变 |
| 同构值类型 | plan 点/向量自造族 5 类（**裁决口见 §5**） | 收敛到一处 + build 绿 |
| 样板收敛 | SavedData 包装样板 15 份 → `SavedDataUtil`/抽象基类 | 每份迁移后 samples 等值 + build 绿 |
| 同名常量/镜像 | 3 组同名常量分两文件、TouristEntity↔Shadow 镜像（**tier0 未复核项**） | 待侦察确认后并入 |

### 明确不做 & 重要修正（防翻车）

1. **`BuildingConfig.UnlockRequirement` 与 `production/data/RecipeUnlockRequirement` 不合并**。两者名字像但概念不同：前者是**建筑解锁**（building 域，仅 `level` + `NONE`），后者是**配方解锁**（production 域，`minColonyLevel` + `fromJson`），消费方完全不同（`BuildingUnlockChecker` vs `RecipeUnlockChecker`/`WorkstationDataPacket` 等）。**tier0 复制的"UnlockRequirement 双 record"是误报，剔除。**
2. **点/向量 5 类不机械合并**。虽都是 `record(int x,y,z)` 同构，但方法语义各异（读 §5 裁决口）。plan 原句"改用 vanilla 类型"与 CLAUDE.md「保留非 vanilla、合单个 int 点类」互相矛盾——**以本文件 §5 裁决为准**，开工先读用法再定收敛粒度。
3. **API 面的重复（`ColonySnapshot` 双 record）留到 API 收敛（Tier 2e），Tier 3 不碰**。`BuildingContributionRegistry:279` 与 `shared/api/BuildingApi:58` 逐字节相同，但一处是内部、一处是公开契约——收敛会动 API 面，且公开契约的收敛哲理由 2e 统一裁决（删接口/直连实现/保留查询面）。`ColonySnapshot` 情形与它相同，**不在此做**，移交给 2e。
4. **不吃掉 plan 原始表述已过期的部分**：plan §二「重复清单」中"中轻"几项（config record 群、同名常量、镜像）多数**未复核**，凡未核实同构的，开工先侦察确认再决定合并，不许凭一句"看起来像"就合。
5. **不合并任何进 game registry / 被 JSON / lang / NBT / 资源路径引用的名称**——那是 Tier 2（改名）的活，与"合并值类型为一起"是两回事。合并只动 Java 类型/方法，若要动字符串 id，停下标记给改名步骤。
6. **禁顺手重构**：一批只动一个 cluster 的一个面向；同一 cluster 内部如需改动相邻类（改签名/删除），只做承载合并所必需的最小改动。

## 2. 候选怎么核（复用 tier1 的双通道，但重心不同）

Tier 3 没有"死不死"的生死判定，而是**"同不同构 / 值不值得合 / 合到哪"**三问。核验三步：

### 通道 A —— 同构判定（逐字节 vs 语义）

对每个候选集群，`diff` 关键方法**逐字节**比对：

| 判定 | 含义 | 处置 |
|------|------|------|
| **逐字节相同** | 方法体一字不差（如 `parseElementMap` 7 处） | 抽共享实现，删原私有方法 |
| **结构同构·方法不同** | record 字段同构但方法语义各有用途（点/向量 5 类） | 进裁决口 §5，不自动合 |
| **语义不同·名字撞** | `UnlockRequirement` 双 record 情形 | 确认概念不同 → 剔除，不合并 |
| **仅字段名/toString 差** | 值是同一物，只是每个类自己抄了份 | 统一到唯一类 |

三问（每 cluster 开工前必答）：
1. **同吗？** 逐字节 diff 确认。相同 → 合；语义不同 → 停。
2. **值吗？** 合了让下次改动更省力吗（plan §一 净减量法则）？纯改名/纯打包不叫合并，是横移。
3. **合到哪？** 用「目标形态」裁决（§3），不靠主观。

### 通道 B —— 引用复核（合什么由它定）

- `grep -rn "\b<类名>\b" src/` 拉全引用清单，**决定收敛后目标类的包与可见性**（跨包高引用 → 放公共位置；单域 → 放域内）。
- 若目标是**公开契约**（api/ 面）或**被 JSON/lang/NBT 引用**，停下确认是否归属 API 收敛（2e）或改名（2b），不在这里硬做。
- 保留**反射入口**白名单（mixin/@EventBusSubscriber/@SubscribeEvent/@GameTest），别动。

**只有 A 判"逐字节相同" ∩ B 判"非公开契约/非字符串引用" = 才入「待合并表」。** 待合并表一行一条：文件路径 / 行数 / 通道A同构类型 / 通道B收敛目标。

## 3. 合并方向（用目标形态裁决，不是按现状）

plan.md「拆分铁律」：**基建/框架/去堆目标收 foundation，域特性留域**。合并收敛的目标包照此定：

| 合并集群 | 收敛目标 | 理由 |
|---------|---------|------|
| `parseElementMap` 配方解析 | `production/recipe` 内一个共享解析器（或 ElementMappingConfig 收敛，开工读用法定） | 配方是 production 域特性，留域；解析器不跨域 |
| 点/向量自造族 5 类 | **裁决口 §5**（可能：4 个 int 类合一个 `GridPos`+`SplineVec3` 独立；或全合） | 跨切值类型 → plan 写 foundation/util，但方块偏移/路径点是域特性，需判 |
| SavedData 样板 15 份 | `foundation/saveddata/SavedDataUtil` + 抽象基类 | 基建样板 → foundation |
| packet 静态 handler 样板 | foundation/networking 的泛型基类；域特性包（SplineBuild/AltarCast/TavernRecruit）**留域** | plan「基建收 foundation、域特性留域」 |

**通用裁决规则**（贯穿本 tier，用户拍板）：合并只收敛**方法/样板**，不把域特性实体（渲染器/网络包/Overlay/Menu）硬收进 foundation——`SplineBuildPacket`、`RoadPlacementRenderer`、`RoadStudioOverlay` 这类留域，foundation 只收"包基类/共享控件/值类型/超域同步包"。合并如果因为"想收进 foundation"而开始认识全域，方向就错了。

## 4. 执行（一个 cluster / 一个 commit，按此序先易后难）

| # | cluster | 难度 | 内容 | 验收 |
|---|---------|------|------|------|
| 1 | **配方 `parseElementMap` 集群** | 易（逐字节相同） | ✅ 完成：抽 `element/internal/ElementMaps.parse(...)` 共享方法，7 处调用改走它，删 7 个私有方法（6 处 `HashMap` + 1 处 `LinkedHashMap`）；**5 个配方 record 并不同构（组件/逻辑各异），未做 record 收敛** | ✅ build+test 绿；`grep parseElementMap src/` 零命中；既有测试覆盖 `fromJson→ElementMaps.parse` 等值 |
| 2 | **点/向量自造族** | 中（裁决口） | ✅ 裁决 A：**不合并**；5 类分属三系统（GridPos=task 内核 / BlockOffset=building / PathPoint+XZPoint+SplineVec3=road），无逐字节重复 | ✅ 无改动，归包留待 Tier 4（纯移动不改定义） |
| 3 | **SavedData 样板 15 份** | 中 | ✅ 裁决：**砍掉不收敛**。15 类实为 10 个功能域各自的状态根（非"样板"），真逐字节重复仅 getOrCreate 内 ~7 行（Factory+computeIfAbsent）；跨 10 域改 13 文件省 ~100 行，净减量法则下性价比为负，且 RoadSavedData.load 单参/TaskPoolSavedData.load 多参/ColonySavedData 强写盘形状特殊硬收逼改签撞红线。plan「foundation/saveddata 收 15 份各域样板」违反其自身拆分铁律（foundation 反向认识全域）。留待 Tier 4 重组时归包顺手统一 | ✅ 无改动 |
| 4 | packet 静态 handler 样板（~30 份） | 大（最高风险） | 抽泛型 `AbstractPayload`；**域特性包留域** | 泛型基类 + build 绿 + 相关测试绿 |
| 5 | （移交 2e）`ColonySnapshot` 双 record | — | **不做**，见 §1 修正 3 | 移交给 API 收敛 |
| 6 | **craft/craft_spell 执行合一** | 中（行为敏感） | ✅ 完成：`CraftRecipeView.resolveSpell()`；`executeCraft` 加 `action` 参数共用，删 `executeCraftSpell`/`checkCraftSpellPreconditions` 整段；packet/eligibility 分支统一走 `CraftRecipeView` | ✅ build+test 绿；行为不变（制作站 craft 仍查不到 spell，服务端防护不破） |

> **侦察 #6 实录（2026-08-31）**：用户指出 Workstation/Craftingstation/Potionstation 的合成/合成法杖/合成杂物/抄写本质都是"扣元素→出物品"。实测：craft 系列（法杖/杂物/药水）已被 `CraftRecipeView.resolve` 统一；**craft_spell（抄写）漏在外面，与 craft 执行 80% 逐字节相同**（仅 recipe 解析源 + 输出 NBT 构造差异，craft_spell 把 `magicId` 手写进 CompoundTag，恰可转成 `CraftRecipeView.outputNbt`）。craft.json 与 craft_spell.json 两个 blueprint 逐字节同构（仅 action 一词 + 文案）。**synthesize 不可并入**：推导式（element_mappings 动态查/无原料/5 ticks）≠ 配方式（JSON 配方/可选原料/1200 ticks），是 decompose 的另一端。**裁决**：craft/craft_spell 合一执行，但**保留 craft_spell action 与 blueprint id**（魔法工坊专用 + UI 显示 transcribe），spell 不塞进全局 `resolve`（否则制作站能造卷轴，服务端防护破）。

> **侦察 #1 实录（2026-08-31）**：plan §二「配方 record 集群」判断过期——5 个配方 record（Synthesize/Misc/CraftWand/CraftSpell/BrewPotion）**组件与逻辑全不同**（`SynthesizeRecipe.totalCost`/`calculateChannelTicks`、`CraftWandRecipe.outputNbt+preset_id`、`BrewPotionRecipe.parseNbt/inputItems`），不满足"平行同构"，**不可收敛成 1 个 `CraftRecipe`**。真正逐字节重复的只有 `parseElementMap` 这一个方法（7 份，6 份 `HashMap` 一字不差 + `ElementValueGenerator` 用 `LinkedHashMap` 保审计确定序）。收敛点：`element/internal/ElementMaps`（element 域；production 已依赖 element，无环）。

**实际开工顺序由你我逐个敲定**，本表只是按"易→难"的推荐。每步：

1. **侦察**：读该 cluster 全部文件，填通道 A 同构判定 + 通道 B 引用清单 → 产出「待合并表」小节。
2. **合并**：按 §3 裁决收敛，改目标类 + 更新所有引用（`mvn`/`./gradlew` 编译兜底）。
3. **验证等值**：纯逻辑 cluster 补/跑样例 **等值测试**（合并前后同输入同输出，不新造行为）；不涉纯逻辑的用 build + 原有测试。
4. **commit**：`refactor: <cluster> 收敛（N→1，M 行）`，一个 commit 一个 cluster，可单独 `git revert`。
5. **回填**：更新本文件的待合并表核算 + `newplan/status.md`。

### 工具（沿用 tier1 §4 结论）

- **编译是头号刑具**：`./gradlew build` 每一批必绿。
- **等值验证**：纯逻辑用 JUnit 5（不加 Mockito/AssertJ），只写代表用例（plan Testing 节）。
- **无 MC 专用合并工具**；点/向量类的手写数学向量（SplineVec3）若要验等值，`diff` 与 vanilla `Vec3` 语义是否一致即可，不靠工具。
- **不需要 ProGuard**（那是 Tier 1 死码复核的活）；本 tier 用 diff + grep + build 已够。

## 5. 裁决口：点/向量自造族归去哪

**问题（plan 原文有矛盾）**：plan.md §三 写"改用 vanilla 类型"，但 CLAUDE.md「点/向量自造族 5 个、保留非 vanilla」「首选合并成单个 int 点类」——两条冲突。且本次侦察实读 5 个类发现：

| 类 | 坐标 | 关键方法/用途 | 语义 |
|----|------|--------------|------|
| `SplineVec3` | double x,y,z | add/subtract/scale/length/normalize/dot | **数学向量**（样条计算，刻意零 MC） |
| `GridPos` | int x,y,z | manhattanTo/distSq/add | 世界格点距离 |
| `PathPoint` | int x,y,z | xz()/manhattanXZTo | 道路路径存储（含 Y，垂直连接/挖掘） |
| `BlockOffset` | int x,y,z | toKey()/of()/Gson Deserializer | 建筑结构相对偏移（JSON `[x,y,z]`） |
| `XZPoint` | int x,z | manhattanTo | 2D 平面（障碍/占用/MST 拓扑），与 PathPoint 非逐字节冲突 |

**关键**：5 类**不是"逐字节相同"**，是"结构同构、方法各有用途"。SplineVec3 独立（double、数学运算）；XZPoint 独立（2D）；GridPos/PathPoint/BlockOffset 是 3 个 int 3D，但各有业务方法且**类型被 JSON/Gson 引用**（BlockOffset 有 Deserializer）。强行"合一个 int 点类"会：丢语义（manhattanXZTo 平面距离 / BlockOffset 序列化）、引 run-time JSON 兼容问题、且污染"纯逻辑不 import MC"边界裁决。

**裁决建议（待你拍板，开工第 2 步前定）**：

- **A（推荐）**：`SplineVec3` 独立保留（纯数学向量，重构/改名归 Tier 2）；`GridPos`/`PathPoint`/`XZPoint` 3 个 int 点类**暂不合并**，只在其 domain（road / core / building）内部各自唯一（若确认 2 个 int 点类逐字节同构才合，否则不削）。**合并只在"逐字节相同"时做**，不为了"看起来像"而硬合。
- **B**：在 domain 内把 `GridPos`/`PathPoint` 合并成一个通用 `GridPos`（如果 diff 确认只差一个平面距离方法，删那个方法改用 `GridPos` + 一个 `distXZ`），`BlockOffset` 因 Gson 序列化独立。
- **C**：按 plan 原句，全合并成一个 int 点类——**不推荐**，会因 JSON 引用丢兼容 + 丢语义。

> 启动点：第 2 步开工前，读 `XZPoint` 确认它是否与 `PathPoint` 冲突，再定 A/B/C。**建议 A**（不硬合，只把真正的逐字节重复抽掉，其余等 Tier 4 重组再定归包）。

## 6. 复核清单（每 commit 前）

- [ ] TG: `./gradlew build` 绿（编译是头号刑具）
- [ ] 涉纯逻辑的 cluster：`./gradlew test` 绿 + 样例等值（同输入同输出）
- [ ] 每个被删私有方法/类：`grep -rn "\b<名字>\b" src/` 仅剩收敛后的目标引用
- [ ] 每处引用已改到目标类；改签名/删除只限承载合并所需最小改动（禁顺手改）
- [ ] 非公开契约、非 JSON/lang/NBT 引用被合并（若动了字符串 id，标记给改名步骤）
- [ ] 反射入口（mixin/event/@Mod/@GameTest）未被误碰
- [ ] `git status` 只含本步文件；净增行数 → 停下问值不值（横移嫌疑）
- [ ] 更新本文件待合并表核算 + `newplan/status.md`

## 7. 不变式红线（沿用 plan.md）

- 高兼容不硬编码：合并只动 Java 类型/方法，不碰方块/物品引用、不进 game registry 的注册物。
- 纯逻辑不 import MC：合并纯值类型时，若目标类需 import MC 才能"顺便合并"→ 停下，分开合并（保"纯逻辑不 import MC"硬边界）。
- 禁顺手重构：一批只动一个 cluster 的一个面向。
- 合并不挪包：值类型/样板的"归包"属 Tier 4 重组；Tier 3 只把同类收敛到一处，不跨包大幅搬家——**除非**该 cluster 本身就是跨域样板（如 SavedData → foundation/saveddata，这类按 §3 裁决直落目标包）。
