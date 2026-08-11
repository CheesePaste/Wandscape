# 游客模拟——偏好与目标选择系统

本文解释游客如何决定「下一站去哪」（目标选择 / Find-Best-Action 偏好系统），供新读者快速理解游客行为。基于真实源码：`tourist/internal/TouristSimulation.java`、`tourist/internal/TouristSpotManager.java`、`tourist/internal/TouristSpawnSystem.java`、`tourist/internal/TouristMoveGoal.java`、`Config.java`。

## 一、决策管线总览（TL;DR）

游客每一次「选下一站」走同一条管道：

1. **收集候选**：视野内（`TOURIST_VISION_RADIUS`）已加载、结构完好、营业中、有交互位（spot）的建筑。
2. **过滤**：已逛过（visited）的剔除（ATM 缺钱豁免）；精力 0 只去 relax；夜晚未满条优先旅店。
3. **评分**：`score = 满意度增益 + 精力/钱包紧急加分`，spot 全满时**乘以排队降权乘数**。
4. **加权抽取**：按 score 加权随机选一栋（`Math.max(0.5, score)` 兜底，候选非空必选）。
5. 走到目标 → 占一个 spot 做动作 → 交互结算 → 填三条 → 再选下一站。

核心入口：`TouristSimulation.selectNextTarget`（过滤）→ `buildingScore`（评分）→ `weightedPick`（抽取）。

## 二、决策输入：游客状态

### 三条需求条（Comfort / Magic / Wonder）

- 每条 = `fill / need`。`need` 由画像 + 等级决定，`fill` 从 0 起步。
- **画像 roll**（`TouristSpawnSystem.rollAndSetPersona`）：40% 均衡 `{1,1,1}`；20% 舒适 `{1.6,0.7,0.7}`；20% 魔法 `{0.7,1.6,0.7}`；20% 奇观 `{0.7,0.7,1.6}`。
- `totalNeed = NEED_BASE(60) + (level−1) × NEED_PER_LEVEL(20)`；`need_d = round(totalNeed × w_d / Σw)`。
- 1 级游客：均衡 20/20/20；侧重 32/14/14（及其置换）。等级越高总需求越大、越难喂饱。
- 满条 = 三条 fill 全到 need。**满条才给经验**（防刷，里程碑不是流水）；夜晚满条由离场窗口处理。

### 精力（Energy）

- 范围 0–`TOURIST_MAX_ENERGY(100)`。
- 交互消耗：shop −20、service −`energyPerUse`（建筑配置）。relax +`energyRestore`（建筑配置）。旅店住宿回精力（清晨退房 +100）。

### 钱包与旅费

- `wallet`（随身现金）+ `travelFund`（总旅费 = ATM 取现池上限）。
- `startingWallet = BASE_WALLET(200) + level × WALLET_PER_LEVEL(300)`（1 级 = 500）。
- `travelFund = startingWallet × ATM_TRAVEL_FUND_MULTIPLIER(3.0)`（1 级 = 1500）。
- 购物从 wallet 扣；ATM 把 travelFund 分批取进 wallet。

### 停留（visited 记忆）

- `visitedBuildings`：整个停留期**不重置**（红线 #8 防挂机），同一建筑只逛一次；ATM 是唯一豁免。
- 停留 2–4 天（`departureDeadline`），到点离场。

## 三、候选过滤（`selectNextTarget`）

只产出候选，不评分。所有条件不满足 → 返回 null → 游客闲逛（wander）。

1. **视野**：建筑锚点与游客水平距离 ≤ `TOURIST_VISION_RADIUS(48)`；实体寻路（requireLoaded=true）还要求目标区块已加载。
2. **可用性**：非 shutdown、结构完整、`isTouristTarget`、`interact_spots` 非空（0-spot 建筑对游客无效）。
3. **visited 门**：已逛过 → 剔除；**ATM 豁免**：`atmReusable` 通过（travelFund>0 且 wallet<initialWallet/4 且取现冷却已过）时 ATM 跳过 visited 门，可分批取现。
4. **精力 0**：只能去 relax（energyRestore>0）；无恢复建筑 → 闲逛，**不离场**。
5. **夜晚 + 未满条**：优先旅店（`service.maxOccupancy>0` 且有空位，不查 visited）；视野内无旅店 → 回退普通建筑（仍尊重 visited、精力 0 只去 relax），避免傍晚干晃 5000 tick。

## 四、评分公式（Find-Best-Action，`buildingScore`）

```
score = 满意度增益 satisfactionGain
      + 精力紧急加分（relax，精力比 < 0.25 → +100）
      + 钱包紧急加分（ATM，缺钱 → +50 / +100）
若 spot 全满：score ×= 排队降权乘数 queuePenaltyMultiplier(排队人数)
```

### 满意度增益（核心偏好）

```
对每一维 d（comfort/magic/wonder）：
  gap_d = max(0, need_d − sat_d)
  gain += min(gap_d, round(建筑值_d × BAR_GAIN_COEFF(1.0)))
```

即「这次访问能把总满意度抬多少」，与结算 `fillBars` 逐维一致。建筑值 = 配置 comfort/magic/wonder + 商店上货加成（`ShopStockManager.getGoodsBonus`）。均衡建筑（30/30/30，总增益 90）常比单维夸张（90/0/0，总增益 80）更受欢迎——不会因某一维数值夸张就吸走游客、浪费访问。

### 精力/钱包紧急加分

- 精力比 `energy/100 < ENERGY_RESTORE_THRESHOLD(0.25)` 且建筑是 relax → **+100**。
- ATM（且 `atmReusable` 通过）：钱包 = 0 → **+100**；钱包 < initialWallet/4 → **+50**。
- 数值与单次满意度增益（~15–150）同量级，只做微调、不碾压选店。

