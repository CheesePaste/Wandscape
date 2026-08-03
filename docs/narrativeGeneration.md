# Goal

为游客机器人自动生成叙事文本——从抵达殖民地到离开的旅程故事。玩家可通过右击游客查看"旅行日记"，殖民地可积累"编年史"（高光事件）。叙事数据同时为未来的记忆系统（偏好演化、推荐推荐）提供事实基础。

# Current situation

## 已有数据

游客实体（`TouristEntity`）已经跟踪了构建叙事所需的全部原始数据：

| 数据 | 来源 | 说明 |
|------|------|------|
| 名称 | `touristName` | 如"旅行商人 张三" |
| 样貌 | `DATA_APPEARANCE` | 市民/法师 |
| 等级 | `level` | 1-5 |
| 精力值 | `energy` | 0-200，每次交互消耗 |
| 满意度 | `satisfaction` | 0-100 |
| 偏好 | `typePreferences` | 每建筑类型 5..100，每次访问 -15 |
| 已访问建筑 | `visitedBuildings` | Set\<UUID\>，本次旅程内 |
| 目标状态 | `commuteTarget` / `targetBuildingId` / `targetBuildingCategory` | 当前导航中 |

## 现有叙事输出（简陋）

```java
// TouristMoveGoal.java 中仅有的两处叙事文本：

// 1. 入住旅馆
showActionBar("✨ " + tourist.getTouristName() + " 入住了旅馆 " + bldType);

// 2. 购物
showActionBar("🛒 " + tourist.getTouristName() + " 从 " + bldType + " 购买了 " + purchased
    + " | 满意+" + gain + " 精力-20");
```

所有叙事都是硬编码的 `showActionBar` 单行消息，无历史记录，无个性化，无故事连贯性。

## 当前限制

1. **无记忆**：游客不记得自己刚才去了哪、买了什么。`visitedBuildings` 只用于去重，不记录"什么时间 / 什么感受"。
2. **无叙事生成器**：每个输出点都是就地拼接字符串，没有统一的生成管道和模板系统。
3. **无历史查看**：玩家错过的 ActionBar 消息就永远丢失了。
4. **无情感维度**：满意度只有数值，没有映射到情感标签（欣喜/满意/失望/愤怒）。
5. **离开后无痕迹**：游客离开后所有数据消失，殖民地缺乏"来过谁"的记忆。

# Design

## 1. 记忆模型 (VisitMemory)

### 1.1 数据结构

```java
// 纯数据记录，不依赖任何 AI 框架，放 core/ 或 shared/data/
public record VisitMemory(
    String buildingTypeId,      // e.g. "tavern"
    String buildingDisplayName, // e.g. "冒险者酒馆"
    String category,            // shop / service / hotel
    long gameTime,              // 访问发生的 game tick
    int satisfactionBefore,     // 交互前满意度
    int satisfactionDelta,      // 满意度变化（可正可负）
    int energyDelta,            // 精力变化（通常为负）
    String whatHappened,        // 一句话事件描述，如"购买了 面包"
    Emotion emotion             // POSITIVE / NEUTRAL / NEGATIVE
) {}

public enum Emotion {
    DELIGHTED,   // satisfactionDelta >= 20
    PLEASED,     // 10..19
    SATISFIED,   // 1..9
    NEUTRAL,     // 0
    DISAPPOINTED,// -1..-9
    UPSET        // <= -10
}
```

### 1.2 存储位置

```
TouristEntity (内存，旅程期间):
  └─ List<VisitMemory> recentVisits  // 最多保留 24 条，FIFO
  └─ VisitMemory hotelStay          // 当前宾馆入住（可选）
  └─ long arrivalTime               // 抵达殖民地的时间

ColonyChronicle (ColonySavedData 持久化):
  └─ List<ChronicleEntry> entries   // 最多保留 200 条重要事件
  └─ 非重要事件不在编年史中
```

### 1.3 记忆窗口

游客记忆仅在**当前旅程**内有效，离开时丢弃（目前不需要跨旅程记忆）。如果将来需要，`VisitMemory` 已经含 `gameTime`，直接序列化即可。

## 2. 叙事事件类型 (NarrativeEventType)

### 2.1 事件分类

