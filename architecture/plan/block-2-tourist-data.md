# Block 2 — 游客数据（三条/画像/停留/活动，去 satisfaction/typePreferences）

> 依赖 Block 0 契约（TouristStateHost default 方法 + Activity 枚举）。**只做数据面**：存储字段 + NBT + 生成/离开 + Screen。**不碰** TouristSimulation/TouristMoveGoal/TouristSimSystem/HotelStayHandler（Block 3）。本块与 Block 1/3/4 可并行。

## 目标

1. TouristEntity/TouristShadow 实现 TouristStateHost 新 default 方法（真实字段 + NBT）。
2. **删除 `satisfaction`、`typePreferences` 字段与 NBT key**。
3. TouristSpawnSystem：生成时 roll 画像 + 设停留截止；离开判定改为「满条/截止/无恢复/无床位/idle」。
4. TouristDataPacket + TouristScreen：显示三条 bar + 画像 + 活动 + 停留天数；删满意度单条。

## 负责文件

| 文件 | 动作 |
|---|---|
| `tourist/entity/TouristEntity.java` | 加三条/画像/活动/停留字段 + NBT；删 satisfaction/typePreferences |
| `tourist/internal/TouristShadow.java` | 同上（影子副本） |
| `tourist/internal/TouristSpawnSystem.java` | 生成 roll 画像+设截止；离开判定重写（D6） |
| `tourist/network/TouristDataPacket.java` | 扩展字段（三条/画像/活动/停留） |
| `tourist/client/TouristScreen.java` | 三条 bar + 画像标签 + 活动 + 停留天数 |

## 具体改动

### 1. TouristEntity（当前 :167-234 字段，:450-537 save，:540-652 load）

**删除**：
- `satisfaction` 字段（:168）+ NBT key `satisfaction`。
- `typePreferences: Map<String,Integer>`（:177-180）+ NBT `typePreferences` 复合块 + `getTypePreference/adjustTypePreference`（:751-764）。

**新增字段**：
```java
private int comfortSat, magicSat, wonderSat;      // 填充量，0..need
private int comfortNeed = 100, magicNeed = 100, wonderNeed = 100;  // 需求上限（画像）
private Activity currentActivity;                  // null=无活动
private int activityTicks;                          // 活动剩余/已过 tick
private int occupiedSpot = -1;                      // 占用的交互位下标，-1=未占用
private int nightsStayed;                           // 住店晚数
private long departureDeadline = Long.MAX_VALUE;    // 离境截止（gameTime）
```

**实现 TouristStateHost default 方法的 override**（getter/setter 直读字段；clamp sat 到 [0,need]）。`isFullySatisfied()` = `min(comfortSat/comfortNeed, magicSat/magicNeed, wonderSat/wonderNeed) == 1`。

**NBT save/load 新 key**：`comfortSat/magicSat/wonderSat/comfortNeed/magicNeed/wonderNeed/activity/activityTicks/occupiedSpot/nightsStayed/departureDeadline`。删除旧 `satisfaction/typePreferences` key。

> 注意：`getSatisfaction()/setSatisfaction()`、`getTypePreference()/adjustTypePreference()` 在 Block 0 契约里仍是接口方法。**本块不要删接口方法**（那是 Block 3 的事）——若编译报「未实现」，暂时保留 stub 实现（如 `getSatisfaction()` 由三条派生、`setSatisfaction()` 空操作、typePreference 返回 40），Block 3 删接口时一并清掉。

### 2. TouristShadow（当前 :36-93 字段，:252-324 NBT）

镜像 TouristEntity 的改动：
- 删 `satisfaction`（:69-73 中）、`typePreferences`（:74）+ NBT key。
- 加三条/画像/活动/停留字段 + NBT（影子存绝对 simTick 基；departureDeadline 存绝对值即可）。
- override TouristStateHost default 方法（Block 2 实现）。
- 现有 `TouristStateHost` 的 `getSatisfaction/setSatisfaction/getTypePreference/adjustTypePreference` 保留 stub 或由三条派生。

### 3. TouristSpawnSystem（生成 :322-346；离开 :393-447 cleanupTourists、:458-502 processNightDepartures、:546-583 onTouristDepart）

**生成时**（flushPendingSpawns / forceSpawn）新增：
```java
// 1) roll 画像权重：40% 均衡 {1,1,1}；20% 舒适 {1.4,0.8,0.8}；20% 魔法 {0.8,1.4,0.8}；20% 奇观 {0.8,0.8,1.4}
double[] w = rollPersonaWeights(random);
// 2) 总需求与等级正相关：总需求 = BASE + (level-1)×PER_LEVEL，越高越难满足
int totalNeed = TOURIST_NEED_BASE + (touristLevel - 1) * TOURIST_NEED_PER_LEVEL;
double sum = w[0] + w[1] + w[2];
tourist.setComfortNeed((int) Math.round(totalNeed * w[0] / sum));
tourist.setMagicNeed((int) Math.round(totalNeed * w[1] / sum));
tourist.setWonderNeed((int) Math.round(totalNeed * w[2] / sum));
// 3) 停留截止：2~4 天
tourist.setDepartureDeadline(level.getGameTime() + (TOURIST_STAY_MIN_DAYS + random.nextInt(MAX-MIN+1)) * 24000L);
```

**离开判定重写**（D6）——替换 `cleanupTourists` / `processNightDepartures` 里 sat<50/50-99/100 三段逻辑：
```
离开条件（任一）：
 1. isFullySatisfied() && 夜晚    → 满条离场（grantExperience + mage resume）。
                                     白天满条先继续闲逛，等到夜晚再离场（不立刻走）。
 2. gameTime >= departureDeadline → 到点离场（满条才有经验）
 3. 夜晚 && 无 beds 建筑有空位     → 离场（routeToHotel 失败）
 4. idleTimeout（长时间无目标）    → 离场
```
- **精力 0 且无恢复建筑 → 不在此离场**：改为闲逛（Block 3 行为），直到视野出现恢复建筑 / 夜晚入旅店 / 截止。
- 删除 `getSatisfaction()` 的所有读取；`grantExperience`（:511-523）的条件改为 `isFullySatisfied()`。
- `onTouristDepart`（:546-583）读的 satisfaction → 改读三条/`isFullySatisfied()`；`registerDeparture(uuid,colonyId,satisfaction)` 的 satisfaction 实参改传聚合值（min-ratio×100，Block 4 收口签名）。

### 4. TouristDataPacket（S→C）+ TouristScreen

- `TouristDataPacket.apply`（:49-58）字段扩为：comfort/magic/wonder sat 与 need、currentActivity、nightsStayed、停留天数；删 satisfaction。
- `TouristScreen`：删满意度单条（:94-99）→ 加三条 `drawStatBar`（Comfort/Magic/Wonder，显示 fill/need）；加画像标签（如「偏爱魔法」= need 最高维）；加活动状态文本（Activity 名称）；加「已住 N 晚 / 共 X 天」。
- `drawStatBar` 辅助（:180-190）复用。

## Done 判定

1. `./gradlew build` 绿（Block 3 未合入时，游客行为可能暂时不完整——本块只要求编译 + 数据正确）。
2. 游客 NBT 含三条/画像/活动/停留 key；无 `satisfaction`/`typePreferences` key。
3. TouristScreen 显示三条 bar + 画像 + 活动 + 停留天数。
4. 生成时画像随机且**按等级缩放总需求**（高等级总需求更高）；截止时间在 2-4 天内。
