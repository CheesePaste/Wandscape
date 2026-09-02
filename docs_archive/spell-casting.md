# NPC 施法决策层 — 设计文档

> **状态（2026-08-07）**：**P1 数据与分发、P2 决策集中、P3 玩家策略 + 条件、P4 死亡留存 + 复活、P5 祭坛施法均已实现**（见文末实施表）。
> 分类已定案为 6 类（SINGLE_TARGET / AOE / DEFENSE / SUPPORT / SPECIAL / ALTAR）。
> 本文既记录已落地结构，也是各阶段的实施蓝图。

## 一、现状问题：为什么需要决策层

魔法门控已经就绪，但**决策是散的**。当前 NPC 施法的真实路径：

| 调用方 | 触发 | 直接做的事 |
|---|---|---|
| `guard/executor/GuardCombat.engage`（GuardCombat.java:53） | 守卫任务：建筑区内最近敌对 | `MagicCaster.castNpcAt` 射 beam |
| `guard/executor/SelfDefenseExecutor` | 自防御：仇恨优先→半径内最近 | `MagicCaster.castNpcAt` 射 beam |
| `engine/system/NavigationSystem.switchToRitualTeleport`（NavigationSystem.java:256） | 寻路失败 | 直接 `npc.tryCastSpell("teleport", …)` |

**已解决**：P1/P2 后三者不再各自选魔法——守卫/自防御经 `CastBrain` 选魔法再按 id 分发，CD/蓝/射程/视觉全部数据驱动（beam/teleport 已迁入 `magic_spells/*.json`）。

**仍缺**：NPC 无"会哪些魔法"概念（SpellbookComponent 未做）；CastBrain 只有 `hasTarget` 布尔，支撑不了防御/治疗/AOE 的**条件决策**；NPC 死亡后无留存，复活无目标可指向。

## 二、目标

1. **任意数量魔法，新魔法零改动接入**（数据驱动，仿 `MagicCircleSpec` + dataconfig）。
2. **玩家可控但不繁琐**：只调一个"策略/优先级"维度，不写脚本。
3. **原子化**：决策层通过 `WandscapeApis` + 事件通信，不跨包引用（CLAUDE.md 铁律 1/2）。
4. **保持 `core/` 零 MC 依赖**：魔法定义/策略/快照是纯数据 record；效果实现按"引擎是请求层、适配层是实现"放 `engine/` 或各模块 `internal/`。

## 三、参考模型谱系（知名游戏怎么管理 NPC 施法）

玩家对施法决策的控制力，从高到低：

| 方案 | 代表 | 机制 | 对本模组的适配度 |
|---|---|---|---|
| 全条件脚本 | FFXII Gambit / Dragon Age: Origins | 玩家写"HP<30%→回血"逐条判定 | 最可控，但 UI/翻译/理解负担最重；殖民地 NPC 该自治 |
| **分类权重** | 龙之信条 随从倾向 | 玩家调**分类**（救死扶伤/输出/实用）的排名，AI 自己挑具体技能 | **最贴合"分类+优先级"**；只排 3~4 个分类，UI 直觉 |
| 战术模式 | 女神异闻录 | 玩家只选"全力攻击/省蓝/辅助"策略，AI 在策略内自行决定 | 简单直觉，适合做**预设** |
| 每技能开关 | WC3/DotA 自动施放 | 每个技能一个勾选 + 内置触发条件 | 最轻量，适合做**兜底**/微调 |
| 纯 AI | 宝可梦 | 按得分表自动选 | 零负担但玩家无操控感 |

**结论**：本模组采用 **龙之信条（分类权重）+ 女神异闻录（策略预设）+ WC3（每技能开关）** 的组合——玩家只动一层（策略 + 优先级列表），其余 AI 自治。全条件脚本（FFXII）对 Minecraft 殖民地模组过重。

## 四、三层决策架构

```
L0 硬性覆盖（不可配置，保命/不打断）
  ├─ 自身或范围内友方血量 < 阈值 → 治疗魔法（无视玩家策略）✅ 已实现：GuardCombat.l0EmergencyHeal
  │                              （自身或治疗半径内友方血比 < 0.5 且会 heal → 强制施奶；
  │                                heal 以自身为圆心，可同时奶到范围内友方与自身）
  ├─ LOS 被方块挡住           → 停止施法、转寻路（现有 GuardCombat 逻辑）
  ├─ LOS 可见但目标进入威胁距离  → 战斗风筝：后撤拉开距离（边走边打，光束独立跟随；GuardCombat）
  ├─ 自身血量比例 < fleeHpThreshold(0.30) → 低血逃跑态：走位距离改用 fleeStart(12)/fleeStandoff(18)，
  │      LOS 被墙挡也继续后撤不走近（保命优先；阈值 Config.guard.fleeHpThreshold 可调，GuardCombat）
  ├─ 附近可见敌数 ≥3          → 群殴规避：往敌方质心反方向走位（GuardCombat）
  ├─ 施法互斥锁被占用          → 不打断当前施法，等下一轮
  └─ 导航失败需传送           → 走 utility 传送（导航回退，属硬性路径）

L1 玩家策略层（可配置）
  ├─ 每个 NPC 一个策略预设（均衡/火力/支援/防御/自定义）
  └─ 策略内一条可编辑的【施法优先级列表】
      每 tick 从上往下扫：第一个"CD 已过 + 蓝够 + 有有效目标 + conditions 满足"的魔法即施放

L2 兜底层
  └─ 列表全不可施 → 普通攻击（✅ 已实现：GuardCombat.normalAttack，物理 5 伤基础 / 2s 攻速 / 不耗蓝，
     白色 CastBolt 粒子线，同建筑交互射线） / 走位 / 待命
```

关键点：**L0 必须永远先于 L1**（保命逻辑不能被玩家把治疗调成最低优先级而饿死）；L1 玩家改的是**分类或魔法间的排序**，不是触发条件本身。

## 五、数据结构（JSON 数据驱动）

### 5.1 `MagicDef` — 魔法定义（仿 `MagicCircleSpec`：record + fromJson + dataconfig）

```jsonc
{
  "id": "beam",                 // 魔法 id（tryCastSpell 的 magicId key）
  "category": "normal",         // 分类（性质）：normal / special / altar；敌数门控看策略组，不看这里
  "default_group": "single_target", // 可选：默认策略组（single_target/aoe/defense/support），缺省 → support 兜底
  "mana_cost": 50,
  "base_cooldown": 400,         // tick；施法互斥锁（法阵/引导/光束全程）结束后才开始倒计时的冷却，按 SPELL_SPEED 缩短
  "range": 32,
  "target_mode": "hostile_nearest",
  "altar_only": true,            // 仅祭坛可施放；NPC 直接施法（CastBrain）永不选中
  "altar_cooldown": 600,         // 该魔法在祭坛侧的冷却（tick），按祭坛独立存放
  "altar_duration": 160,         // 祭坛引导/施法时长（tick），应与其法阵 spec 的 durationTicks 对齐
  "conditions": { ... },        // 可选：内置触发条件（见 5.2），缺省 = 无条件
  "effect": {                   // 每魔法专属参数，由对应效果执行器消费
    "circle_id": "arcane_hexagram",
    "color": "0xFFA8E0FF"
  },
  "description": "…"            // 可选：玩家可读的介绍文本（魔法卷轴的 JEI 信息页用），缺省 = null
}
```

