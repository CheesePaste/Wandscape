# 桥层消解规则（shared / core / engine）

> 用途：给 AI（重构子代理）判断 `shared`/`core`/`engine` 三桥层里**每个类**该删还是该留、留到哪的**决策规则**。不是清单——AI 按规则逐类自查，产出分类表，人复核。
> 依据：`packages.md` 实地摸底（三包子包职责已核实）+ 决策 #7（归属看语义）。
> 范围：只处理 `shared`/`core`/`engine` 三个桥层。`compat` 保留、已搬入 `content/` 的域不在本规则范围。
> 使用：AI 对每个类跑一遍 §1 决策树，落到一个终点（删 / content / foundation / impl / api）；产出「类名 → 终点」表。

---

## 0. 一句话定性（先知道这三包是什么）

- **shared**：旧架构为"互不直接引用"搭的**桥层**，混着三类——真公共基建 / 具体功能实现 / 搭桥死码。要拆散。
- **core**：零 MC 的**运行内核的"框"**（ECS/组件/边界/值类型），不是独立层——它直接耦合 task/op。多数内容**随 task/op 域**走。
- **engine**：运行内核之上的 **MC 适配层**。只有 ~6 个真 MC 适配器，其余是装配/服务/定位器。定位器（WandscapeEngine）要解散。

**总原则（决策 #7）**：归属看**语义**（它是什么、服务谁），**绝不看"谁依赖它"**。跨域直接调用是正常，永不是把类挪出归属域的理由。判断时若陷入"但谁依赖它怎么办"，停下，按"它是什么"重判。

---

## 1. 决策树（每个类按此顺序问，命中即停）

### Q1 — 死吗？ → 命中则「删」
满足以下**全部**，才删：
- 全仓 `grep` 类名/字符串名 **0 引用**（非 self、非注释）
- **无反射入口**：类上无 `@Mixin`/`@SubscribeEvent`/`@EventBusSubscriber`/`@Mod`/`@GameTest`/`@RegisterExtension`，不进 `META-INF/services`
- **非数据/注册面**：类名/字段不出现于 lang/json/NBT 键/ResourceLocation/注册 id
- **非 SavedData 加载器**：无 `load(...)`（SavedData 靠 `load` 活着）

⚠️ **不可信工具**：codebase-memory 图谱 in-degree 会漏 class-level / event method-ref 边——已实测 0 引用抽出 `Config`/`AchievementService` 等全是活跃类。**只能「编译 + grep 字符串」双关定生死**。
⚠️ **必活红线**（看着像死但千万别删）：事件监听器、命令建议器、SavedData `load`、`@EventBusSubscriber` 类、注册进 game registry 的注册物。**误杀成本 > 漏删成本**——拿不准 → 标记 `?` 交给人工，不删。

### Q2 — 纯逻辑零 MC 吗（不 import `net.minecraft`/`net.neoforged`）？ → 是则大概率进 content
纯逻辑类（值类型/计算/解析/蓝图/评分/路由）**只有语义归属**，不在 Q4 基建范畴。问 Q3 落哪个 content 域。
- 这类是"纯逻辑不 import MC"红线样本，**保留其纯净性**，别为合并而引入 MC。

### Q3 — 服务谁？（语义归属 → 定 content 域）
问"它在为哪个玩法域干活"，答案到 `content/<域>`：
- 它是某域数据/规则/状态的一部分 → 该域（例：core 的 NPC 属性 → content/npc；值类型 GridPos 随用它的 task/op → content/task）
- 它跨多域共享 → **不是 content 域特性**，落到 Q4 基建
- 它是该域的**实体/网络包/Overlay/Menu**配套 → 该域（域特性**留域**，不是技术镜像）

### Q4 — 是跨域基建吗？ → 是则进 foundation
**判据：无域归属 + 被多域共享 + 是"框架/工具/样板/基类"**。且**foundation 不反向认识任何域**（若某类认识全部域，说明它不是基建，是装配→impl）。
进 foundation 子包：`log` `networking`(包基类) `registry`(常量/注册门面) `saveddata`(工具/基类) `util`(纯值类型/跨切工具) `ui`(Screen 框架/公共控件/theme/markdown) `ui/render`(共享视觉工具)。
- 已知典型：`shared/log`、`dataconfig` 数据加载器、`shared/ui` 公共控件/markdown 栈、`shared/network` 包基类与真跨域同步包、SavedData 工具。

