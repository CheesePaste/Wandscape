# 市民 NPC 系统设计

**状态:** 草案  
**日期:** 2026-06-25  
**目标:** 纯观赏性市民 NPC，提升殖民地"活着"的氛围感

---

## 0. 定位

| | Worker NPC (现有) | Citizen NPC (新增) |
|---|---|---|
| 目的 | 执行任务、消耗资源 | 观赏、氛围、叙事 |
| 复杂度 | ECS + op队列 + 法杖 | 状态机 + 路径 |
| 持久化 | 必须 | 不需要 |
| 行为驱动 | 任务池 push | JSON 时刻表 + 随机 |
| 与建筑关系 | 作为任务目标 | 作为生活场所 |
| 代码位置 | `core/` + `engine/` + `npc/` | 完全新包 `citizen/` |

**核心原则：** 与现有 ECS worker 系统零耦合。不走 ECS，不走 `World` component 体系，不走 `GlobalTaskPool`。

---

## 1. 架构概览

```
citizen/
├── CitizenEntity.java          extends Villager (纯 vanilla，不走 ECS)
├── CitizenManager.java         单例，殖民地级生命周期管理
├── CitizenState.java           状态枚举
├── DailySchedule.java          时刻表数据类
├── Profession.java             职业枚举
├── schedule/
│   └── CitizenScheduleLoader.java  JSON 加载器
└── ai/
    ├── CitizenStateGoal.java   状态驱动的 AI goal
    └── CitizenBubbleGoal.java  随机头顶气泡

data/wandscape/citizen/
├── schedules/                  时刻表 JSON
│   ├── farmer.json
│   ├── merchant.json
│   └── scholar.json
├── names/                      名字池 JSON
│   ├── surnames.json
│   └── given_names.json
└── dialogues/                  气泡文字池 JSON
    ├── idle.json
    ├── working.json
    └── social.json
```

---

## 2. CitizenEntity

```java
// 继承 Villager — 复用村民模型/渲染/睡觉 pose/AI 框架
// 不需要自定义 EntityModel 或 Renderer
public class CitizenEntity extends Villager {
    ...
}
```

**用 Villager 的理由：**
- 村民自带 SLEEPING pose、head bobbing、profession 皮肤变体
- 不用写新 Renderer，vanilla `VillagerRenderer` 直接可用
- `VillagerData`（type + profession + level）可映射到我们的 Profession 枚举
- 玩家直觉："殖民地里的村民" = 活着的城镇

---

## 3. 状态机

```
                         ┌──────────────────┐
                         │     SLEEPING     │  22:00-06:00 在床上
                         └────────┬─────────┘
                                  │ 时刻表 → wake
                         ┌────────▼─────────┐
                         │      WAKING      │  短暂过渡 (粒子: 起床烟)
                         └────────┬─────────┘
                                  │ done
                ┌─────────────────┼─────────────────┐
                ▼                 ▼                 ▼
         ┌────────────┐   ┌────────────┐   ┌────────────┐
         │  COMMUTING │   │  LEISURE   │   │   IDLE     │
         │  (走向目标) │   │  (闲逛/娱乐)│   │ (无业站桩) │
         └─────┬──────┘   └─────┬──────┘   └─────┬──────┘
               │                │                 │
         ┌─────▼──────┐  ┌─────▼──────┐         │
         │  WORKING   │  │  EATING    │         │
         │  (职业表现) │  │(午餐/晚餐) │         │
         └─────┬──────┘  └─────┬──────┘         │
               │                │                 │
               └────────┬───────┘                 │
                        │                         │
                  ┌─────▼──────┐                  │
                  │  CHATTING  │◄─────────────────┘
                  │ (社交闲聊) │  两个市民靠近触发
                  └─────┬──────┘
                        │
             ┌──────────┼──────────┐
             ▼          ▼          ▼
        ┌────────┐ ┌────────┐ ┌────────┐
        │FLEEING │ │ ERRAND │ │RAINING │  覆盖状态 (高于时刻表)
        │(逃跑)  │ │(临时)  │ │(躲雨)  │
        └────────┘ └────────┘ └────────┘
```