**分类定案（3 类）**——`category` 只表达法术**性质**，**不决定敌数门控与预设排序**（那两者由法术所在策略组驱动，见「敌数门控」与 5.4）：

| 分类 | 覆盖 | 典型魔法 | 性质 |
|---|---|---|---|
| `normal` 普通 | 全部战斗魔法（单体/群攻/防御/支援按**策略组**分） | beam、meteor、petrification、enfeeble_field、conversion、fortification、desperation | 常规战斗法术，可装备进任意策略组；默认策略组 = `default_group` |
| `special` 特殊 | teleport(传送)、heal(治疗) | 系统固有自动触发 | 所有 NPC 天生固有；heal 可装备进装备槽并经 L1 决策施放，另有 L0 紧急奶 + 脱战自奶兜底；teleport 不进装备/决策表，仅导航回退/逃生传送 |
| `altar` 祭坛 | revive(复活) | 祭坛专属仪式 | `altar_only`，仅祭坛可施放，不进 NPC 自动决策 |

`target_mode` 决定"何时算有有效目标"，与 `category` 解耦：

| target_mode | 语义 |
|---|---|
| `hostile_nearest` | 半径内最近敌对（现有 beam 语义） |
| `hostile_lowest_hp` | 半径内血量最低敌对（集火） |
| `ally_lowest_hp` | 友方血量最低（治疗/增益） |
| `self` | 目标=自身（增益/护盾） |
| `none` | 无需目标（辅助/场地魔法） |
| `dead_ally` | 最近死亡留存记录（复活用，P4） |

### 5.2 `conditions` — 内置触发条件（✅ 已实现，数据驱动，非玩家脚本）

每个魔法可配自己的触发阈值，`CastBrain` 对照世界快照判断。**全部可选**，缺省 = 无条件。**只承载血线与效果不重放**；敌数门控已从按魔法配置中移除，改由 `CastBrain` 按**策略组**统一判定（见下方「敌数门控」）：

```jsonc
"conditions": {
  "self_hp_max": 0.6,                   // DEFENSE：自身血量 < 60% 才开盾
  "ally_hp_max": 0.7,                   // SUPPORT 治疗：友方最低血量 < 70% 才奶（heal 实际配 0.7）
  "no_effect": "minecraft:absorption"   // 自身无此状态才施放（防盾/buff 叠加）
}
```

**敌数门控（按策略组，非 per-spell / 非 category）——优先级降级，非硬性禁用**：**单体攻击组**的法术在敌数 ≤ 3 时正常优先，**群体攻击组**的法术在敌数 ≥ 3 时正常优先（阈值见 `WandscapeConstants.CAST_SINGLE_TARGET_MAX_ENEMIES` / `CAST_AOE_MIN_ENEMIES`）；防御/支援组不设敌数门槛。敌数与组不匹配的法术（敌数 &gt; 3 只剩单体组、敌数 &lt; 3 只剩群攻组）**不直接禁用，降级为最低优先级**——有其他匹配法术就选匹配的，仅当没有任何匹配法术可用时才施放它（避免「只剩单体攻击却一个也不放」的僵局）。组 = 玩家在策略页放置法术的桶（`EquippedMagicComponent`），**非法术自身 category**——把群攻法术（如 meteor）拖进单体组 → 敌数 1 也能对单体砸；留在群攻组 → 敌数 ≥ 3 才放。原各魔法 JSON 里的 `min_enemies` 已移除。

**防御 vs 治疗的血线竞争**靠阈值错开解决，不靠运行时互斥：护盾配 `self_hp_max: 0.6`（血量偏高时保命），治疗配 `ally_hp_max: 0.7`（血线偏低才奶）——两者天然不会同时抢。heal 的 `ally_hp_max` 只管 **队友**（快照 `allyLowestHpRatio` 排除施法者自己），**自己或治疗半径（6 格）内友方掉血走 L0 硬性覆盖**（见第四节）——L0 现也管濒死队友，L1 的 `ally_hp_max` 只是正常线，不依赖它保命。

### 5.3 `WorldSnapshot` — CastBrain 的输入（✅ 已实现，纯数据 record，可单测）

CastBrain 由 `select(known, castable, hasTarget)` 扩展为吃世界快照：

```java
record WorldSnapshot(
    int enemyCount,           // 目标周围敌人数（策略组敌数门控用：单体组 ≤ 3 / 群攻组 ≥ 3）
    float selfHpRatio,        // 自身血量比例 [0,1]（self_hp_max 用）
    float allyLowestHpRatio,  // 友方最低血量比例 [0,1]；无友方 = 1（ally_hp_max 用）
    Set<String> activeEffects // 自身已有状态 id（no_effect 用）
)
```

判定规则：HOSTILE 系需要 `enemyCount > 0`，ALLY 系需要 `allyLowestHpRatio < 1`，SELF/NONE 不需要目标。快照由调用方（守卫/自防御战斗循环）在每轮 ~10 tick 循环里构造。

### 5.4 `CastStrategy` — 施法策略（✅ 已实现，存于 NPC 数据）

```
预设: balanced / offensive / support / defensive   （custom 仅旧存档兼容）
priority: [magicId…]   // 显式优先级列表，配置后始终生效；空 = 全部停用
configured: bool       // 是否配置过；false（从未配置）→ 按预设推导默认列表
```

**预设 = 策略组级排序模板**（玩家可整体换预设，也可在某策略组内手动排序/启停——手动结果以显式 priority 为准，不被预设覆盖）：

| 预设 | 策略组顺序（高→低） |
|---|---|
| `offensive` 火力 | 单体攻击组 > 群体攻击组 > 防御组 > 支援组 |
| `balanced` 均衡 | 群体攻击组 > 单体攻击组 > 支援组 > 防御组 |
| `support` 支援 | 支援组（治疗优先）> 防御组 > 群体攻击组 > 单体攻击组 |
| `defensive` 防御 | 防御组 > 支援组 > 群体攻击组 > 单体攻击组 |

玩家不设任何东西 → 默认 `balanced` 的默认列表，零配置可用。

## 六、模块结构与关键类

