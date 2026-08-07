# NPC 施法决策层 — 设计文档

> **状态（2026-08-07）**：**P1 数据与分发、P2 决策集中、P3 玩家策略 + 条件、P4 死亡留存 + 复活、P5 祭坛施法均已实现**（见文末实施表）。
> 分类已定案为 5 类（SINGLE_TARGET / AOE / DEFENSE / SUPPORT / UTILITY）。
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
  ├─ 自身血量 < 阈值           → 治疗魔法 / 脱离战场（无视玩家策略）
  ├─ LOS 被方块挡住           → 停止施法、转寻路（现有 GuardCombat 逻辑）
  ├─ 施法互斥锁被占用          → 不打断当前施法，等下一轮
  └─ 导航失败需传送           → 走 utility 传送（导航回退，属硬性路径）

L1 玩家策略层（可配置）
  ├─ 每个 NPC 一个策略预设（均衡/火力/支援/防御/自定义）
  └─ 策略内一条可编辑的【施法优先级列表】
      每 tick 从上往下扫：第一个"CD 已过 + 蓝够 + 有有效目标 + conditions 满足"的魔法即施放

L2 兜底层
  └─ 列表全不可施 → 基础攻击 / 走位 / 待命（现有行为保持）
```

关键点：**L0 必须永远先于 L1**（保命逻辑不能被玩家把治疗调成最低优先级而饿死）；L1 玩家改的是**分类或魔法间的排序**，不是触发条件本身。

## 五、数据结构（JSON 数据驱动）

### 5.1 `MagicDef` — 魔法定义（仿 `MagicCircleSpec`：record + fromJson + dataconfig）

```jsonc
{
  "id": "beam",                 // 魔法 id（tryCastSpell 的 magicId key）
  "category": "single_target",  // 分类：single_target / aoe / defense / support / utility
  "mana_cost": 50,
  "base_cooldown": 40,          // tick；按 SPELL_SPEED 缩短（沿用 MagicState 现有逻辑）
  "range": 32,
  "target_mode": "hostile_nearest",
  "altar_only": true,            // 仅祭坛可施放；NPC 直接施法（CastBrain）永不选中
  "altar_cooldown": 600,         // 该魔法在祭坛侧的冷却（tick），按祭坛独立存放
  "altar_duration": 160,         // 祭坛引导/施法时长（tick），应与其法阵 spec 的 durationTicks 对齐
  "conditions": { ... },        // 可选：内置触发条件（见 5.2），缺省 = 无条件
  "effect": {                   // 每魔法专属参数，由对应效果执行器消费
    "circle_id": "arcane_hexagram",
    "color": "0xFFA8E0FF"
  }
}
```

**分类定案（5 类）**——`category` 决定策略预设的默认排序与 UI 分组，**不承载触发逻辑**：

| 分类 | 覆盖 | 典型魔法 | 决策要点 |
|---|---|---|---|
| `single_target` 单体攻击 | beam、火球、闪电 | 单体高伤/持续伤 | 无条件，有敌即可 |
| `aoe` 群体攻击 | 爆炸、冰爆、火焰风暴 | 范围伤害 | **敌数 ≥ N 才施放**（防浪费蓝），否则回落单体 |
| `defense` 防御 | 护盾、减伤、结界、嘲讽 | 保命/免伤 | 自身血量阈值 + **无同类状态**（防叠加）；与治疗的血线竞争靠预设排序+阈值错开 |
| `support` 支援 | 治疗 + 增益（力量/急速） | 回血/预铺 buff | 治疗响应式（谁血低奶谁）、增益预判式（开战前上），靠 target_mode 区分 |
| `utility` 杂项 | 传送、复活、召唤、天气 | 非战斗 | L0 硬性路径 / 玩家命令 |

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

每个魔法可配自己的触发阈值，`CastBrain` 对照世界快照判断。**全部可选**，缺省 = 无条件：

```jsonc
"conditions": {
  "min_enemies": 3,                     // AOE：范围敌人数 ≥ 3 才施放
  "self_hp_max": 0.6,                   // DEFENSE：自身血量 < 60% 才开盾
  "ally_hp_max": 0.5,                   // SUPPORT 治疗：友方最低血量 < 50% 才奶
  "no_effect": "minecraft:absorption"   // 自身无此状态才施放（防盾/buff 叠加）
}
```

**防御 vs 治疗的血线竞争**靠阈值错开解决，不靠运行时互斥：护盾配 `self_hp_max: 0.6`（血量偏高时保命），治疗配 `ally_hp_max: 0.5`（低血线才奶）——两者天然不会同时抢。

### 5.3 `WorldSnapshot` — CastBrain 的输入（✅ 已实现，纯数据 record，可单测）

CastBrain 由 `select(known, castable, hasTarget)` 扩展为吃世界快照：

```java
record WorldSnapshot(
    int enemyCount,           // 目标周围敌人数量（min_enemies 用）
    float selfHpRatio,        // 自身血量比例 [0,1]（self_hp_max 用）
    float allyLowestHpRatio,  // 友方最低血量比例 [0,1]；无友方 = 1（ally_hp_max 用）
    Set<String> activeEffects // 自身已有状态 id（no_effect 用）
)
```

判定规则：HOSTILE 系需要 `enemyCount > 0`，ALLY 系需要 `allyLowestHpRatio < 1`，SELF/NONE 不需要目标。快照由调用方（守卫/自防御战斗循环）在每轮 ~10 tick 循环里构造。

### 5.4 `CastStrategy` — 施法策略（✅ 已实现，存于 NPC 数据）

```
预设: balanced / offensive / support / defensive / custom
priority: [magicId…]   // 玩家可编辑的优先级列表；custom 预设必须显式给全
```

**预设 = 分类级默认排序**（类内按魔法定义顺序），玩家可整体换预设，也可微调成魔法级 custom：

| 预设 | 默认排序（高→低） |
|---|---|
| `offensive` 火力 | SINGLE_TARGET > AOE > DEFENSE > SUPPORT |
| `balanced` 均衡 | AOE > SINGLE_TARGET > SUPPORT > DEFENSE |
| `support` 支援 | SUPPORT（治疗优先）> DEFENSE > AOE > SINGLE_TARGET |
| `defensive` 防御 | DEFENSE > SUPPORT > AOE > SINGLE_TARGET |

玩家不设任何东西 → 默认 `balanced` 的默认列表，零配置可用。

## 六、模块结构与关键类

```
magic/data/MagicDef.java            ✅ record 镜像 + fromJson（纯数据，仿 MagicCircleSpec；已实现）
magic/internal/SpellbookLoader.java ✅ dataconfig 注册 magic_spells 类目 + getSpec(id)/getAll()
magic/internal/CastBrain.java       ✅ 统一施法脑：L1 优先级扫描 + 策略优先级解析 + 快照目标规则 + conditions 门控
magic/data/WorldSnapshot.java       ✅ 决策输入快照（纯数据 record，已实现）
magic/data/SpellConditions.java     ✅ 魔法内置触发条件（conditions JSON 镜像 + matches，已实现）
shared/api/SpellcastingApi.java     ✅ 决策层对外接口（getKnownSpells/getStrategyPreset/getPriority/setStrategy，已实现）
core/component/SpellbookComponent.java   ✅ NPC 会哪些魔法（magicId 列表，已实现）
core/component/CastStrategyComponent.java ✅ 策略预设 + 优先级列表（已实现）
magic/internal/MagicOp.java         （延后）魔法效果分发注册表——有第二个战斗魔法时再建
npc/data/DeathRecord.java           ✅ 死亡留存记录（纯数据 record + nearest 纯逻辑）
npc/internal/ColonyDeathRegistry.java ✅ 死亡记录 SavedData（平铺列表，3 天过期清理）
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
- **`resolvePriority(strategy, known)`**：预设 → 分类级默认排序（类内按 spellbook 顺序，UTILITY 不进表）；CUSTOM 用显式 magicId 列表，空回退 balanced。
- P3 起 `known` 来自 NPC 的 `SpellbookComponent`（默认 [beam]）经玩家策略解析，替代硬编码。