### 状态定义

| 状态 | 触发 | 视觉 | 时长 | 碰撞/交互 |
|------|------|------|------|----------|
| `SLEEPING` | 时刻表 22:00-06:00 | 躺在床上，`setPose(SLEEPING)`，Zzz 粒子 | 整夜 | 可穿模 |
| `WAKING` | SLEEPING 结束 | 从床起来，起床烟雾粒子 | 10-20 tick |
| `COMMUTING` | 时刻表 → 走向 workplace/home/tavern | 比 LEISURE 快 30%，直线走向目标 | 直到到达 |
| `WORKING` | COMMUTING 到达 workplace | 按职业不同（见 §7） | 时刻表 slot 时长 |
| `EATING` | 时刻表午餐/晚餐 | 在 tavern 建筑内坐下，手部进食动画 | 5 分钟 |
| `LEISURE` | 时刻表休闲时段 | 慢走，偶尔停步环顾，可能坐长凳 | 时刻表 slot 时长 |
| `IDLE` | 无工作、无家的市民 | 站桩或极慢走，东张西望 | 直到分配工作 |
| `CHATTING` | 两个 LEISURE/IDLE 市民距离 < 3 格，概率触发 | 面对面，头摆动，气泡文字交替弹出 | 10-30 秒 |
| `FLEEING` | 看到僵尸/灾厄村民（即使 invulnerable 也逃跑） | 跑向最近建筑，手臂挥舞 | 直到 10 秒安全 |
| `RAINING` | 下雨 → 覆盖户外状态（LEISURE/WORKING outdoor→COMMUTING home） | 加速走，头顶伞粒子 | 直到雨停 |
| `ERRAND` | 特殊事件（建筑落成围观、节日聚集） | 走向事件位置 | 事件时长 |

### 转换优先级（高→低）

1. **FLEEING** — 看到怪物立即触发，打断一切
2. **ERRAND** — 全局事件覆盖个人时刻表
3. **RAINING** — 天气覆盖户外状态
4. **时刻表** — 正常时间驱动
5. **CHATTING** — 仅覆盖 LEISURE/IDLE，不打断 WORKING/COMMUTING

### 状态切换规则

- COMMUTING 不需要独立 `GOING_TO_BED` / `GETTING_UP` 状态 — 它们就是 `COMMUTING(target=home/bed)` 和 `WAKING`
- 每个状态切换插入 20-60 tick 随机延迟，避免全殖民地同步
- SLEEPING → WAKING → COMMUTING 是单向链，SCHEDULE 驱动

---

## 4. 时刻表 (DailySchedule)

### JSON 格式

```json
{
  "id": "farmer",
  "display_name": "农民",
  "slots": [
    {"time": "06:00", "state": "waking"},
    {"time": "06:30", "state": "commuting",  "target": "farm"},
    {"time": "07:00", "state": "working",    "target": "farm"},
    {"time": "12:00", "state": "commuting",  "target": "tavern"},
    {"time": "12:30", "state": "eating",     "target": "tavern"},
    {"time": "13:00", "state": "commuting",  "target": "farm"},
    {"time": "13:30", "state": "working",    "target": "farm"},
    {"time": "17:00", "state": "commuting",  "target": "home"},
    {"time": "18:00", "state": "leisure",    "target": "plaza"},
    {"time": "21:00", "state": "commuting",  "target": "home"},
    {"time": "22:00", "state": "sleeping",   "target": "home"}
  ]
}
```

- `target` 是建筑**类别**（workplace/home/tavern/plaza/farm/library/garrison），运行时通过 `BuildingApi.getBuildingsByCategory()` 解析为具体坐标
- 每个 slot 之间自动插入 20-60 tick 随机延迟
- 如果殖民地没有对应类别的建筑，回退到 IDLE，mood -1/天

### 各职业时刻表差异