```
magic/data/MagicDef.java            ✅ record 镜像 + fromJson（纯数据，仿 MagicCircleSpec；已实现）
magic/internal/SpellbookLoader.java ✅ dataconfig 注册 magic_spells 类目 + getSpec(id)/getAll()
magic/internal/CastBrain.java       ✅ 统一施法脑：L1 优先级扫描 + 策略优先级解析 + 快照目标规则 + conditions 门控
magic/data/WorldSnapshot.java       ✅ 决策输入快照（纯数据 record，已实现）
magic/data/SpellConditions.java     ✅ 魔法内置触发条件（conditions JSON 镜像 + matches，已实现）
shared/api/SpellcastingApi.java     ✅ 决策层对外接口（getKnownSpells/getStrategyPreset/getPriority/setStrategy，已实现）
core/component/EquippedMagicComponent.java   ✅ NPC 已装备魔法容器（4 分类桶 × 每桶 ≤3，桶内=类内优先级，默认 beam+heal，已实现）
core/component/CastStrategyComponent.java ✅ 策略预设 + 优先级列表（已实现）
magic/internal/MagicOp.java         （延后）魔法效果分发注册表——有第二个战斗魔法时再建
npc/data/DeathRecord.java           ✅ 死亡留存记录（纯数据 record + nearest 纯逻辑）
npc/internal/ColonyDeathRegistry.java ✅ 死亡记录 SavedData（平铺列表，永久留存，复活成功后删除）
npc/internal/NpcDeathHandler.java   ✅ LivingDeathEvent 钩子：死亡瞬间抓快照
npc/internal/ReviveHandler.java     ✅ 复活效果：spawnFromRecordAt（指定位置生成 + 恢复快照 + 虚弱复活）；入口已迁祭坛（P5），shift+右键移除
```

### 6.1 `MagicOp` — 效果分发（**未实现，延后**）

当前 `AtomicOp`（op/api/AtomicOp.java）是 sealed interface + `OpExecutorRegistry` 注册表：**纯数据请求 + 执行器按类型分发**。`MagicOp` 计划照此模式：

- sealed interface `MagicOp`，每个魔法一个 variant（`BeamOp`/`TeleportOp`/未来的 `AoEOp`/`BuffOp`…），携带该效果所需参数。
- 执行器按 magicId 注册：beam 效果走现有 `MagicCaster`/`MagicCastManager`/`MagicBeamEntity`；teleport 走 `engine/boundary/WandscapeRitualOps.self_teleport`（已有）；复活走 `ReviveExecutor` + 仪式。
- **效果实现仍留在各模块 internal**，`MagicOp` 只做分发——不违反"引擎是请求层、适配层是实现"。

> **为何延后**：当前只有一个战斗魔法（beam），守卫循环里按 magicId 一个 if 即可分发；
> 建单实现的 sealed 层级是死代码。等第二个战斗魔法（AOE/单攻变体）落地时再建。

### 6.2 `CastBrain` — 统一施法脑（✅ 已实现，含 P3 扩展）

- **纯决策函数**，不是新的任务系统：`select(known, castable, snapshot) → MagicDef?`，纯 Java 可单测。
- 调用方（守卫/自防御）在 ~10 tick 战斗循环里构造 `castable` 判定（互斥锁 + CD + 蓝）与 `WorldSnapshot`（敌数/自血/友方最低血/状态）喂入；拿到结果后按魔法 id 分发执行。
- **P3 扩展（已实现）**：快照驱动目标规则（HOSTILE 需敌数>0、ALLY 需有受伤友方、DEAD_ALLY 祭坛专属永不自选）+ conditions 门控（5.2/5.3）。
- **`resolvePriority(strategy, known)`**：未配置（`configured=false`）→ 按预设分类级排序（类内按装备槽位序，UTILITY 不进表）；已配置 → 用显式 magicId 列表（空 = 全部停用，不兜底）。CUSTOM 仅旧存档兼容。
- P3 起 `known` 来自 NPC 的 `EquippedMagicComponent`（已装备载荷）经玩家策略解析，替代硬编码。

## 七、决策流程（数据流）

```
触发源：守卫任务循环 / 自防御循环 / 导航回退
  → 构造 WorldSnapshot（敌数/自身血/友方最低血/状态）
  → known = 玩家策略（EquippedMagicComponent + CastStrategyComponent）经 CastBrain.resolvePriority 解析
  → CastBrain.select()
      ├─ L0 硬性覆盖：血量危机/LOS/互斥锁 → 硬性魔法 或 直接返回"不施放"
      ├─ L1 按 priority 列表从上往下扫：
      │     MagicState.canCast(magicId) && 蓝够 && 快照目标规则命中 && conditions 满足 → 选中
      └─ L2 兜底：普通攻击（GuardCombat.normalAttack，物理/5伤×SPELL_POWER×魔力强化/2s/不耗蓝）/走位/待命
  → 选出魔法 → 按 id 分发（当前仅 beam → MagicCaster/MagicCastManager/MagicBeamEntity；
      未来 TeleportOp→WandscapeRitualOps、ReviveOp→仪式系统）
  → 门控仍走 npc.tryCastSpell（MagicState 不变）
```

## 八、对接现有代码（迁移路径）

1. ✅ **beam 数据迁入 `magic_spells/beam.json`**：CD 400（施法锁结束后起算）/ 蓝 50 / 射程 32 / 法阵 arcane_hexagram / 颜色 #A8E0FF；`MagicCaster` 改读 MagicDef（缺失回退常量）。
2. ✅ **teleport 定义进 MagicDef**（CD 300 / 蓝 30，utility 类）；`NavigationSystem` 门控改读 teleport.json，锁时长保留 `WandscapeRitualOps` 引导对齐。
3. ✅ **`GuardCombat.engage` 改造**：不再直接 `MagicCaster.castNpcAt`，经 `CastBrain.select` 选魔法再按 id 分发；守卫/自防御共用此路径。
4. **`MagicCaster` 瘦身**（随 MagicOp 一起）：从"只会射光束"变成"`MagicOp` 分发器的 beam 实现"；玩家调试命令 `cast` / shift+右键 `castNpc` **已移除**（测试功能完成，2026-08）。
5. **手动施法**（shift+右键）**已移除**（测试功能完成，2026-08）。

> **CD 与锁的关系（2026-08 起）**：`MagicState` 的每魔法 CD 在施法互斥锁占用期间**冻结**，锁释放后才倒计时——CD 表示「施法结束后的恢复间隔」，施法时间（法阵/引导/光束全程）不计入。总间隔 = 锁时长 + CD。此前 CD 与锁同时从施法开始倒计时，光束锁（240 tick）盖过 CD（40 tick）导致连发无停顿；现改为光束 240 tick 结束后再停 400 tick。

## 九、玩家策略与条件（P3 已实现）

