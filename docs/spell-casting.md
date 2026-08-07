# NPC 施法决策层 — 设计文档

> **状态（2026-08-07）**：**P1 数据与分发、P2 决策集中、P4 死亡留存 + 复活均已实现**（见文末实施表）。
> 分类已定案为 5 类（SINGLE_TARGET / AOE / DEFENSE / SUPPORT / UTILITY）。
> **P3 玩家策略 + CastBrain 条件扩展** 与 **P5 祭坛施法** 规划中（未实现）。
> 本文既记录已落地结构，也是 P3/P5 的实施蓝图。

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

### 5.2 `conditions` — 内置触发条件（数据驱动，非玩家脚本）

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

### 5.3 `WorldSnapshot` — CastBrain 的输入（纯数据 record，可单测）

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

### 5.4 `CastStrategy` — 施法策略（存于 NPC 数据）

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
magic/internal/CastBrain.java       ✅ 统一施法脑：L1 优先级扫描（列表顺序 + 门控可施放 + 目标规则）
                                    ⏳ P3 扩展：吃 WorldSnapshot + conditions
magic/data/WorldSnapshot.java       （P3）决策输入快照（纯数据 record）
shared/api/SpellcastingApi.java     （P3）决策层对外接口（getKnownSpells/getStrategy/setStrategy…）
core/component/SpellbookComponent.java   （P3）NPC 会哪些魔法（magicId 列表）
core/component/CastStrategyComponent.java (P3) 策略预设 + 优先级列表
magic/internal/MagicOp.java         （延后）魔法效果分发注册表——有第二个战斗魔法时再建
npc/data/DeathRecord.java           ✅ 死亡留存记录（纯数据 record + nearest 纯逻辑）
npc/internal/ColonyDeathRegistry.java ✅ 死亡记录 SavedData（平铺列表，3 天过期清理）
npc/internal/NpcDeathHandler.java   ✅ LivingDeathEvent 钩子：死亡瞬间抓快照
npc/internal/ReviveHandler.java     ✅ 复活魔法：dead_ally 目标解析 + 门控 + 引导调度 + 生成 NPC
```

### 6.1 `MagicOp` — 效果分发（**未实现，延后**）

当前 `AtomicOp`（op/api/AtomicOp.java）是 sealed interface + `OpExecutorRegistry` 注册表：**纯数据请求 + 执行器按类型分发**。`MagicOp` 计划照此模式：

- sealed interface `MagicOp`，每个魔法一个 variant（`BeamOp`/`TeleportOp`/未来的 `AoEOp`/`BuffOp`…），携带该效果所需参数。
- 执行器按 magicId 注册：beam 效果走现有 `MagicCaster`/`MagicCastManager`/`MagicBeamEntity`；teleport 走 `engine/boundary/WandscapeRitualOps.self_teleport`（已有）；复活走 `ReviveExecutor` + 仪式。
- **效果实现仍留在各模块 internal**，`MagicOp` 只做分发——不违反"引擎是请求层、适配层是实现"。

> **为何延后**：当前只有一个战斗魔法（beam），守卫循环里按 magicId 一个 if 即可分发；
> 建单实现的 sealed 层级是死代码。等第二个战斗魔法（AOE/单攻变体）落地时再建。

### 6.2 `CastBrain` — 统一施法脑（✅ 已实现，P3 扩展）

- **纯决策函数**，不是新的任务系统：`select(known, castable, hasTarget) → MagicDef?`，纯 Java 可单测。
- 调用方（守卫/自防御）在 ~10 tick 战斗循环里构造 `castable` 判定（互斥锁 + CD + 蓝）喂入；拿到结果后按魔法 id 分发执行。
- **P3 扩展**：签名改为 `select(known, castable, snapshot)`，加 conditions 判定（5.2/5.3）。
- P3 起 `known` 来自 NPC 的 `SpellbookComponent` 与玩家策略（当前为 `defaultCombatSpells()` = [beam]）。

## 七、决策流程（数据流）

```
触发源：守卫任务循环 / 自防御循环 / 导航回退 / 手动 shift+右键
  → 构造 WorldSnapshot（敌数/自身血/友方最低血/状态）+ 已知魔法表
  → CastBrain.select()
      ├─ L0 硬性覆盖：血量危机/LOS/互斥锁 → 硬性魔法 或 直接返回"不施放"
      ├─ L1 按 priority 列表从上往下扫：
      │     MagicState.canCast(magicId) && 蓝够 && target_mode 命中 && conditions 满足 → 选中
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