```
farmer:   06:00 wake → 06:30 → farm → 12:00 tavern → 13:00 farm → 17:00 home → 18:00 plaza → 22:00 sleep

guard:    06:00 wake → 06:30 → garrison(站岗) → 12:00 tavern → 13:00 garrison(巡逻)
          → 18:00 tavern(换岗) → 22:00 garrison(值班) → 02:00 home → 02:30 sleep
          // guard 有夜间值班，睡眠时间少但 mood 不减

scholar:  08:00 wake → 08:30 → library → 12:00 tavern → 13:00 park(leisure)
          → 14:00 library → 17:00 home → 18:00 plaza → 22:00 sleep

merchant: 08:00 wake → 08:30 → market → 12:00 tavern → 13:00 market → 18:00 home
          → 19:00 tavern(leisure) → 22:00 sleep

artisan:  07:00 wake → 07:30 → workshop → 12:00 tavern → 13:00 workshop → 17:00 home
          → 18:00 tavern → 22:00 sleep

idler:    08:00 wake → 09:00 plaza → 12:00 tavern → 13:00 plaza → 18:00 tavern
          → 22:00 home → 23:00 sleep

child:    07:00 wake → 08:00 plaza(跑动) → 12:00 tavern → 13:00 park → 17:00 home
          → 18:00 plaza → 20:00 home → 21:00 sleep
```

---

## 5. CitizenManager

```java
public class CitizenManager {

    // ── 配置 ──
    int maxCitizens;                               // 殖民地总床位数
    Map<String, DailySchedule> schedules;           // id → schedule
    Set<String> usedNames;                          // 已用名字

    // ── 运行时 ──
    Map<UUID, CitizenEntity> active;                // citizenId → entity
    Map<UUID, BlockPos> bedAssignments;             // citizenId → bedPos
    Map<UUID, String> recentNarratives;             // 最近叙事事件 (Phase 5)

    // ── 生命周期 ──
    void onColonyCreated(UUID colonyId);
    void onBuildingBuilt(UUID colonyId, String category);
    void onBuildingRemoved(UUID colonyId, UUID buildingId);
    void tick(long worldTime);                      // 每 20 tick 调用
    void spawnBatch(UUID colony, int count);
    void despawnCitizen(UUID citizenId);
}
```

### tick() 伪代码

```java
void tick(long worldTime) {
    int mcHour = (int) ((worldTime / 1000 + 6) % 24);
    int mcMinute = (int) ((worldTime % 1000) * 60 / 1000);

    // 每 MC 天检查一次 mood 衰减/增长
    boolean newDay = (worldTime % 24000 < 20);

    for (var it = active.entrySet().iterator(); it.hasNext(); ) {
        CitizenEntity c = it.next().getValue();
        if (c.isRemoved()) { releaseBed(c); it.remove(); continue; }

        // 1. 覆盖规则 — 优先级最高
        if (c.isThreatenedByMonster()) {
            transitionTo(c, FLEEING, "home"); continue;
        }
        if (c.level().isRaining() && c.state.isOutdoor()) {
            transitionTo(c, COMMUTING, "home"); continue;
        }

        // 2. 时刻表检查
        DailySchedule.Slot slot = c.schedule.getSlot(mcHour, mcMinute);
        if (slot != null && slot.state != c.state && c.state.isSchedulable()) {
            transitionTo(c, slot.state, slot.target);
        }

        // 3. 到达检查
        if (c.state == COMMUTING && arrived(c)) {
            arriveAndStart(c);
        }

        // 4. 社交触发 (LEISURE/IDLE 下, 0.2%/tick + per-pair 60s cooldown)
        if ((c.state == LEISURE || c.state == IDLE) && c.state != CHATTING
                && worldTime - c.lastChatEnded > 1200) {
            CitizenEntity nearby = findNearbyCitizen(c, 3.0);
            if (nearby != null && c.getRandom().nextFloat() < 0.002f) {
                startChatting(c, nearby);
            }
        }

        // 5. 每日 mood 更新
        if (newDay) {
            c.mood = clamp(c.mood + c.dailyMoodDelta(), 0, 100);
            c.dailyMoodDelta = 0;
        }
    }

    // 人口检查
    if (countForColony(null) < maxBedsForColony(null)) {
        spawnBatch(null, 1);
    }
}
```

---

## 6. 职业系统