**动机**：CastBrain 原来只有 `hasTarget` 布尔，支撑不了防御/治疗/AOE 的条件决策；NPC 无"会哪些魔法"概念。P3 补齐条件决策（数据驱动）+ 玩家可控的策略层（只调一个"策略/优先级"维度，不写脚本）。

### 9.1 条件决策（已实现）

- **`SpellConditions`**（`magic/data/`）：`MagicDef.conditions` JSON → record（self_hp_max / ally_hp_max / no_effect），`matches(WorldSnapshot)` 纯逻辑判定，缺省 = 无条件。敌数门控（单体组 ≤3 / 群攻组 ≥3）由 `CastBrain` 按**策略组**判定，不进 per-spell 条件。
- **`WorldSnapshot`**（`magic/data/`）：决策输入快照（敌数 / 自身血比例 / 友方最低血比例 / 状态 id 集合），由守卫/自防御战斗循环每轮构造（`GuardCombat.buildSnapshot`：半径 16 内敌数、自身血比、半径内其他友方 NPC/村民最低血比、`unwrapKey` 取状态 id）。
- **`CastBrain.select(known, castable, snapshot)`**：目标规则改由快照驱动（HOSTILE 需敌数>0、ALLY 需有受伤友方、DEAD_ALLY 祭坛专属永不自选），并追加 conditions 门控。

### 9.2 玩家策略（已实现）

- **`EquippedMagicComponent`**（`core/component/`）：NPC 已装备魔法容器（B 阶段起替代 SpellbookComponent）——按 4 分类分桶、每桶 ≤3、桶内 = 类内优先级（槽位序），NBT 持久（`spellbookEquip`）。默认装备 **beam + heal**（新 NPC / 旧存档无字段时由 `onAddedToLevel` 数据驱动种入）。UTILITY 魔法不存此容器（teleport 导航回退 / revive 祭坛，系统固有）。服务端权威重算入口 `fromFlat(flat, categoryOf)`：未知/UTILITY 丢、每类 ≤3、去重。
- **`CastStrategyComponent`**（`core/component/`）：预设（balanced/offensive/support/defensive，custom 仅旧存档）+ 显式优先级列表（保留作覆盖）+ `configured` 标记。装备 UI 只写预设（未配置 → 按预设分类排序推导）；`setEquippedAndStrategy` 之外仍可经 API 设显式列表作精确覆盖。
- **`CastBrain.resolvePriority(strategy, known)`**：未配置 → 按预设分类级排序（类内按装备槽位序，UTILITY 不进表）；已配置 → 显式 magicId 列表（空 = 全部停用）。CUSTOM 仅旧存档兼容。
- **`SpellcastingApi`**（`shared/api/`，实现 `magic/internal/SpellcastingApiImpl`）：查/改装备载荷与策略；`setEquippedAndStrategy(uuid, preset, equipped)` 服务端按真实分类装桶校验。`NpcDataPacket` 携带策略数据 + 战斗魔法目录（`magicCatalog`，id→分类）；`NpcStrategyPacket`（client→server，携带装备扁平态 + 本次消耗的卷轴槽）改完回发刷新。

### 9.3 策略 UI（已实现，B 阶段装备制）

- NpcScreen 加「策略」按钮 → **NpcStrategyScreen**（装备制）：顶部 4 总体策略按钮（均衡/火力/支援/防御，管跨类施法先后）+ 中部 **4 分类 × 3 槽位**面板（槽位序 = 类内优先级，点已占槽卸载，悬停提示）+ 右侧**玩家背包卷轴源列表**（点卷轴装备到该魔法所属分类首空槽；本地预校验已装/满，服务端权威复核 ≤3/去重/UTILITY 排除并消耗卷轴）。任意改动客户端重排完整扁平装备态（分类固定序 × 槽位序）连同 `consumeSlot` 发 `NpcStrategyPacket`。lang 键 `gui.wandscape.strategy.*` + `magic.wandscape.*`。

## 十、复活与死亡留存（✅ 已实现）

**动机**：守卫 NPC 战死不可逆，殖民地战力永久损失。复活 = UTILITY 魔法（`magic_spells/revive.json`）+ 死亡留存数据，**第一版不走仪式系统**（shift+右键玩家指挥式，独立引导调度）。

### 10.1 死亡留存（已实现）

- **触发**：`NpcDeathHandler`（LivingDeathEvent 钩子，Wandscape 构造器注册），在实体清理前抓快照。
- **数据 `DeathRecord`**（纯 record）：npcId、名字、维度、死亡坐标、死亡时间、所属殖民地、外观（皮肤变体/帽子颜色）、hasDefaultWand、7 属性快照、背包快照（ECS Inventory 的 ResourceStack 列表）。
- **存储 `ColonyDeathRegistry`**：SavedData（`wandscape_npc_deaths`），每世界一份平铺列表。
- **清理**：仅复活成功后删除；不做时间过期清理（永久留存）。
- **第一版无墓碑方块**：施法时死亡点生成法阵（复用 `MagicCircleCastPacket` 链路），玩家看到法阵即知位置。

### 10.2 复活魔法（已实现）

- **MagicDef**：`id=revive`、`category=utility`、`target_mode=dead_ally`、蓝 80 / CD 600 / 射程 32、法阵 `revive_ritual`（绿色复活阵，半径 13 / 600 tick，spec 见 `architecture/magic/example-specs/revive_ritual.json`）。
- **触发（P4 原入口，已迁移）**：原为 shift+右键 NPC → `MagicInteractHandler` → `ReviveHandler.castRevive`（施法者周围射程内最近的 `DeathRecord`，门控走 `npc.tryCastSpell`）；**P5 起复活唯一入口为祭坛**（见第十一章），shift+右键施放已移除。
- **引导**：时长 = 复活法阵 spec 时长（法阵完整展开后完成，缺失回退 100 tick）；期间 NPC 面向死亡点、举杖（`startManualCast`）。
- **完成（P5 后）**：`AltarCastExecutor` 引导到期调用 `ReviveHandler.spawnFromRecordAt(level, rec, 祭坛中心最上方)` 生成新 `WandscapeNpc`，恢复名字/外观/属性上限/默认法杖/背包（ECS 重 seed + ColonyMember 修正），删除死亡记录，PORTAL 爆点。
- **无死亡记录前置校验（按殖民地）**：`AltarCastHandler.onCastRequest` 点选 revive 时若该殖民地无死亡记录（`latestInColony(colonyId) == null`）直接提示、不发布任务；`AltarCastExecutor` 幂等复核兜底——发布后记录被同殖民地其他祭坛复活消耗时跳过施法（不扣蓝、不放法阵）；`fireRevive` 同样按祭坛所属殖民地取记录（`getBuilding(altarId).getColonyId()`），不跨殖民地捞人。
- **虚弱复活**：复活后 **1 血 0 蓝**（`setHealth(1)` + `setMana(0)` + `markManaSeeded` 阻止首 tick 满蓝种子），靠脱战回血（interval 回 1 HP）与魔力回复（每 10t 回 1% 上限）缓慢恢复——复活有代价。
- **失败兜底**：生成位置无地可放等失败 → 记录保留，玩家可重试。
- **保卫殖民地复活（2026-08-26）**：法师战死时若距**本殖民地**任一建筑 AABB（3D 距离）≤ `Config.REVIVE_NEAR_BUILDING_RANGE`(20) 格，**立即**在市政厅门口自动复活（复用全灭保底的 `resolveTownHallDoorOrAnchor` + `spawnFromRecordAt` 虚弱复活），无需祭坛仪式——守卫殖民地战死不强制走祭坛，判定只认本殖民地建筑。
- **与施法决策的关系**：复活不进 NPC 自动战斗决策表（L1）——玩家指挥式，避免 NPC 战斗中弃敌救人。
- **遗留**：装备只恢复 default wand（当前装备系统仅 WAND 槽）；墓碑方块视觉留作后续增强。
- **P5 迁移（已完成）**：复活入口已改为**祭坛**（见下章），shift+右键施放已移除。