## 七、决策流程（数据流）

```
触发源：守卫任务循环 / 自防御循环 / 导航回退 / 手动 shift+右键
  → 构造 WorldSnapshot（敌数/自身血/友方最低血/状态）
  → known = 玩家策略（SpellbookComponent + CastStrategyComponent）经 CastBrain.resolvePriority 解析
  → CastBrain.select()
      ├─ L0 硬性覆盖：血量危机/LOS/互斥锁 → 硬性魔法 或 直接返回"不施放"
      ├─ L1 按 priority 列表从上往下扫：
      │     MagicState.canCast(magicId) && 蓝够 && 快照目标规则命中 && conditions 满足 → 选中
      └─ L2 兜底：基础攻击/走位/待命
  → 选出魔法 → 按 id 分发（当前仅 beam → MagicCaster/MagicCastManager/MagicBeamEntity；
      未来 TeleportOp→WandscapeRitualOps、ReviveOp→仪式系统）
  → 门控仍走 npc.tryCastSpell（MagicState 不变）
```

## 八、对接现有代码（迁移路径）

1. ✅ **beam 数据迁入 `magic_spells/beam.json`**：CD 40 / 蓝 50 / 射程 32 / 法阵 arcane_hexagram / 颜色 #A8E0FF；`MagicCaster` 改读 MagicDef（缺失回退常量）。
2. ✅ **teleport 定义进 MagicDef**（CD 300 / 蓝 30，utility 类）；`NavigationSystem` 门控改读 teleport.json，锁时长保留 `WandscapeRitualOps` 引导对齐。
3. ✅ **`GuardCombat.engage` 改造**：不再直接 `MagicCaster.castNpcAt`，经 `CastBrain.select` 选魔法再按 id 分发；守卫/自防御共用此路径。
4. **`MagicCaster` 瘦身**（随 MagicOp 一起）：从"只会射光束"变成"`MagicOp` 分发器的 beam 实现"；玩家调试命令 `cast` / shift+右键 `castNpc` 保留（免费，测试功能，走 L0 调试路径不进玩家策略）。
5. **手动施法**（shift+右键）保持免费、不占蓝（现注释即"测试功能"），只占用 CD 与互斥锁——不归玩家策略管。