### Q5 — 是装配/门禁/入口吗？ → 是则进 impl
**判据：@ApiStatus.Internal + 把各域"拼起来的接线员"**。它不是某域的一部分，是域间的装配点、初始化、静态定位器、bootstrap。
- 例：`EngineBootstrap`（→ `impl/WandscapeBootstrap`）、`WandscapeEngine` 静态定位器（**解散**：内部 getXxx() 搭桥消解为各消费方直接 new/直调，残余装配逻辑进 impl）。
- 不残留"服务定位器"反模式——getXxx() 被跨域调用的，一律直连。

### Q6 — 是真公开契约吗？ → 是则进 api
**判据：addon/整合包作者没有它写不出功能** + 被外部消费。内部 use 主导的**删接口、消费方直连实现类**（不产生伪公开面）。
- 只留 5-7 个真接口 + 真事件流 + 瘦身 `WandscapeApis`。纯内部"我想调你方法" → 直接调用，**不包装成 api**。

### Q7 — 是 MC 适配器吗？ → 随运行内核 / 留域（不进 foundation）
运行内核（task/op）↔ MC 的**真适配器**（`engine/boundary` 的 `WandscapeBlockOps/EntityOps/RitualOps/MovementOps` 等）。
- 它们是接口的具体实现，**服务运行内核**，放 content/task 旁（随 task 域），**不拆成独立 core/foundation 层**。接口 `BlockOps/EntityOps/...` 本身作为接缝保留，跟运行内核走。
- 不是"独立 core 层"——core 是框不是层，适配器跟它服务的 task/op 走。

### 兜底
Q1–Q7 都答不清 → 标 `?`，交人工复核。**宁可标注，不猜死**。

---

## 2. 五类目标判据速查