## 十一、祭坛施法（P5 已实现）

**动机**：复活等重大魔法不应随手 shift+右键施放。祭坛作为殖民地的"神圣设施"，集中管理重大魔法：玩家在祭坛左键选中魔法、点右下角 Submit 发布任务 → NPC 走到祭坛旁施法。

### 11.1 需求（用户定，全部落地）

1. **新建筑类别 altar（祭坛）**：与其他建筑一样放置/维护（`buildings/altar1.json`）。
2. **施法入口**：玩家 V 面板右键祭坛 → AltarScreen → **左键选中**魔法（如复活，仅高亮不发任务）→ 点右下角 **Submit**（`gui.wandscape.common.submit`）发布任务 → NPC 执行。
3. **NPC 执行**：NPC 走到祭坛旁边，与其他建筑一样以任务方式施法；**魔法阵/特效在祭坛中心释放**。
4. **魔力来源**：**扣接取祭坛施法任务的 NPC 的蓝**；`SchedulerSystem` 分派时要求其当前魔力 ≥ 该魔法蓝耗（不足则任务挂起等回蓝）。
5. **祭坛 CD/时长**：每个魔法有祭坛侧冷却与引导时长（`altar_cooldown`/`altar_duration`），**按祭坛（building UUID）独立**存放（`AltarCastState` SavedData），**不同祭坛之间不共享**。
6. **altarOnly 约束**：祭坛中的魔法（`altar_only: true`，当前仅 revive）**不能被 NPC 直接施放**——`CastBrain` 自动施法跳过、shift+右键复活移除。
7. **复活目标**：**最近死去的 NPC**（`ColonyDeathRegistry.latest()`，按 deathTime 最新，不限位置）。
8. **复活点**：祭坛包围盒中心最上方方块顶端。

### 11.2 结构（已落地）

```
building/executor/AltarCastExecutor   OpExecutor<AltarCastOp>：幂等复核（祭坛 CD + 魔力 + 锁）→ tryAltarCast 扣蓝占锁 → 中心起法阵 → 引导 → 到期 fireEffect（revive）+ 起祭坛 CD
building/internal/AltarCastState      SavedData（wandscape_altar_casts）：按祭坛 UUID 独立存每魔法剩余 CD，每 tick 推进
building/internal/AltarCastHandler    玩家点选校验 + 锁定校验（任务池 hasActiveTask，发布即锁）+ 经 PlayerManualSource 发任务 + tick 降 CD + centerTop 助手
building/client/AltarScreen           MedievalScreen MINIMAL + ScrollableList：列出 altarOnly 魔法（名称/蓝耗/CD/时长/冷却·锁定状态）；左键选中 + 右下角 Submit 提交
building/network/                     AltarOpenPacket（server→client，含 locked）+ AltarCastRequestPacket（client→server）
task/                                 AltarCastOp（AtomicOp permit）+ DSL "altar_cast" 步骤 + blueprint magic:altar_cast
magic/                                MagicDef 加 altar_only/altar_cooldown/altar_duration；CastBrain.select 跳过 altarOnly
npc/                                  DeathRecord.latest + ColonyDeathRegistry.latest；ReviveHandler.spawnFromRecordAt（生成点来源改为祭坛中心最上方）
```

### 11.3 依赖与复用

- `BuildingApi.getBuildingBounds(UUID)` 取祭坛包围盒 → 中心 + 最高面生成点（`AltarCastHandler.centerTop`）。
- 建筑交互先例：`BuildingInteractHandler` 的 altar 分支；GUI 先例 `MedievalScreen MINIMAL` + `ScrollableList`。
- 施法/法阵/复活复用：`MagicCircleCastPacket`、`npc.tryAltarCast`（扣蓝+占锁、**不设 NPC 每魔法 CD**）、`ReviveHandler.spawnFromRecordAt`。
- 任务分派：`PlayerManualSource.publish`（蓝图 `magic:altar_cast`，priority = `QUEUE_RITUAL_ALTAR` = 10）。
- 调度器魔力门槛：`GlobalTask.taskParams["mana_cost"]` + `EntityOps.getCurrentMana(ecsId)`（core 边界，`SchedulerSystem` 纯 Java 不碰 MC 类）。

### 11.4 边界

- 祭坛可施魔法列表 = 数据驱动（`MagicDef.altar_only` 过滤），当前仅 revive。
- 跨殖民地限制：第一版不做；祭坛任务带 `colony_id`，调度器只分给该殖民地 NPC。
- 祭坛施法进任务系统（NPC 移动/交互是任务），不进 CastBrain 自动决策表；`CastBrain.select` 再加 altarOnly 跳过作防御性保证。
- 引导时长与法阵视觉对齐：`altar_duration` 设为该魔法 circle spec 的 `durationTicks`（revive → revive_ritual = 600）。
- **发布即锁定，施放结束才起 CD**：玩家提交后该祭坛+魔法被任务池锁定（`GlobalTaskPool.hasActiveTask("magic:altar_cast", {altar, magic_id})`，覆盖已发布未施放 + 施放中），任务完成（施放结束）即解锁；祭坛 CD 在 `fireEffect` 起算，接续锁定窗口无缝隙——防止玩家在 NPC 接取前反复点击刷多次施法。客户端以 `AltarSpellInfo.locked` + 本地 submitted 集显示「施法中/已安排」并禁用 Submit。
- **全灭保底自动复活**：当殖民地所有 NPC 阵亡（活着的 NPC 为 0 且死亡表存在记录）时，系统自动触发全灭保底（`ReviveHandler.checkAndAutoReviveColony`），在市政厅（`town_hall`）门口播放闪耀绿色复活魔法视觉并自动复活一名离世成员，避免全员阵亡后无法师施发复活的瘫痪死锁局面。
- **保卫殖民地复活（单法师自动召回）**：与全灭保底同链路但**单法师死亡即触发**——法师死亡时距本殖民地最近建筑（AABB 3D 距离）≤ `revive.nearBuildingRange`(20) 则立即在市政厅门口复活（虚弱复活），无需祭坛仪式。