## 九、玩家策略与条件（P3 已实现）

**动机**：CastBrain 原来只有 `hasTarget` 布尔，支撑不了防御/治疗/AOE 的条件决策；NPC 无"会哪些魔法"概念。P3 补齐条件决策（数据驱动）+ 玩家可控的策略层（只调一个"策略/优先级"维度，不写脚本）。

### 9.1 条件决策（已实现）

- **`SpellConditions`**（`magic/data/`）：`MagicDef.conditions` JSON → record（min_enemies / self_hp_max / ally_hp_max / no_effect），`matches(WorldSnapshot)` 纯逻辑判定，缺省 = 无条件。
- **`WorldSnapshot`**（`magic/data/`）：决策输入快照（敌数 / 自身血比例 / 友方最低血比例 / 状态 id 集合），由守卫/自防御战斗循环每轮构造（`GuardCombat.buildSnapshot`：半径 16 内敌数、自身血比、半径内其他友方 NPC/村民最低血比、`unwrapKey` 取状态 id）。
- **`CastBrain.select(known, castable, snapshot)`**：目标规则改由快照驱动（HOSTILE 需敌数>0、ALLY 需有受伤友方、DEAD_ALLY 祭坛专属永不自选），并追加 conditions 门控。

### 9.2 玩家策略（已实现）

- **`SpellbookComponent`**（`core/component/`）：NPC 会哪些魔法（magicId 列表，默认 `[beam]`），仿 `MagicState` 实体持有 + NBT 持久。
- **`CastStrategyComponent`**（`core/component/`）：预设（balanced/offensive/support/defensive/custom）+ 自定义优先级列表。
- **`CastBrain.resolvePriority(strategy, known)`**：预设 → 分类级默认排序（类内按 spellbook 顺序，UTILITY 不进表）；CUSTOM 用显式 magicId 列表，空回退 balanced。
- **`SpellcastingApi`**（`shared/api/`，实现 `magic/internal/SpellcastingApiImpl`）：查/改魔法表与策略；`NpcDataPacket` 携带策略数据，`NpcStrategyPacket`（client→server）改策略后回发刷新。

### 9.3 策略 UI（已实现）

- NpcScreen 加「策略」按钮 → **NpcStrategyScreen**：4 预设按钮（均衡/火力/支援/防御，当前预设金框高亮）+ 每魔法启停列表（启用在优先级序在前、停用在后）；点预设按分类排序，点魔法切 CUSTOM（显式顺序）。lang 键 `gui.wandscape.strategy.*` + `magic.wandscape.*`。

## 十、复活与死亡留存（✅ 已实现）