| 终点 | 一句话判据 | 典型（packages.md 实锤） |
|------|-----------|------------------------|
| **删** | 全 0 引用 + 无反射入口 + 非数据/注册面 | `shared/data/InterruptRecord`、`core/types/EquipmentPreset`、`ComponentStore`/`HashMapComponentStore`（core 外 0 引用单实现→内联）、`SuspensionContext`（0 引用→内联/私有） |
| **content/<域>** | 某域的数据/规则/状态/实体/特性/纯逻辑 | core 值类型（GridPos/AttributeType/属性→随用它的域）、op→content/task、MagicState 等「非 ECS 组件」（住 WandscapeNpc）、engine 各 MC 适配器→content/task |
| **foundation/** | 无域归属 + 跨域共享 + 框架/工具/样板/基类 | shared/log、dataconfig 加载器、shared/ui 公共控件+markdown 栈、shared/networking 包基类+真跨域包、SavedData 工具、SoundEvent 唯一注册点 |
| **impl/** | @ApiStatus.Internal 装配/门禁/入口 | EngineBootstrap（→WandscapeBootstrap）、WandscapeEngine 定位器（解散后装配逻辑）、公开 api 实现注册 |
| **api/** | 外部作者必需的真公开契约 | ColonyApi/BuildingApi/ElementApi/WarehouseApi/RoadApi/WandApi/SpellcastingApi + 真事件流。内部 use 主导的删接口直连 |

---

## 3. 必删 / 必活红线（重点防护，别踩）

**必活**（看着废但活着，删了运行期崩/存档炸）：
- SavedData 的 `load(CompoundTag, HolderLookup.Provider)` —— 加载器靠它活
- 事件监听器（`@SubscribeEvent` 方法）、`@EventBusSubscriber` 类、方法引用订阅
- 命令建议器（brigadier `suggest*`）
- `@Mixin` 4 个（building/raid/overview/road，全功能钩子，无一可删）
- 注册进 game registry 的任何注册物（实体/方块/物品/音效/自定义 attribute）
- `mixins.json`、`META-INF/services`、`@Mod` 主类、`WandscapeClient`
- `core` 的活 ECS：`World`/`System`/6 个真组件（Position/TaskExecutor/Inventory/ColonyMember/ColonyMetadata/NavigationState）——承重，改造（拆 god-object）不删
- `core/boundary` 8 接口——多调用方运行时接缝，非单次间接，不内联

**必解散**（不是删，是拆）：
- `WandscapeEngine` 静态定位器：getXxx() 36 处跨域引用，**消费方直接 new/直调**，定位器消失
- `WandscapeEngine.getWorld()` —— engine 外 0 调用者（World 是装配注入非懒取），直接删该 getter
- 假事件内联：刷新 UI/通知型回调改直接调用；**真事件流保留**（CustomEvent/域事件）
- `engine/boundary` 目录名——误导（混了真适配器+op 适配器+无关工具），随归类改名

**注意「非 ECS 组件」**：`core/component` 里 5 个不是 ECS 组件（`MagicState`/`EquippedMagicComponent`/`CastStrategyComponent`/`NpcTaskQueue`/`SuspensionContext`），住 `WandscapeNpc` 直接持有、各有 Test——"拆 ECS"一刀切会误伤，它们随域走（→ content/npc）。

---

## 4. 已知类别模板（packages.md 实锤，AI 可对照套用）

- **core/ecs（4）**：`World`(god-object，拆职责不删)、`System`、`ComponentStore`、`HashMapComponentStore`(→内联)。World/System 跟 task 运行内核走。
- **core/component（11）**：6 真 ECS 组件（随运行内核）→ 与 task 内核同处；5 非 ECS 组件 → content/npc。
- **core/types（17）**：值类型，绝大多数活，随用它的域；`EquipmentPreset`→删；纯逻辑可测（FriendlyForce/FollowAttackDecision/HostileMarkDecision 有单测，保留纯净）。
- **core/boundary（8 接口）**：BlockOps/EntityOps/MovementOps/RitualOps/EventBus/ColonyResourceAccess/ResourceAddedListener/ResourceShortageHandler → 接缝保留，跟 task/op 运行内核走。
- **core/event（4）**：CustomEvent/SimpleEventBus 是真事件流，保留；NarrativeEventTriggered/TaskCompleted 看是否真事件流，假事件内联。
- **engine/boundary（9）**：4 个 Ops 实现（真 MC 适配器→content/task）+ 3 个 op 适配器（AsyncTransformExecutor/WandscapeBlockInteractExecutor/ResourceRequestExecutor，→content/task）+ 2 个工具（BuildPlacementGuard/ProductionEligibility，→ 随其域）。
- **engine/service（10）**：跨域 MC 服务（ChunkLoadManager/ParticleService/SoundService/StatsService/GuideProgressService 等），只被 EngineBootstrap 注册（import 低≠死），随各自服务域或 content 对应域。
- **shared**：log/network 基类/公共控件/markdown 栈/真 DTO→foundation；panel/overlay/具体功能面板/API 实现→随域；死码删。
- **engine/colony**：ColonyLevelManager/ColonyActivation/ColonySavedData → content/colony。

---

## 5. 产出格式（AI 输出）

```
# 桥层消解清单（<包名>）

| 类（完整路径） | Q1死? | 终点 | 关键理由一句话 |
|---------------|------|------|---------------|
| ... | 否 | content/npc | NPC 属性值类型，npc 域规则 |
| ... | 是 | 删 | 全仓 0 引用，无反射/注册/data 面 |
| ... | ? | 人工复核 | SavedData load 签名特殊，拿不准 |

## 需人工复核项（`?` 汇总）
- 列出所有标 `?` 的类 + 为什么不确定
```

**交接承诺**：产出表里，每类的"终点"都能由 §1 决策树一条条推导回，理由可复核。拿不准的绝不硬判，标 `?`。

---

## 6. 不变式红线

- 高兼容不硬编码：拆解不碰方块/物品引用、不进 game registry 的注册物。
- 纯逻辑不 import MC：Q2 判"纯逻辑零 MC"的类，保留纯净性，别为合并引入 MC。
- 归属看语义不看依赖方向（决策 #7）：拆解全程的判据，不是"谁依赖它就要绕开"。
- 移动不改逻辑：拆解若涉及搬类/改引用，行为不变；改逻辑另开一步。
- 一次一 commit，每步 `./gradlew compileJava` 绿（测试不在此阶段要求，见 status 测试决策）。