## 十二、分阶段实施

| 阶段 | 内容 | 状态 |
|---|---|---|
| **P1 数据与分发** | `MagicDef` + `magic_spells` JSON（beam/teleport）+ `SpellbookLoader`（dataconfig）；CD/蓝/射程/视觉改数据驱动 | ✅ 已实现（行为不变，配 6 个解析单测） |
| **P2 决策集中** | `CastBrain`（L1 优先级扫描）；GuardCombat/SelfDefense 经 CastBrain 选魔法再分发 | ✅ 已实现（配 6 个单测） |
| **P3 玩家策略 + 条件** | 分类定案（5 类，已落地）；`SpellConditions` + `WorldSnapshot` + CastBrain 扩展（快照目标规则 + conditions 门控 + resolvePriority）；`SpellbookComponent` + `CastStrategyComponent`（含 defensive 预设）+ `SpellcastingApi` + `NpcScreen` 策略 UI | ✅ 已实现（配单测：WorldSnapshot/SpellConditions/SpellbookComponent/CastStrategyComponent/CastBrain 共 46 个） |
| **P4 死亡留存 + 复活** | `DeathRecord` + `ColonyDeathRegistry`（SavedData）+ `NpcDeathHandler` 钩子；`revive` MagicDef + `dead_ally` 目标 + shift+右键施放 + 引导到期生成 NPC 恢复数据（虚弱复活：1 血 0 蓝） | ✅ 已实现（配 8 个单测） |
| **P5 祭坛施法** | altar 建筑类别 + AltarScreen + 祭坛施法任务（NPC 走到祭坛旁，法阵在祭坛中心）；复活改祭坛唯一入口（目标 = 最近死去不限位置，复活点 = 祭坛中心最上方）；扣接取任务 NPC 的蓝（调度器魔力门槛）、每祭坛每魔法 CD 独立（AltarCastState）、altarOnly 魔法禁 NPC 直接施放 | ✅ 已实现（配单测：MagicState.tryAltarCast/DeathRecord.latest/MagicDef altar 字段/CastBrain altarOnly 跳过） |

P1/P2 玩家无感知（内部重构），P3 起见 UI。每个阶段完成即按 CLAUDE.md 提交规则 commit。

## 十三、依赖与边界

- `MagicDef` 是纯数据 record → 放 `magic/data/`（仿 `MagicCircleSpec` 同在 magic/data）；`CastStrategy`（P3）与 `DeathRecord`（P4）是纯数据 → `core/` 或 `npc/data/`。
- `EquippedMagicComponent` / `CastStrategyComponent` → `core/component/`（纯 Java 零 MC，仿 `MagicState`）。
- 效果实现 → `magic/internal/`（beam）、`engine/boundary/`（teleport）、`npc/internal/`（复活），`MagicOp` 只做分发。
- 模块间通过 `SpellcastingApi` + 事件，不跨包 new 类（铁律 1）。
- **不做全条件脚本**（FFXII 式）：触发器是 `target_mode` + 内置 `conditions` 阈值，不是玩家可写的判定——避免翻译与理解负担。

## 十四、默认法术数值平衡调整（2026-08-11）

对默认法术书 `[beam, heal, meteor, petrification]` 的四类魔法做一轮实测后调整：

| 魔法 | 调整 | 理由 |
|---|---|---|
| meteor | 蓝 70→**40**、CD 500→**300**、单颗伤害 10→**12**（`effect.damage` 数据驱动）、`min_enemies` 1→**3**（**08-12 反转**：见第十五节） | 原 70 蓝/500CD 换单目标 10 伤，是四魔法里最弱、最贵、却排 balanced 第一优先级的浪费发；降本增效 + 只在聚团（敌数≥3）时砸，避免单体遭遇战先甩陨石 |
| heal | **以施法者自身为圆心**（原以战斗目标怪物为圆心，常奶错位置白扣蓝）、`ally_hp_max` 0.9→**0.7** | 目标错位 bug：`ally_lowest_hp` 只判"附近有受伤友方"，效果却落在怪物脚下；圆心改自身后同时服务 L0 紧急奶。阈值回调避免高频过奶 |
| L0 硬性覆盖 | **新增** `GuardCombat.l0EmergencyHeal`：自身**或治疗半径内友方**血比 < **0.5**（2026-08-12 从 0.35 上调，更早开奶保命）且会 heal → 无视玩家策略/conditions 强制施奶 | 落实第四节 L0 设计：落单/受伤法师不再死于攻击循环，玩家把治疗调低也饿不死保命；范围内队友濒死时提前开奶，避免来不及施法 |
| `MagicDef` | 新增 `effectDamage` 字段（`effect.damage` 可选，负值归 null） | 效果伤害随 mana/CD 一起数据驱动，未来伤害类魔法免改代码 |

> 平衡基准：maxMana 100、回蓝 1 点/10t（0.5s）。beam 单目标约 60 伤/32s 总间隔、meteor 总量恒 6×7.5 伤、6s 法阵持续时长内逐颗发射（按 1/6 持续时长 1 颗，每颗发射时动态重瞄当时最近目标，见第十八节）——单体 beam 依旧最凶，meteor 定位为"廉价清杂"，敌少时也能当单体爆发用。

**施法锁减半（同日）**：所有战斗魔法（beam + heal/meteor/petrification/enfeeble_field/fortification/conversion/desperation）的施法互斥锁时长**减半**（`MagicCaster` 光束 = `(前摇+法阵+收尾)/2`，`MagicSpellExecutors` 其余 = `法阵时长/2`）；施法效果时长（治疗光环/增益/光束伤害）**不变**。锁本只用于防施法重叠，不必覆盖整个法阵/光束动画——锁太长会让守卫长时间站桩、且连危机自奶都放不出，实测极易被打死。减半后：光束 12s→6s、conversion 10s→5s、enfeeble 7s→3.5s、heal/meteor/fortification 6s→3s、petrification 5s→2.5s、desperation 0.75s→0.35s。

## 十五、meteor 保底集中砸（2026-08-12）

反转第十四节「只在聚团（敌数≥3）时砸」的设定：**陨石总量恒为 3 颗**，按目标数分配，使单颗伤害调整也不会在敌少时把总伤害打没。