| Profession | 绑定建筑类别 | WORKING 表现 | LEISURE 偏好 | 手持物品 | 默认时刻表 |
|-----------|-------------|-------------|-------------|---------|-----------|
| FARMER | `farm` | 在建筑周围踱步，间歇作物粒子 | 酒馆 | 小麦 | farmer |
| MERCHANT | `market` / `storage` | 在建筑门口站岗，偶尔进出 | 广场/酒馆 | 绿宝石 | merchant |
| SCHOLAR | `library` / `academy` | 在书架区域走动，附魔粒子 | 公园/图书馆 | 书 | scholar |
| ARTISAN | `workshop` / `production` | 在工坊周围，铁砧粒子 | 酒馆 | 铁锤 | artisan |
| GUARD | `garrison` / `wall` | 站岗 30-60s → 巡逻到下一点 | 酒馆/garrison | 铁剑(装饰) | guard |
| IDLER | 无 | 慢走、东张西望、坐长凳 | 广场/酒馆 | 无 | idler |
| CHILD | 无（跟随父母或 orphan） | 跑动（1.5x），追逐其他小孩 | 广场 | 无 | child |

### 职业分配逻辑

1. `CitizenManager.spawnBatch()` → 遍历殖民地所有建筑类别
2. 每个类别计算 `idealStaff = floor(beds / 4)` 或硬编码 1-2
3. 当前该类别员工数 < idealStaff → 新市民优先分配给缺口最大的类别
4. 无缺口 → 随机 IDLER
5. 无空床 → 不生成

### GUARD 巡逻

- 建筑配置中 boundary 的四角 + 中心 = 5 个巡逻点
- WORKING(patrol) 状态：站桩 30-60 秒 → 走向下一个点 → 循环
- `CitizenStateGoal` 支持 `standDuration` 字段

### CHILD

- 体型缩小到 0.5x
- 继承 Villager 的 `BABY` 逻辑（`setAge(-1)` 永久幼年）
- 不分配工作，跟随最近的 GUARD 或 IDLER（视为"家长"）
- 两个 CHILD 靠近 → 自动开始追逐游戏（互相走近再跑开）

---

## 7. 交互系统

### 市民-玩家

**右键 (Phase 1)：** 聊天栏显示
```
李伟 - 农民 - 情绪 65 (工作中)
```

**右键 (Phase 3+)：** 可选弹简单 GUI — 名字 / 职业 / 情绪条 / 最近气泡履历

### 市民-市民

**CHATTING：**
- 触发条件：两个 LEISURE/IDLE 市民距离 < 3 格，概率 2%/tick
- 行为：面对面站定，头摆动（vanilla villager 自带），气泡文字交替弹出
- 10-30 秒后分开，mood 各 +2
- `recentNarratives` 记录："§7李伟和张芳在广场聊了天"

**CHILD-CHILD 追逐：**
- 两个 CHILD < 5 格 → 一个跑一个追 → 10 秒后交换角色

**碰撞：**
- 市民之间无碰撞（`noPhysics = true` 当状态不是 WORKING/COMMUTING 时）
- 避免门口拥堵

### 市民-建筑

- COMMUTING 到达 → `setFocus` 短暂看向建筑 (1-2 秒)
- WORKING 时偶发工作粒子
- EATING → 在 tavern 建筑内寻找空椅子/床位置坐下
- SLEEPING → 走向分配给他的床，`setPose(Pose.SLEEPING)`，Zzz 粒子每 3-5 tick

### 市民-怪物

- 看到 Zombie / Pillager / Ravager → 立即 FLEEING
- 跑向最近的有门建筑（任意类别），速度 1.5x 正常
- 10 秒内无怪物视野 → 恢复到 FLEEING 之前的状态
- 不扣 mood（纯视觉效果，避免情绪螺旋下降）

---

## 8. 名字系统

Phase 1 内置数组：