### 排队降权（等比例，非固定减分）

- 仅当 **spot 全满**时触发：`score ×= queuePenaltyMultiplier(totalQueueLength)`。
- 乘数：1 人 ×0.75（−25%）、2 人 ×0.5（−50%）、3 人 ×0.25（−75%），**封顶 −75%**；0 人 ×1.0（无惩罚）。
- 设计意图：多建同类型 = 排队短 = 降权轻；排队短的好店仍比空置低价值建筑更受欢迎。
- **历史教训**：2026-08-10 前是固定 `QUEUE_PENALTY=3000`，相对单次增益高 20–200 倍，好店一满就被压到权重地板 0.5、与 0 分垃圾建筑等权，导致「满钱游客宁可取钱/闲逛也不排好店」（低价值售货机前排长队）——已改等比例降权。

## 五、加权抽取（`weightedPick`）

- 候选权重 `w_i = max(0.5, score_i)`，按权重和随机抽取（`Math.random() × Σw` 逐个减）。当前公式下 score 恒 ≥ 0，0.5 仅作最小权重兜底——保证候选非空必选，不挑剔。

## 六、Spot 与排队

- `interact_spots`（建筑 JSON）个数 = 该建筑**同时交互人数上限**。每个 spot 一个动作（browse/eat/bathe/...），交互时长 = 模式预设块 `interaction_duration_ticks`（shop/service/relax/atm 各块同字段，与 spot 无关）。
- 到达建筑 → 认领一个空 spot；**全满 → 排队**。
- 排队：每 spot 各一队（严格 FIFO），新游客排到**队最短**的 spot 后，沿该 spot facing **反方向**一个贴一个站（间距 `queueSlotSpacing(1.0)`），队首紧贴正在交互的游客、朝向与交互游客同向；只有队首可认领空 spot，队首离队后自动前移。
- 超 `QUEUE_WAIT_TOLERANCE_TICKS(2400)` 放弃 → 强制闲逛并记 visited（避免立刻重选同一栋）。
- 队列/占用在 `TouristSpotManager`（内存单例），实体与影子 sim 共用，保证双方感知同一占用。

## 七、交互结算（`fillBars` 无惩罚）

- `fillBars`：`sat_d += round(建筑值_d × coeff)`，封顶 need_d。**无惩罚**——普通低值建筑也正向涨（休闲友好，不逼玩家堆最强）。
- 四类（`TouristSimulation.performXxxInteraction`）：
  - **shop**：`ShopStockManager` 按 wallet 可买则购买（扣库存、按 profitRate 给殖民地元素、游客 spendWallet），精力 −20；买不起/没货也照样填条，行程记「逛了一圈，什么也没买」。
  - **service**：精力 −energyPerUse，`elementOutput` 全量写入 `ColonyItemBank`（产元素），填条。
  - **relax**：精力 +energyRestore（封顶 100），填条。
  - **atm**：取现 `min(round(initialWallet × uniform[0.2,0.5]), travelFund)`，填条，记录取现时间（配合冷却分批取）。

## 八、数值速查

| 项（Config / 常量） | 默认 | 含义 |
|---|---|---|
| `TOURIST_BAR_GAIN_COEFF` | 1.0 | 每维增益 = round(建筑值 × coeff)，封顶缺口 |
| `TOURIST_NEED_BASE` / `NEED_PER_LEVEL` | 60 / 20 | 1 级总需求 / 每级增量 |
| `TOURIST_BASE_WALLET` / `WALLET_PER_LEVEL` | 200 / 300 | 随身现金 = base + level × per-level |
| `TOURIST_ATM_TRAVEL_FUND_MULTIPLIER` | 3.0 | travelFund = 现金 × 系数（取现池上限） |
| `TOURIST_ATM_WITHDRAW_COOLDOWN_TICKS` | 2400 | 两次取现最小间隔 |
| `TOURIST_ENERGY_RESTORE_THRESHOLD` | 0.25 | 精力低于此比例 → relax +100 |
| `TOURIST_VISION_RADIUS` | 48 | 目标选择只看半径内已加载建筑 |
| `TOURIST_QUEUE_WAIT_TOLERANCE_TICKS` | 2400 | 排队超时放弃 |
| `TOURIST_QUEUE_SLOT_SPACING` | 1.0 | 排队站位间距（格） |
| `TOURIST_STAY_MIN/MAX_DAYS` | 2 / 4 | 停留天数下限/上限 |
| `TOURIST_MAX_ENERGY`（WandscapeConstants） | 100 | 精力上限 |

评分私有常数（`TouristSimulation`）：`ENERGY_URGENCY_BONUS=100`、`WALLET_LOW_BONUS=50`、`WALLET_EMPTY_BONUS=100`、排队降权乘数 `1.0 / 0.75 / 0.5 / 0.25`（封顶 −75%）。

## 九、为什么这样设计（红线）

- **休闲友好**：无「数值不够就扣好感」的惩罚，普通建筑也正向涨。
- **多样城镇，不堆最强**：画像自组织，要喂饱不同画像自然补三类建筑——行为引导而非规则逼迫。
- **多建同类型 = 多交互位 = 排队短**：真实收益，玩家看到「这家店火爆，该多开一家」。
- **满条才给经验**：经验是里程碑不是流水。
- **visited 不重置**：防挂机；ATM 唯一豁免（配合冷却分批取现）。

## 十、相关文档

- 模块总览（生成/移动/影子模拟/酒店/酒馆/叙事）：[modules/tourist.md](modules/tourist.md)
- 建筑 JSON 格式（`interact_spots` / shop / service / relax / atm 块）：[data/buildings.md](data/buildings.md)
- 设计决策（三值改造 / 排队降权 / 加分下调）：[decisions.md](decisions.md)