- `meteor.json` `min_enemies` 3→**1**：敌数 ≥ 1 即可施放（快照仍按 NPC 16 格内 `Enemy` 计数）。
- **分配规则**（按距施法者近→远）：1 敌 → 该敌独占 3 颗；2 敌 → 最近 2 颗 + 次近 1 颗；≥3 敌 → 最近 3 个各 1 颗。实现 `MagicSpellExecutors.distributeMeteors`（纯函数，可单测）+ `spawnMeteorsAt`（同目标多颗水平小偏移散开，仍在 4 格溅射半径内）。
- **落地伤害**：`MagicEventHandler.tickMeteors` 每次命中前重置 `target.invulnerableTime = 0`——同目标 10 tick 内落多颗陨石只有第一颗结算，不重置则保底 = 12 伤而非 36 伤（与 `GuardCombat.normalAttack` 同款做法）。
- 玩家命令 `/wandscape magic cast meteor`（`castForPlayer`）同步用同一分配；0 敌人时保留视线前方 6 格落 1 颗的调试兜底。

## 十六、非战斗自奶（2026-08-12）

战斗内 L0 紧急奶只在血比 < 0.5 时强制施奶，L1 治疗只认友方最低血（快照排除自己）——战斗打完 NPC 停在 50%~满血之间时**永远不会自奶补满**，只靠慢速脱战回血。补上非战斗自奶：

- **触发**：NPC 不在战斗（无 `self_defense` 包 / `guard:` 战斗任务）且血量未满、会治疗、CD/蓝/互斥锁都通过 → 对自己施放 heal 补满到 100%。
- **实现**：`WandscapeNpc.tickIdleSelfHeal()` 每 tick 尝试，实际频率受 heal 魔法 CD（300t）限制；战斗判定 = ECS 当前包为 `self_defense` 或全局任务蓝图 `guard:` 前缀（战斗必然以任务形式占用队列，空闲/建造/采集即非战斗）。
- **不打断任务**：建造/采集等非战斗任务照常进行，治疗以自身为圆心（光环跟随施法者），与任务互不干扰。
- **敌对测试法师（EvilMage）除外**：其施法由自身 goal 驱动，不继承自奶，避免给测试敌人加 buff。

## 十七、治疗吃法术强度加成（2026-08-12）

治疗光环的每脉冲治疗量从固定 4 点改为 **基础量 × 施法者 SPELL_POWER**（`MagicSpellExecutors.HEAL_BASE_AMOUNT = 4`，默认 SPELL_POWER=1 → 仍 4 点，强法师奶更多），与伤害加成同源——`castHeal` 施放时取 `npc.getEffectiveAttribute(AttributeType.SPELL_POWER)` 乘入 `HealAura`（L0 紧急奶 / 非战斗自奶 / 常规治疗共用此路径，一并生效）。玩家命令 `castForPlayer` 无 SPELL_POWER，保持基础量 4。

## 十八、meteor 连落 6 颗、发射时动态重瞄（2026-08-19）

原「3 颗同时落下」视觉上像一次性爆发、对群性弱；改为法阵持续时长（120t）内**按 1/6 持续时长逐颗发射 6 颗陨石**（间隔 20t，末颗在法阵消失前落地），单颗伤害**减半**（`effect.damage` 15→**7.5**，数据驱动；代码缺省 `METEOR_DEFAULT_DAMAGE` 10→5），**总伤害不变**（3×15=45 → 6×7.5=45）。

- **发射时动态重瞄**：不再预分配落点——每颗陨石在**自己发射那一刻**以施法者当前位置（已移除则用施法时位置）为基准，扫描 16 格内最近存活敌对生物并砸向它**当时的位置**（`MagicSpellExecutors.fireMeteorAtNearestEnemy`）。敌人移动/被清后自动换最近目标，集火最近敌人的同时对群更跟手（旧 `distributeMeteors` 保底分配已删除）。
- **调度**：`MagicSpellExecutors` 按 `meteorIntervalTicks(durationTicks)`（= durationTicks/6 = 20t）间隔登记 6 个延迟发射（`MagicEventHandler.PENDING_METEORS`），到期各触发一次重瞄发射；落地结算仍走 `tickMeteors`（同目标叠伤重置无敌帧逻辑不变）。
- **不变项**：溅射半径 4、发射时扫描半径 16、头顶 14 格坠落高度、`min_enemies` 3（第十五节「3→1」记载已被后续反转，以 meteor.json 为准）、mana_cost 40、CD 300 均不动。
- 玩家命令 `/wandscape magic cast meteor`（`castForPlayer`）同步连落（以施法瞬间玩家位置为扫描基准）；0 敌人时保留视线前方 6 格落 1 颗的调试兜底。

## 十九、魔法平衡调整：蓝耗 / 背水一战 / 魅惑（2026-08-19）

- **enfeeble_field / fortification 蓝耗 65→40**（`mana_cost` 数据驱动）：两个中等消耗魔法降价，施放机会更多。
- **desperation 力量削弱并加上限**：力量等级从 A²/48 → **min(10, ⌊A²/100⌋)**（护甲 <10 无奖励，最高力量 X）；背水一战反转护甲加**下限 −16**（护甲 ≥32 反噬封顶，不再无限加深）。
- **conversion 改为群体魅惑**：不再单目标——施法瞬间**魅惑最近的 3 个敌对生物**（16 格内按距施法者近→远，不中途追加），使它们倒戈攻击附近敌人（`tickConversions` 每 0.5s 重定向不变）；**受伤立即解除魅惑**（`onLivingDamage` 见伤害即移除 CONVERSION 与跟踪表）。法阵改在施法者脚下（跟随 NPC），不再落在目标脚下。

## 二十、NPC 施法清单扩展与策略分类（2026-08-19）

- **默认法术书扩展为 8 个**：`SpellbookComponent.DEFAULT_SPELLS` 从 `[beam, heal, meteor, petrification]` 增补 **conversion / desperation / fortification / enfeeble_field**。新 NPC 开局全都会；已存档 NPC 加入世界时自动补齐（`WandscapeNpc.onAddedToLevel` 遍历 DEFAULT_SPELLS 补缺失）。法术书内顺序决定**同分类内优先级**。
  > **B 阶段（2026-08）已废弃此设计**：`SpellbookComponent` 移除，改 `EquippedMagicComponent`（每类 ≤3 装备容器，默认仅 beam+heal）；"开局全会 8 个"不再是默认——见 `docs/plan-magic-items.md`。
- **策略分类调整**（`category` 数据驱动，只决定策略预设排序与 UI 分组，不改触发逻辑 target_mode/conditions）：
  - **desperation → `single_target`**（背水一战视为单体输出模式：战斗中优先于群攻施放，90s CD + `no_effect` 门控防刷）。
  - **conversion → `defense`**（群体魅惑归防御桶：倒戈控场当保命用）。
  - **enfeeble_field → `aoe`**（群体虚弱归群攻桶，与 meteor 同桶）。
  - **fortification → `support`**（增益桶，与 heal 同桶，SUPPORT 预设优先）。