```java
// 50 姓 (最常见的中文姓)
String[] SURNAMES = {"李","王","张","刘","陈","杨","赵","黄","周","吴",
                     "徐","孙","马","胡","朱","郭","何","罗","高","林",
                     "郑","梁","谢","宋","唐","许","韩","冯","邓","曹",
                     "彭","曾","萧","田","董","潘","袁","蔡","蒋","余",
                     "于","杜","叶","程","苏","魏","吕","丁","任","沈"};

// 50 名 (性别中立)
String[] GIVENS = {"明","华","文","伟","芳","秀英","丽","强","勇","静",
                   "慧","敏","俊","杰","兰","玲","超","平","刚","涛",
                   "斌","霞","红","建国","海燕","宁","磊","洋","辉","鑫",
                   "怡","珊","君","佳","晨","宇","涵","浩","博","瑞",
                   "思远","晓","雨","梦","毅","恒","淑珍","志强","雪","云"};
```

格式：姓 + 名，无空格。`"李伟"`、`"王秀英"`、`"刘建国"`。`CitizenManager.usedNames` 是 `Set<String>`，避免重名。If 2400 combinations exhausted, append 数字后缀（"李伟2"）。

之后切 JSON：
```json
{
  "surnames":    ["李","王",...],
  "given_names": {"neutral": ["明","华",...], "male": [...], "female": [...]}
}
```

---

## 9. 特殊日子 & 场景事件

### 雨天

- 覆盖所有户外状态 → COMMUTING(home) 或 LEISURE 改为 indoor LEISURE(tavern)
- 户外工作暂停（FARMER/GUARD 停职）
- 室内工作不变（SCHOLAR/ARTISAN/MERCHANT）

### 建筑落成

- `BuildCompleteListener` → `CitizenManager.onBuildingCompleted(buildingId)`
- 所有非 WORKING/SLEEPING 的市民 COMMUTING → 围观新建筑
- 5 分钟后自动解散回到正常时刻表
- `recentNarratives` 记录："§6新酒馆落成，市民聚集庆祝"

### 夜间 (22:00-06:00)

- 所有市民在 `homeBuilding` 床上 SLEEPING
- 无家的市民（homeless）在 tavern 角落站着 IDLE（mood -3/夜）
- GUARD 若值夜班 → 站岗中，不受夜间影响

### 袭击 (Phase 5)

- 所有市民 FLEEING → 跑回最近住宅
- GUARD → WORKING(combat stance)，走向 garrison 门口
- 袭击结束后 5 分钟恢复正常

---

## 10. 数据流

```
                      ┌─────────────────────┐
                      │  data/wandscape/     │
                      │  citizen/schedules/  │──── CitizenScheduleLoader ──► DailySchedule maps
                      │  citizen/names/      │──── NamePool
                      │  citizen/dialogues/  │──── DialoguePool
                      └─────────────────────┘

MC Server Tick
  │
  ▼
Wandscape.java serverTick()
  ├── CitizenManager.tick(worldTime)
  │     ├── for each citizen:
  │     │     ├── schedule.get(time) → 目标状态
  │     │     ├── 状态切换 → CitizenEntity.state = newState
  │     │     ├── CitizenStateGoal: 走向目标建筑 / 闲逛
  │     │     └── CitizenBubbleGoal: 随机气泡
  │     │
  │     ├── 检查人口数 < 目标 → spawn
  │     └── 检查淘汰条件 → despawn
  │
  ├── BuildingApi (只读查询建筑坐标/类别)
  │
  └── (不碰 ECS World、GlobalTaskPool、TaskExecutor)
```

---

## 11. 与现有系统唯一的接触点

| 调用方向 | 接口 | 用途 |
|---------|------|------|
| `CitizenManager` → `BuildingApi` | `getBuildingsByCategory()` | 根据类别找建筑坐标 |
| `ColonyCommand` → `CitizenManager` | `onColonyCreated()` | 殖民地新建时初始化市民 |
| `BuildCompleteListener` → `CitizenManager` | `onBuildingBuilt()` | 建筑建成时+市民 |
| `Wandscape.serverTick()` → `CitizenManager` | `tick()` | 每 tick 驱动 |

不需要新的事件、新的 shared API、新的 ECS Component。

---

## 12. 实施分阶段

### Phase 1 — 走动的名字 (MVP)

- `CitizenEntity extends Villager` + `CitizenManager`
- 生成 5 个市民，内置名字池分配
- 随机走动 + 头顶名字
- 右键聊天栏反馈
- 独立 `EntityType` + spawn egg
- invulnerable
- **不持久化，重进消失**