**动机**：守卫 NPC 战死不可逆，殖民地战力永久损失。复活 = UTILITY 魔法（`magic_spells/revive.json`）+ 死亡留存数据，**第一版不走仪式系统**（shift+右键玩家指挥式，独立引导调度）。

### 10.1 死亡留存（已实现）

- **触发**：`NpcDeathHandler`（LivingDeathEvent 钩子，Wandscape 构造器注册），在实体清理前抓快照。
- **数据 `DeathRecord`**（纯 record）：npcId、名字、维度、死亡坐标、死亡时间、所属殖民地、外观（皮肤变体/帽子颜色）、hasDefaultWand、7 属性快照、背包快照（ECS Inventory 的 ResourceStack 列表）。
- **存储 `ColonyDeathRegistry`**：SavedData（`wandscape_npc_deaths`），每世界一份平铺列表。
- **清理**：复活成功后删除；超过 3 游戏日（`EXPIRE_TICKS`）由 `ReviveHandler.tick` 每日 prune。
- **第一版无墓碑方块**：施法时死亡点生成法阵（复用 `MagicCircleCastPacket` 链路），玩家看到法阵即知位置。

### 10.2 复活魔法（已实现）

- **MagicDef**：`id=revive`、`category=utility`、`target_mode=dead_ally`、蓝 80 / CD 600 / 射程 32、法阵 `self_teleport`（紫色传送阵）。
- **触发（P4 原入口，已迁移）**：原为 shift+右键 NPC → `MagicInteractHandler` → `ReviveHandler.castRevive`（施法者周围射程内最近的 `DeathRecord`，门控走 `npc.tryCastSpell`）；**P5 起复活唯一入口为祭坛**（见第十一章），shift+右键施放已移除。
- **引导**：时长 = 复活法阵 spec 时长（法阵完整展开后完成，缺失回退 100 tick）；期间 NPC 面向死亡点、举杖（`startManualCast`）。
- **完成（P5 后）**：`AltarCastExecutor` 引导到期调用 `ReviveHandler.spawnFromRecordAt(level, rec, 祭坛中心最上方)` 生成新 `WandscapeNpc`，恢复名字/外观/属性上限/默认法杖/背包（ECS 重 seed + ColonyMember 修正），删除死亡记录，PORTAL 爆点。
- **虚弱复活**：复活后 **1 血 0 蓝**（`setHealth(1)` + `setMana(0)` + `markManaSeeded` 阻止首 tick 满蓝种子），靠脱战回血（interval 回 1 HP）与魔力回复（10t/1 点）缓慢恢复——复活有代价。
- **失败兜底**：生成位置无地可放等失败 → 记录保留，玩家可重试。
- **与施法决策的关系**：复活不进 NPC 自动战斗决策表（L1）——玩家指挥式，避免 NPC 战斗中弃敌救人。
- **遗留**：装备只恢复 default wand（当前装备系统仅 WAND 槽）；墓碑方块视觉留作后续增强。
- **P5 迁移（已完成）**：复活入口已改为**祭坛**（见下章），shift+右键施放已移除。

## 十一、祭坛施法（P5 已实现）

**动机**：复活等重大魔法不应随手 shift+右键施放。祭坛作为殖民地的"神圣设施"，集中管理重大魔法：玩家在祭坛选中魔法 → NPC 走到祭坛旁施法。

### 11.1 需求（用户定，全部落地）

1. **新建筑类别 altar（祭坛）**：与其他建筑一样放置/维护（`buildings/altar1.json`）。
2. **施法入口**：玩家 V 面板右键祭坛 → AltarScreen → 选中魔法（如复活）执行。
3. **NPC 执行**：NPC 走到祭坛旁边，与其他建筑一样以任务方式施法；**魔法阵/特效在祭坛中心释放**。
4. **魔力来源**：**扣接取祭坛施法任务的 NPC 的蓝**；`SchedulerSystem` 分派时要求其当前魔力 ≥ 该魔法蓝耗（不足则任务挂起等回蓝）。
5. **祭坛 CD/时长**：每个魔法有祭坛侧冷却与引导时长（`altar_cooldown`/`altar_duration`），**按祭坛（building UUID）独立**存放（`AltarCastState` SavedData），**不同祭坛之间不共享**。
6. **altarOnly 约束**：祭坛中的魔法（`altar_only: true`，当前仅 revive）**不能被 NPC 直接施放**——`CastBrain` 自动施法跳过、shift+右键复活移除。
7. **复活目标**：**最近死去的 NPC**（`ColonyDeathRegistry.latest()`，按 deathTime 最新，不限位置）。
8. **复活点**：祭坛包围盒中心最上方方块顶端。