## 二十一、魔力强化：独立魔法输出乘区（2026-08-20）

**背景**：赐福/背水此前给 vanilla 力量（`DAMAGE_BOOST`），但 NPC 伤害全部走 `hurt()` 自定义伤害类型、只读 SPELL_POWER，力量对纯法师完全无效（L2 普攻兜底也是自定义 `melee` 类型）。

**新增效果 `magic_enhance`（魔力强化）**：纯标记 MobEffect（`WandscapeEffects.MAGIC_ENHANCE`，靛蓝），倍率 = `1 + 0.2 × 等级`（I 级 +20%，每级 +20%，独立乘区，与 SPELL_POWER 各自乘算）。SPELL_POWER 是 ECS 自定义属性（非 vanilla Attribute），挂不了 attribute modifier，故在核算入口手动乘（`MagicSpellExecutors.magicEnhanceMultiplier`，纯函数可单测）。

**应用范围**（所有乘 SPELL_POWER 处都额外乘魔力强化，治疗也吃）：
- 伤害：`NpcSpellPowerHandler`（`LivingIncomingDamageEvent`）先按友伤边界取消友军伤害（非 `Enemy` 且非 `canBeamHurt` 的目标、和平模式——铁魔法/召唤物也走此入口，见 `core/types/FriendlyForce`），再在 SPELL_POWER 倍率后乘魔力强化——光束/陨石/未来魔法自动生效；**L2 物理普攻兜底也走此钩子，一并被放大**（既定行为）。
- 治疗：`castHeal` 每脉冲治疗量 = `HEAL_BASE × SPELL_POWER × 魔力强化`。
- **玩家暂不生效**：玩家施放入口已移除，`castForPlayer` 会给玩家上魔力强化 buff（buff 栏可见）但无实际效果，等玩家施法入口回归后自动生效。

**赐福/背水改用魔力强化**：NPC 与玩家路径一致——vanilla 力量（`DAMAGE_BOOST`）从两魔法中移除，替换为 `MAGIC_ENHANCE`。赐福 = 魔力强化 I；背水 = `min(10, ⌊护甲²/100⌋)` 等级（沿用原力量公式，护甲越高加成越多，10 级 = +200% 魔法伤害）。

## 二十二、特殊魔法与初始装备（2026-08-26）

**动机**：heal 与 teleport 是系统固有的保命/导航魔法。heal 额外可装备进装备槽并进 L1 决策（仍有 L0 紧急奶 / 脱战自奶兜底）；teleport 保持系统固有、不占装备槽、不进 L1 战斗决策——只在导航回退 / 逃生传送由系统触发。同时殖民地初始法师（3 名）应开局就带 beam+meteor（受训法师），酒馆招募的法师是普通人，不该带起始战斗魔法。

- **SPECIAL 分类**（`MagicDef.Category.SPECIAL`）：heal/teleport 迁入，`MagicDef.SPECIAL_SPELLS = [teleport, heal]`。所有 NPC 天生固有（`WandscapeNpc.knowsSpecialSpell`）；heal 可装备进装备槽并经 `CastBrain` 选中施放，teleport 不进装备、不进 L1。
- **ALTAR 分类**（原 UTILITY 更名）：revive 独占，`altar_only` 祭坛专属，不进装备/决策表。
- **默认装备**：`EquippedMagicComponent.DEFAULT_EQUIP = [beam, meteor]`（殖民地初始 3 名法师）；酒馆招募法师在 `TavernRecruitPacket` 生成后清空装备槽（无起始战斗魔法，仅特殊区 heal/teleport 系统固有）。
- **装备排除统一**：`SpellbookLoader.equippableCategoryOf` 对 ALTAR(revive) 与 teleport 返回 null（不可装备）；heal 及 normal 法术可装备——装桶取 `MagicDef.default_group`（缺省兜底 support），heal 无 default_group → support 桶（2026-08-26 起桶即策略组，敌数门控与预设排序按桶判，`MagicDef.category()` 只表性质）。卷轴槽、魔法目录同样排除 ALTAR 与 teleport；创造栏含 heal/teleport 卷轴（仅排除 ALTAR revive）；卷轴创造施法排除 ALTAR 与 teleport（heal 可施放）。
- **heal/teleport 卷轴保留**：`scroll_heal.json` + `scroll_teleport.json`（均 min_colony_level 1，魔法工坊合成、创造栏可获得）；teleport 卷轴创造模式不可施放（导航回退魔法无原地施法语义）。
- **策略 UI「特殊」面板**：`NpcStrategyScreen` 右侧新增只读「特殊」面板，列出 teleport/heal 并注明「默认使用，不可更换」。

## 二十三、魔法分类收敛 + 敌数门控跟随策略组（2026-08-26）

**动机**：a455928b 让策略槽位放置不再校验分类匹配（`mayPlace 去分类匹配`）——玩家可把任意法术放进任意策略组；但 `CastBrain.enemyCountGate` 仍按每个法术**自己的 `MagicDef.category()`** 判敌数门槛，门控与实际所在的策略组脱节。例：meteor（曾 category=aoe）即使被拖进「单体攻击组」，敌数 1 时仍被 aoe 的 ≥3 挡掉——"NPC 对单个目标放不出陨石"的根因。

- **`MagicDef.Category` 收敛为 3 类**：`NORMAL`（原 single_target/aoe/defense/support 全部并入）/ `SPECIAL`（teleport/heal）/ `ALTAR`（revive）。`category` 只表性质，不再决定敌数门控与预设排序。
- **策略组 = `EquippedMagicComponent` 4 桶**（single_target/aoe/defense/support，各 ≤3 槽），玩家自由放置。
- **敌数门控跟随策略组**（`CastBrain.enemyCountGate(SpellRef, snapshot)`）：单体攻击组 ≤ 3、群体攻击组 ≥ 3、防御/支援组无门槛。法术以新 `SpellRef(MagicDef, group)` 携带所在组——`CastBrain.knownSpells` 从桶循环带回组，`select` 门控与 `resolvePriority` 预设排序都按组判。**2026-08-27 起门控不匹配降级为最低优先级而非硬禁用**（见 5.2）。
- **`default_group` 字段**（可选）：normal 法术的默认策略组（beam→single_target、meteor→aoe、petrification→defense、enfeeble_field→aoe、conversion→defense、fortification→support、desperation→single_target），供默认装备种子与 `equippableCategoryOf` 兜底装桶；缺省 → support。
- **玩法**：陨石对单体放行 = 把它拖进**单体攻击组**（按组 ≤3）；留在群体攻击组则敌数 ≥3 才砸。预设（火力/均衡/支援/防御）排序也按策略组。
- **铁魔法合成**：`IronSpellsHelper.getSyntheticDef` 合成 def 的 category 恒 NORMAL，targetMode/conditions 按组名字符串 switch；组由 SpellRef 携带。