| 事件类型 | 触发时机 | 重要性 | 入编年史 |
|----------|---------|--------|---------|
| `ARRIVAL` | 游客生成到殖民地 | 低 | 否 |
| `VISIT_SHOP` | 商店交互完成 | 中 | 满意度≥70 或 连续3次满意 |
| `VISIT_SERVICE` | 服务建筑交互完成 | 中 | 元素产出>10 |
| `HOTEL_CHECKIN` | 宾馆入住成功 | 中 | 否 |
| `HOTEL_WAKEUP` | 次日醒来 | 低 | 否 |
| `SATISFACTION_MILESTONE` | 满意度跨过 50/70/100 | 高 | 是 |
| `PREFERENCE_SHIFT` | 偏好达到极限 (≥90 或 ≤10) | 中 | 是 |
| `ENERGY_CRITICAL` | 精力降到 0 | 低 | 否 |
| `MAGE_RECRUIT` | 法师满意度 100% → 简历进酒馆 | **高** | 是 |
| `DEPARTURE` | 游客离开殖民地 | 中 | 满意度≥70 |
| `DEPARTURE_SUMMARY` | 离开时生成旅程总结 | 高 | 是 |

### 2.2 事件数据包

```java
public record NarrativeEvent(
    NarrativeEventType type,
    long gameTime,
    String title,           // "张三在冒险者酒馆喝了一杯"
    String description,     // 详细故事文本
    int satisfactionAfter,  // 事件后的满意度
    Emotion emotion         // 情感标签
) {}
```

## 3. 叙事生成管道

### 3.1 管道结构

```
触发点                  模板引擎              输出
  │                     │                    │
  ├─ onBuildingArrived ─┤                    ├─ ActionBar (仅到达/酒店入住)
  ├─ onHotelCheckin   ─┼─ NarrativeGenerator ┼─ 事件气泡 (购买物品/服务元素 icon×N + 满意度条)
  ├─ onDeparture      ─┤   ├─ 模板匹配       ├─ TouristDiary (存储)
  │                     │   ├─ 情感推断       └─ ColonyChronicle (条件性)
  │                     │   └─ 润色
  │                     │
VisitMemory[] ───────────┘  (上下文增强)
```

> 屏幕提示分工（1.10.34a）：到达与酒店入住保留 ActionBar；购买/服务改为事件气泡
> （`TouristBubblePacket` → 客户端 `TransientBubbleStore`，气泡下方满意度条从交互前值平滑动画到交互后值）；离开无屏幕提示。

### 3.2 两级模板解析机制

叙事生成器在需要生成句子时，按以下优先级查找模板：

```
建筑专属模板 (Building-specific)
  → 读取 data/wandscape/narratives/buildings/<building_id>.json
  → 若找到对应事件模板，使用之
  ↓ 未找到
全局类别模板 (Global Category fallback)
  → 读取 data/wandscape/narratives/<locale>.json
  → 用建筑的 category（shop/service/hotel）匹配通用模板
  ↓ 未找到
安全兜底 (Hardcoded Fallback)
  → 返回最基础的硬编码字符串（"{name} 访问了 {building}"）
  → 保证绝不崩溃
```

#### 3.2.1 建筑专属模板

`data/wandscape/narratives/buildings/<building_id>.json`：

```json
{
  "building_id": "tavern",
  "templates": {
    "visit": [
      "{name} 在{building}的木桌旁坐下，喝了一大杯麦芽酒，{emotion_adj}",
      "{name} 推开{building}的橡木门，酒馆里飘着烤肉的香气，{emotion_adj}"
    ],
    "arrival": [
      "{name} 远远就闻到了{building}飘来的酒香"
    ]
  }
}
```

#### 3.2.2 全局类别模板

`data/wandscape/narratives/zh_cn.json`：

