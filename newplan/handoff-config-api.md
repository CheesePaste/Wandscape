# Handoff — Config + API 重构（Step 1 完成，Step 2 不做）

> 给接手人/AI：当前在 `refactor` 分支。config-api 重构的 **Step 1 已完成并提交**；**Step 2（2e API 收敛）用户明确不做**。期间合并了 `origin/refactor1`（对面日志治理等，保留双方）。

## 意图（这一步干了什么）

把「玩家向 Config」与「addon/整合包作者向 API」重新分层：

- **Config 精简**：只留玩家会调、会想改的标量（约 20 键 + `ClientConfig` 3 键）。
- **深度值分发**：战斗/经济/节奏数值一律离开 Config——有调优需求的面进**领域 API 可调面**（内部统一 `foundation/util/BalanceValues` 覆盖层），不太重要的**就地定义**为消费类常量，想改的走 mixin。
- **WandscapeConstants 只剩结构性常量**（建筑类别 / 任务优先级 / 卡死阈值 / 寻路上限）——原来混进去的数值手感值全分发掉。
- **API 补程序化能力**：`addElement`→boolean + `addAllElements`、`grantExperience`/`getColonyLevel`/`getColonyExp`、`registerMapping`/`unregisterMapping`、各领域 `get/set` 可调面、`ProductionApi` 新建、`ColonyMetrics→ColonyStatus` 更名。

## 已完成（提交均在 refactor 历史）

1. `ColonyMetrics→ColonyStatus` 改名。
2. `WarehouseApi`：`addElement` 改 boolean + `addAllElements` + `insertItems` boolean。
3. `ElementApi`：`registerMapping`/`unregisterMapping`（运行时覆盖层，查先覆盖层回落 JSON registry，不触发事件）。
4. `ColonyApi`：`grantExperience`/`getColonyLevel`/`getColonyExp`（注入式持 `ColonyLevelManager`，不引 content→impl）。
5. Config 删 4 死键；拆 `ClientConfig`（flySpeed/preview 3 键；`PARTICLE_LEVEL` 实测服务端读 → 留 COMMON）。
6. 删 `TOURIST_BAR_GAIN_COEFF`（锁 1.0）、`GUARD_PEACE_FLEE_RANGE`（并入 `FLEE_START_DIST`）。
7. 新建 `foundation/util/BalanceValues`：内部覆盖层（默认常量 + `ConcurrentHashMap`），逻辑读它、领域 API `get/set` 委托它。
8. 领域 API 扩可调面：`NpcApi`（guard 战斗 + npc 回血 + revive + scepter + mage 休息）、`WarehouseApi`（transport）、`BuildingApi`（decoration + 建造耗时）、`SpellcastingApi`（施法阈值）、`ProductionApi`（新建，craft 耗时）。
9. Bal 值读点迁 `BalanceValues`，Config 删相应键。
10. 6d 常量批就地：`STUCK_*`/游客时间窗·间距·半径/`RAID_*`/`DECOR_SCAN`/`SETTLEMENT` 等读点迁消费类常量（`STUCK_*`/`NPC_WALK_THRESHOLD` 复用 `WandscapeConstants`）；Config 删 31 个常量键。
11. `TAVERN_RECRUIT_COST`/`TOURIST_MAX_ENERGY` 进 Config（玩家向键）。
12. 合并 `origin/refactor1`：对面日志治理体系（`LogCategory`/`LogCommand`/热点高噪降噪）+ `TransportItemEntityRenderer` 归位 warehouse，**保留双方**。

## 关键文件

- `foundation/util/BalanceValues`（全部可调值 + 覆盖层）
- `api/`：`NpcApi`/`WarehouseApi`/`BuildingApi`/`SpellcastingApi`/`ProductionApi`/`ColonyApi`/`ElementApi`/`ColonyStatusApi`
- `Config.java`（约 20 玩家向键）、`ClientConfig.java`
- `foundation/registry/WandscapeConstants`（仅结构性）
- `newplan/config-api.md`（重构方案）、`newplan/config-api-decisions.md`(逐键决策/待拍板历史)

## 明确不做 / 注意

- **Step 2（2e API 收敛）不做**：砍 `ScepterApi`/`TavernApi` + 瘦 `ColonyApi`/`BuildingApi`/`TouristApi` 内部桥 + **解散 `WandscapeEngine` 静态定位器**。若未来要做，读 `newplan/config-api-decisions.md` Part D（已写好完整去留表 + 装配方案）。原因：用户判断当前不迫切，属高风险管理。
- 合并冲突已保留双方：`NavigationSystem`（我方 `WandscapeConstants.STUCK_MAX_RETRIES` + 对面 `Log.debug(LogCategory.NPC,...)`）、`status.md`（我方两条 + 对面日志治理一条）。
- 编译验证过 `compileJava` 绿；**未跑** `./gradlew build`/`test`（测试大删是独立事项，CLAUDE.md 允许测试暂不绿）。
- 涉及存档格式的深度值都走"删键即断档"（开发期不承诺存档兼容），无版本号兜底新增。

## 下一步（若非空）

- 无强制定项。若要在 config-api 基础上继续，可先 review Step 1 关键改动（`BalanceValues`、各领域 API 新签名），或按 `config-api.md`/`decisions.md` 补漏。