## 九、复活与死亡留存（✅ 已实现）

**动机**：守卫 NPC 战死不可逆，殖民地战力永久损失。复活 = UTILITY 魔法（`magic_spells/revive.json`）+ 死亡留存数据，**第一版不走仪式系统**（shift+右键玩家指挥式，独立引导调度）。

### 9.1 死亡留存（已实现）

- **触发**：`NpcDeathHandler`（LivingDeathEvent 钩子，Wandscape 构造器注册），在实体清理前抓快照。
- **数据 `DeathRecord`**（纯 record）：npcId、名字、维度、死亡坐标、死亡时间、所属殖民地、外观（皮肤变体/帽子颜色）、hasDefaultWand、7 属性快照、背包快照（ECS Inventory 的 ResourceStack 列表）。
- **存储 `ColonyDeathRegistry`**：SavedData（`wandscape_npc_deaths`），每世界一份平铺列表。
- **清理**：复活成功后删除；超过 3 游戏日（`EXPIRE_TICKS`）由 `ReviveHandler.tick` 每日 prune。
- **第一版无墓碑方块**：施法时死亡点生成法阵（复用 `MagicCircleCastPacket` 链路），玩家看到法阵即知位置。

### 9.2 复活魔法（已实现）

- **MagicDef**：`id=revive`、`category=utility`、`target_mode=dead_ally`、蓝 80 / CD 600 / 射程 32、法阵 `self_teleport`（紫色传送阵）。
- **触发**：shift+右键 NPC → `MagicInteractHandler` → `ReviveHandler.castRevive`：施法者周围射程内最近的 `DeathRecord`；无则提示玩家。门控（互斥锁 + revive 独立 CD + 魔力）走 `npc.tryCastSpell`。
- **引导**：时长 = 复活法阵 spec 时长（法阵完整展开后完成，缺失回退 100 tick）；期间 NPC 面向死亡点、举杖（`startManualCast`）。
- **完成**：`ReviveHandler.tick`（onServerTick 驱动）到期后在死亡点附近安全位置生成新 `WandscapeNpc`，恢复名字/外观/属性上限/默认法杖/背包（ECS 重 seed + ColonyMember 修正），删除死亡记录，PORTAL 爆点。
- **虚弱复活**：复活后 **1 血 0 蓝**（`setHealth(1)` + `setMana(0)` + `markManaSeeded` 阻止首 tick 满蓝种子），靠脱战回血（interval 回 1 HP）与魔力回复（10t/1 点）缓慢恢复——复活有代价。
- **失败兜底**：生成位置无地可放等失败 → 记录保留，玩家可重试。
- **与施法决策的关系**：复活不进 NPC 自动战斗决策表（L1）——玩家指挥式，避免 NPC 战斗中弃敌救人。
- **遗留**：装备只恢复 default wand（当前装备系统仅 WAND 槽）；墓碑方块视觉留作后续增强。
- **P5 迁移**：复活入口将改为**祭坛**（见下章），shift+右键施放届时移除。

## 十、祭坛施法（P5 规划，未实现）

**动机**：复活等重大魔法不应随手 shift+右键施放。祭坛作为殖民地的"神圣设施"，集中管理重大魔法：玩家在祭坛选中魔法 → NPC 走到祭坛旁交互施法。

### 10.1 需求（用户定）

1. **新建筑类别 altar（祭坛）**：与其他建筑一样放置/维护。
2. **施法入口**：玩家 V 面板（俯瞰视角）右键祭坛 → 打开祭坛 UI → 选中魔法（如复活）执行。
3. **NPC 执行**：NPC 走到祭坛旁边，与其他建筑一样与祭坛交互施法；**魔法阵/特效在祭坛中心释放**。
4. **复活目标**：**最近死去的 NPC**（按 deathTime 最新，不限位置——不再按施法者范围搜索）。
5. **复活点**：祭坛包围盒中心最上方方块顶端。
6. **入口迁移**：复活唯一入口改为祭坛（P4 的 shift+右键施放移除）。

### 10.2 结构草案