```json
{
  "locale": "zh_cn",
  "category_templates": {
    "shop": {
      "visit": [
        "{name} 从{building}购买了{item}，感到{emotion_adj}",
        "{name} 在{building}淘到了{item}，{emotion_adj}地离开了"
      ]
    },
    "service": {
      "visit": [
        "{name} 在{building}享受了服务，{emotion_adj}",
        "{name} 体验了{building}的设施，觉得{emotion_adj}"
      ]
    },
    "hotel": {
      "checkin": [
        "✨ {name} 入住了{building}，期待明天的旅程"
      ],
      "wakeup": [
        "{name} 在{building}醒来，精力充沛"
      ]
    }
  },
  "generic": {
    "arrival_morning": [
      "{name} 在清晨抵达殖民地，对新的一天充满期待"
    ],
    "arrival_afternoon": [
      "{name} 在午后来到殖民地，打算四处逛逛"
    ],
    "departure_satisfied": [
      "{name} 离开了殖民地，觉得不虚此行"
    ],
    "departure_neutral": [
      "{name} 结束了这次旅程"
    ],
    "departure_unsatisfied": [
      "{name} 失望地离开了殖民地"
    ]
  },
  "emotion_adjectives": {
    "DELIGHTED": ["欣喜若狂","心满意足","激动不已"],
    "PLEASED":   ["开心","愉快","满意"],
    "SATISFIED": ["还行","凑合","勉强满意"],
    "NEUTRAL":   ["面无表情","没什么感觉"],
    "DISAPPOINTED": ["有点失望","不太满意"],
    "UPSET":     ["非常生气","愤愤不平"]
  }
}
```

#### 3.2.3 安全兜底

在 `NarrativeGenerator` 中硬编码最基础模板：

```java
// 编译时保证始终存在，无外部依赖
private static final String FALLBACK_VISIT = "{name} 访问了 {building}";
private static final String FALLBACK_ARRIVAL = "{name} 来到了殖民地";
private static final String FALLBACK_DEPARTURE = "{name} 离开了殖民地";
```

### 3.3 核心类

```
shared/data/
  └─ VisitMemory.java             ← 纯数据 record
  └─ NarrativeEvent.java          ← 纯数据 record
  └─ Emotion.java                 ← 枚举

tourist/internal/
  └─ NarrativeGenerator.java      ← 生成管道
       ├─ generateVisitEvent(VisitMemory) → String
       ├─ generateArrivalText(...) → String
       ├─ generateDepartureText(...) → String
       ├─ generateHotelCheckinText(...) → String
       └─ resolveTemplate(buildingTypeId, category, eventType) → String
           // 1. 查建筑专属模板
           // 2. 查全局类别模板
           // 3. 返回硬编码兜底

tourist/internal/
  └─ NarrativeTemplates.java      ← JSON 加载 + 两级模板查找
       ├─ loadGlobal(String locale)    // data/wandscape/narratives/zh_cn.json
       ├─ loadBuilding(String buildingTypeId) // data/wandscape/narratives/buildings/<id>.json
       ├─ getTemplate(buildingTypeId, category, eventType) → List<String> 候选模板列表
       └─ pickEmotionAdj(Emotion) → String
```

## 4. 情感模型

### 4.1 满意度→情感映射

```
satisfactionDelta ≥ 20  → DELIGHTED
               10..19  → PLEASED
                1..9   → SATISFIED
                0      → NEUTRAL
               -1..-9  → DISAPPOINTED
              ≤ -10    → UPSET
```

### 4.2 情感对叙事的影响

- **正面情感**：叙事偏积极，编年史更多收录
- **负面情感**：叙事带抱怨口吻，降低该建筑类型的偏好衰减速度（"下次不来了" → 额外偏好 -5）
- **连续情感趋势**：离开总结时若连续 3 次正面 → "不虚此行"；若连续负面 → "再也不来了"

## 5. 展示层

### 5.1 屏幕提示（即时）

- **ActionBar（仅到达与酒店入住）**：内容由 `NarrativeGenerator` 生成，不再就地拼字符串。
  ```
  "✨ 张三入住了冒险者酒馆，期待明天的新旅程"
  ```
- **事件气泡（购买/服务，1.10.34a 起）**：购买冒「物品 icon × 数量」气泡，服务冒「随机元素 icon × 数量」气泡；
  服务端 `TouristMoveGoal.sendBubble()` 发 `TouristBubblePacket`（32 格内玩家）→ 客户端 `TransientBubbleStore` →
  `SpeechBubbleRenderer`（气泡）+ `SatisfactionBarRenderer`（气泡下方满意度条，从交互前值平滑动画到交互后值）。
- **离开：无屏幕提示**（1.10.34a 起静默，仍写编年史）。

### 5.2 游客日记（右击查看）

右击游客 → 对话框显示本次旅程的时间线和故事：