### Phase 2 — 状态机 + 时刻表

- `CitizenState` + `DailySchedule` + JSON 加载
- 时间驱动状态转换
- `CitizenStateGoal`：按状态走向目标建筑
- 状态粒子效果

### Phase 3 — 职业 + 建筑绑定

- 职业分配 + 建筑类别绑定
- 建筑事件触发生成/移除
- 情绪系统基础版

### Phase 4 — 气泡文字 + 社交

- 头顶气泡文字
- 两个市民靠近时的社交动画
- 情绪影响视觉效果

### Phase 5 — 动态叙事

- 特殊事件触发行为变化
- 市民间关系网络
- 殖民地 prosperity 全局影响

---

## 13. 已决策

| # | 决策 | 定案 |
|---|------|------|
| 1 | 怪物伤害 | **invulnerable** — Phase 1 `setInvulnerable(true)` |
| 2 | 右键交互 | **聊天栏反馈** — Phase 1 右键显示名字+职业+情绪 |
| 3 | 名字池 | **内置数组** — Phase 1 硬编码，后续切 JSON |
| 4 | 人口上限 | **殖民地总床位数** — 扫描建筑 boundary 内 `BlockTags.BEDS` |
| 5 | SLEEPING | **睡在床上** — 从 boundary 扫描实际床方块坐标分配 |
| 6 | 区块加载 | **不重建** — Phase 1 走远消失换新，不做花名册 |
| 7 | 床识别方式 | **有床的就算住宅** — 扫描 boundary 内 `BlockTags.BEDS`，有床即参与分配 |
| 8 | 渲染 | **复用 vanilla Villager** — 继承 `Villager`，零新 Renderer |
| 9 | Villager AI | **全清 brain，CitizenManager 自检触发 FLEEING** — `registerBrainGoals()` 全清后自定义 goals |
| 10 | tick 控制 | **CitizenManager.tick() 集中驱动** — 每 20 tick 遍历所有市民 |
| 11 | Vanilla 职业 | **`profession=NONE`** — 无视 vanilla 职业系统 |
| 12 | EntityType | **独立 `EntityType<CitizenEntity>` + spawn egg** |
| 13 | FLEEING 检测 | **CitizenManager 扫描附近 Monster** — 不依赖 vanilla brain |
| 14 | GUARD 巡逻 | **Phase 1 站桩** — 只在 garrison origin 站岗，巡逻 Phase 3 做 |
| 15 | CHILD 跟随 | **单独 FollowGoal** — P3 优先级，定期更新跟随目标（最近成年市民） |
| 16 | CHATTING 频率 | **0.2%/tick + per-pair 60s cooldown** — 期望 25s 触发一次 |

## 14. 床系统 — 关键架构影响

人口上限 = 殖民地总床位数。不依赖建筑配置加字段，而是**运行时扫描建筑边界内的实际床方块**。

### 技术基础

建筑已有 `BuildingConfig.BoundaryBox(min, max)`（相对 origin 的偏移），
`EnqueueHelper.computeWorldBox()` 已将其转为世界坐标 AABB。`CitizenManager` 不需要新增 building config 字段。

### BuildingApi 新增

```java
/** 扫描建筑边界内所有床方块，返回世界坐标列表。 */
List<BlockPos> findBeds(UUID buildingId);
```

实现：`boundary → computeWorldBox → 遍历 AABB 内所有 BlockPos → 检查 `BlockTags.BEDS` → 返回列表。

### 分配逻辑

1. `CitizenManager` 维护 `Map<UUID, BlockPos> bedAssignments`（citizenId → bedPos）
2. 新市民生成 → 遍历所有住宅建筑的 `findBeds()` → 剔除已被占用的床 → 分配空床
3. 市民消失 → 释放床
4. 无空床 → 不生成新市民
5. 床方块被玩家破坏 → 对应市民变为无家（mood -30），尝试重新分配，若无空床则慢慢消失
6. 建筑拆除 → 释放该建筑所有床 → 相关市民变为无家