### 11.2 结构（已落地）

```
building/executor/AltarCastExecutor   OpExecutor<AltarCastOp>：幂等复核（祭坛 CD + 魔力 + 锁）→ tryAltarCast 扣蓝占锁 → 设祭坛 CD → 中心起法阵 → 引导 → 到期 fireEffect（revive）
building/internal/AltarCastState      SavedData（wandscape_altar_casts）：按祭坛 UUID 独立存每魔法剩余 CD，每 tick 推进
building/internal/AltarCastHandler    玩家点选校验 + 经 PlayerManualSource 发任务 + tick 降 CD + centerTop 助手
building/client/AltarScreen           MedievalScreen MINIMAL + ScrollableList：列出 altarOnly 魔法（名称/蓝耗/CD/时长/冷却状态）
building/network/                     AltarOpenPacket（server→client）+ AltarCastRequestPacket（client→server）
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
- 引导时长与法阵视觉对齐：`altar_duration` 设为该魔法 circle spec 的 `durationTicks`（revive → self_teleport = 160）。

## 十二、分阶段实施

| 阶段 | 内容 | 状态 |
|---|---|---|
| **P1 数据与分发** | `MagicDef` + `magic_spells` JSON（beam/teleport）+ `SpellbookLoader`（dataconfig）；CD/蓝/射程/视觉改数据驱动 | ✅ 已实现（行为不变，配 6 个解析单测） |
| **P2 决策集中** | `CastBrain`（L1 优先级扫描）；GuardCombat/SelfDefense 经 CastBrain 选魔法再分发 | ✅ 已实现（配 6 个单测） |
| **P3 玩家策略 + 条件** | 分类定案（5 类，已落地）；`SpellConditions` + `WorldSnapshot` + CastBrain 扩展（快照目标规则 + conditions 门控 + resolvePriority）；`SpellbookComponent` + `CastStrategyComponent`（含 defensive 预设）+ `SpellcastingApi` + `NpcScreen` 策略 UI | ✅ 已实现（配单测：WorldSnapshot/SpellConditions/SpellbookComponent/CastStrategyComponent/CastBrain 共 41 个） |
| **P4 死亡留存 + 复活** | `DeathRecord` + `ColonyDeathRegistry`（SavedData）+ `NpcDeathHandler` 钩子；`revive` MagicDef + `dead_ally` 目标 + shift+右键施放 + 引导到期生成 NPC 恢复数据（虚弱复活：1 血 0 蓝） | ✅ 已实现（配 8 个单测） |
| **P5 祭坛施法** | altar 建筑类别 + AltarScreen + 祭坛施法任务（NPC 走到祭坛旁，法阵在祭坛中心）；复活改祭坛唯一入口（目标 = 最近死去不限位置，复活点 = 祭坛中心最上方）；扣接取任务 NPC 的蓝（调度器魔力门槛）、每祭坛每魔法 CD 独立（AltarCastState）、altarOnly 魔法禁 NPC 直接施放 | ✅ 已实现（配单测：MagicState.tryAltarCast/DeathRecord.latest/MagicDef altar 字段/CastBrain altarOnly 跳过） |

P1/P2 玩家无感知（内部重构），P3 起见 UI。每个阶段完成即按 CLAUDE.md 提交规则 commit。

## 十三、依赖与边界

- `MagicDef` 是纯数据 record → 放 `magic/data/`（仿 `MagicCircleSpec` 同在 magic/data）；`CastStrategy`（P3）与 `DeathRecord`（P4）是纯数据 → `core/` 或 `npc/data/`。
- `SpellbookComponent` / `CastStrategyComponent` → `core/component/`（纯 Java 零 MC，仿 `MagicState`）。
- 效果实现 → `magic/internal/`（beam）、`engine/boundary/`（teleport）、`npc/internal/`（复活），`MagicOp` 只做分发。
- 模块间通过 `SpellcastingApi` + 事件，不跨包 new 类（铁律 1）。
- **不做全条件脚本**（FFXII 式）：触发器是 `target_mode` + 内置 `conditions` 阈值，不是玩家可写的判定——避免翻译与理解负担。