```
═══════ 张三的旅行日记 ═══════
Lv.2 市民 | 抵达: 第3天清晨

📍 面包房 — 购买了面包，感到满意 (+5)
📍 铁匠铺 — 修理了装备，有点失望 (-2)
✨ 冒险者酒馆 — 入住了一晚
📍 纺织坊 — 买了丝绸，非常喜欢 (+15)
━━━━━━━━━━━━━━━━━━━━━━━━━━
总访问 4 处 | 满意度: 68% | 精力: 45
```

### 5.3 殖民地编年史（town_hall GUI）

仅记录重要事件，持久化存储：

```
═══ 殖民地编年史 ═══
Day 3 — 法师 李四（Lv.3）满意度达到100%，简历已存入酒馆
Day 3 — 张三 离开殖民地，满意度68%，"下次还来"
Day 4 — 王五 访问了5座建筑，满意度100%，"完美旅程"
```

## 6. 集成点

### 6.1 改动 TouristMoveGoal

```
当前: onBuildingArrived() → interactWithShop/Service → showActionBar(硬编码)
改为: onBuildingArrived() → interact → createVisitMemory() 
      → NarrativeGenerator.generateVisitEvent() 
      → sendBubble(TouristBubblePacket, 32格内玩家)  ← 购买/服务，不再 showActionBar
      → tourist.addVisitMemory()
      → (条件性) colonyChronicle.addEntry()
```

### 6.2 改动 TouristSpawnSystem

```
onSpawn:  记录 arrivalTime, 生成 ARRIVAL 事件
onDepart: 收集所有 VisitMemory → generateDeparture() 事件（无屏幕提示，静默离开）
          → colonyChronicle.addEntry()
```

### 6.3 改动 TouristEntity

```
加: List<VisitMemory> recentVisits
加: long arrivalTime
加: boolean hasNarrativeDiary   ← 控制右击行为（有记忆=显示日记，否则=显示当前状态）
```

## 7. 数据驱动

### 7.1 目录结构

```
data/wandscape/narratives/
  └─ zh_cn.json          ← 中文本地化模板
  └─ en_us.json           ← 英文（将来）
```

### 7.2 模板 JSON 格式

```json
{
  "locale": "zh_cn",
  "templates": {
    "arrival": {
      "morning": [
        "{name} 在清晨抵达殖民地，对新的一天充满期待",
        "{name} 踏着晨光走进了殖民地"
      ],
      "afternoon": [
        "{name} 在午后来到殖民地，打算四处逛逛"
      ]
    },
    "visit_shop": { ... },
    "visit_service": { ... },
    "hotel_checkin": { ... },
    "departure_summary": { ... }
  },
  "emotion_adjectives": { ... }
}
```

## 8. 不做什么

1. **不做跨旅程记忆**：本次旅程的记忆只在旅程内有效。如果将来要跨旅程，加 NBT 序列化即可。
2. **不做 AI 大模型生成**：模板驱动的叙事足够了。NPC 的"智能感"来自模板多样性和上下文丰富度，不来自 LLM。
3. **不做复杂情感推理**：单次 satisfaction delta → emotion 的映射就够。不做"前两次满意第三次失望的混合情绪"。
4. **不做 Brain/Memory 系统迁移**：纯数据 `VisitMemory` 比 Brain 的 codec 简洁得多。如果将来真的需要 Activity 调度（如让游客有猪灵级别的协作行为），那时候再迁移。
5. **不做实时流式叙事**：只在离散事件点生成，不在移动中/寻路中生成。

## 9. 实现阶段

### Phase 1: 数据结构 + 模板加载
- `VisitMemory`, `NarrativeEvent`, `Emotion` record 类
- `NarrativeTemplates` — JSON 加载，随机模板选择
- 模板文件 `zh_cn.json`

### Phase 2: 核心生成管道
- `NarrativeGenerator`：事件生成 + 离开总结
- 集成到 `TouristMoveGoal.onBuildingArrived()`
- 集成到 `TouristSpawnSystem.onTouristDepart()`

### Phase 3: 存储 + 展示
- `TouristEntity` 记忆列表 + 右击日记 GUI
- `ColonyChronicle` 持久化 + town_hall 查看

### Phase 4: 情感影响反馈
- 情感结果影响偏好衰减速率
- 连续情感影响离开总结语气