```
building/   category 加 "altar"（建筑 JSON）；BuildingInteractHandler 加 altar 分支（V 面板右键 → 打开 AltarScreen）
building/client/  AltarScreen（MedievalScreen MINIMAL）：列出祭坛可施魔法（当前仅 revive），点选 → 发任务
task/       新 Op（如 AltarCastOp，仿 RitualOp 模式）：NPC 走到祭坛旁 → 施法（门控走 tryCastSpell）
magic/      魔法阵定位到祭坛中心：MagicCircleCastPacket 以祭坛包围盒中心 + 地面高度为原点
npc/        ColonyDeathRegistry 加 latest()（按 deathTime 最新，纯逻辑可单测）；生成点 = 祭坛 AABB 中心最上方
```

### 10.3 依赖与复用

- `BuildingApi.getBuildingBounds(UUID)`（已有）取祭坛包围盒 → 中心 + 最高面生成点。
- 建筑交互先例：`BuildingInteractHandler` 已有 shop/potion_station 等分支；GUI 先例 `MedievalScreen MINIMAL`（HotelScreen/ShopScreen）。
- 施法/法阵/复活逻辑复用：`MagicCircleCastPacket`、`tryCastSpell` 门控、`ReviveHandler.spawnNpcFromRecord`（改生成点来源）。
- 复活"最近死去"选择：`ColonyDeathRegistry.latest()` 纯逻辑（deathTime 最大），配单测。

### 10.4 边界

- 祭坛可施魔法列表 = 数据驱动（魔法定义加"可祭坛施放"标记，或按 category=utility 过滤）——P5 实施时定。
- 跨殖民地限制：第一版不做（同 P4，范围最近即取）。
- 祭坛施法进任务系统（NPC 移动/交互是任务），不进 CastBrain 自动决策表。

## 十一、分阶段实施

| 阶段 | 内容 | 状态 |
|---|---|---|
| **P1 数据与分发** | `MagicDef` + `magic_spells` JSON（beam/teleport）+ `SpellbookLoader`（dataconfig）；CD/蓝/射程/视觉改数据驱动 | ✅ 已实现（行为不变，配 6 个解析单测） |
| **P2 决策集中** | `CastBrain`（L1 优先级扫描）；GuardCombat/SelfDefense 经 CastBrain 选魔法再分发 | ✅ 已实现（配 6 个单测） |
| **P3 玩家策略 + 条件** | 分类定案（5 类，已落地）；`Conditions` + `WorldSnapshot` + CastBrain 扩展；`SpellbookComponent` + `CastStrategyComponent`（含 defensive 预设）+ `NpcScreen` 策略 UI | ⏳ 规划中 |
| **P4 死亡留存 + 复活** | `DeathRecord` + `ColonyDeathRegistry`（SavedData）+ `NpcDeathHandler` 钩子；`revive` MagicDef + `dead_ally` 目标 + shift+右键施放 + 引导到期生成 NPC 恢复数据（虚弱复活：1 血 0 蓝） | ✅ 已实现（配 8 个单测） |
| **P5 祭坛施法** | altar 建筑类别 + AltarScreen + 祭坛施法任务（NPC 走到祭坛旁，法阵在祭坛中心）；复活改祭坛唯一入口（目标 = 最近死去不限位置，复活点 = 祭坛中心最上方） | ⏳ 规划中 |

P1/P2 玩家无感知（内部重构），P3 起见 UI。每个阶段完成即按 CLAUDE.md 提交规则 commit。

## 十二、依赖与边界

- `MagicDef` 是纯数据 record → 放 `magic/data/`（仿 `MagicCircleSpec` 同在 magic/data）；`CastStrategy`（P3）与 `DeathRecord`（P4）是纯数据 → `core/` 或 `npc/data/`。
- `SpellbookComponent` / `CastStrategyComponent` → `core/component/`（纯 Java 零 MC，仿 `MagicState`）。
- 效果实现 → `magic/internal/`（beam）、`engine/boundary/`（teleport）、`npc/internal/`（复活），`MagicOp` 只做分发。
- 模块间通过 `SpellcastingApi` + 事件，不跨包 new 类（铁律 1）。
- **不做全条件脚本**（FFXII 式）：触发器是 `target_mode` + 内置 `conditions` 阈值，不是玩家可写的判定——避免翻译与理解负担。
