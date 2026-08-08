# 聊天区输出审查清单

> 用途：逐条决定每处聊天/动作栏消息「保留 / 删除 / 加翻译键 / 改 Log」。
> 口径：`sendSystemMessage` / `displayClientMessage` / `sendSuccess` / `sendFailure` / `broadcastSystemMessage`。不含头顶气泡（SpeechBubble）、不含 GUI 屏幕文案。
> 翻译键基建：`I18n.name(key, fallback)`（已有 `message.wandscape.projection.cannot_pin`、`message.wandscape.overview.cannot_pin`、`message.wandscape.town.welcome`）。带参数建议 `Component.translatable(key, args)`。

**决策标记**（填到每条的「决定」列）：
- `保留` — 原样保留
- `删` — 删除该消息（或降级 `Log.debug`）
- `加键` — 换成翻译键
- `改` — 修改文案后保留

---

## 一、正常游玩反馈 —— 聊天区

### 1.1 酒馆招募 NPC `building/network/TavernRecruitPacket.java`

| # | 位置 | 触发 | 当前文案 | 通道 | 决定 |
|---|---|---|---|---|---|
| 1 | :75 | 酒馆未关联殖民地 | `[Wandscape] This tavern is not assigned to any colony.` | 聊天 |  |
| 2 | :93 | 元素不足 | `[Wandscape] Insufficient elements: recruiting costs 10000 of every element (first recruit free).`（含数值） | 聊天 |  |
| 3 | :114 | NPC 生成失败 | `[Wandscape] Failed to recruit NPC.` | 聊天 |  |
| 4 | :145 | 招募成功 | `[Wandscape] Mage recruited! Lv.X 强度:Y 工速:Z 施速:W 护甲:V (坐标)`（中英混排，多参数） | 聊天 |  |
| 5 | :172 | 系统不可用 | `[Wandscape] Tavern system not available.` | 聊天 |  |
| 6 | :179 | 无效选择 | `[Wandscape] Invalid mage selection.` | 聊天 |  |
| 7 | :188 | 简历法师生成失败 | `[Wandscape] Failed to recruit mage.` | 聊天 |  |
| 8 | :215 | 简历法师招募成功 | `[Wandscape] Mage {name} recruited! Lv.X 强度:Y 工速:Z 施速:W 护甲:V`（中英混排，多参数） | 聊天 |  |

### 1.2 仓库

| # | 位置 | 触发 | 当前文案 | 通道 | 决定 |
|---|---|---|---|---|---|
| 9 | `warehouse/network/WarehouseActionPacket.java:141` | 手中无物存仓 | `[Wandscape] Nothing in hand to deposit` | 聊天 |  |
| 10 | `warehouse/WarehouseNotificationHandler.java:27` | 资源不足（由 `shared/event/ResourceInsufficientEvent.java:31` 生成） | `[Wandscape] ⚠ Colony is short on {resource}: needs {n}, has {n}` —— **广播给所有在线玩家** | 聊天 |  |

### 1.3 投影建造 `projection/network/ProjectionPlacePacket.java`

| # | 位置 | 触发 | 当前文案 | 通道 | 决定 |
|---|---|---|---|---|---|
| 11 | :58 | 未知建筑类型 | `[Projection] §cUnknown building type: {id}` | 聊天 |  |
| 12 | :69 | 建筑 API 不可用 | `[Projection] §cBuilding API unavailable` | 聊天 |  |
| 13 | :79 | 建造失败 | `[Projection] §c{error}`（透传建造错误） | 聊天 |  |
| 14 | :93 | 首免成功 | `[Projection] §a{buildingName} §fplaced at ({pos}) — §eFREE first build, no materials consumed` | 聊天 |  |
| 15 | :100 | 常规建造成功 | `[Projection] §a{buildingName} §fplaced at ({pos}) — §aNPC will construct` | 聊天 |  |

### 1.4 祭坛施法 `building/internal/AltarCastHandler.java`（纯中文）

| # | 位置 | 触发 | 当前文案 | 通道 | 决定 |
|---|---|---|---|---|---|
| 16 | :74 | 魔法不可在祭坛施放 | `该魔法不可在祭坛施放` | 聊天 |  |
| 17 | :79 | 建筑系统未就绪 | `建筑系统未就绪` | 聊天 |  |
| 18 | :85 | 祭坛不存在或未完工 | `祭坛不存在或未完工` | 聊天 |  |
| 19 | :92 | 冷却中 | `祭坛冷却中（剩余 X 秒）`（数值） | 聊天 |  |
| 20 | :98 | 施法进行中 | `该祭坛正在施法中` | 聊天 |  |
| 21 | :104 | 无复活记录 | `该殖民地没有可复活的死亡记录` | 聊天 |  |
| 22 | :108 | 法师魔力不足 | `没有魔力足够（≥X）的法师 NPC`（数值） | 聊天 |  |
| 23 | :126 | 任务系统未就绪 | `任务系统未就绪` | 聊天 |  |
| 24 | :133 | 施法已安排 | `已安排祭坛施法：{magicId}`（参数） | 聊天 |  |

### 1.5 殖民地自动创建 / 面板

| # | 位置 | 触发 | 当前文案 | 通道 | 决定 |
|---|---|---|---|---|---|
| 25 | `shared/network/PanelStateTogglePacket.java:63` | 面板打开时自动建殖民地 | `殖民地尚未建立，已自动创建「{name}的殖民地」——放置市政厅后自动关联。` | 聊天 |  |
| 26 | `shared/network/ColonyCreateRequestPacket.java:58` | 市政厅关联已有殖民地 | `市政厅已关联至现有殖民地。` | 聊天 |  |
| 27 | `shared/network/ColonyCreateRequestPacket.java:66` | 创建失败 | `创建殖民地失败。` | 聊天 |  |

### 1.6 建筑交互 `building/internal/BuildingInteractHandler.java`

| # | 位置 | 触发 | 当前文案 | 通道 | 决定 |
|---|---|---|---|---|---|
| 28 | :160 | 药水站未实现 | `[Wandscape] Potion Station — not yet implemented`（占位） | 聊天 |  |
| 29 | :247 | 建筑无 node_config | `[Wandscape] {type} — no node_config`（内部错误外漏） | 聊天 |  |

### 1.7 建筑/道路扫描导出 `building/scanner/network/BuildingScannerExportPacket.java`（纯英文）

| # | 位置 | 触发 | 当前文案 | 通道 | 决定 |
|---|---|---|---|---|---|
| 30 | :65 | 找不到扫描仪 | `§cNo scanner found at {pos}` | 聊天 |  |
| 31 | :72 | 未设建筑 ID | `§cSet a building ID before exporting` | 聊天 |  |
| 32 | :80 | 未定义边界 | `§cNo boundary defined` | 聊天 |  |
| 33 | :316 | 建筑导出成功 | `§aExported building '{id}' to §e{path}` | 聊天 |  |
| 34 | :321 | 建筑导出失败 | `§cFailed to export: {msg}` | 聊天 |  |
| 35 | :382 | 边界内无道路方块 | `§cNo road blocks found inside boundary box` | 聊天 |  |
| 36 | :412 | 道路预设导出成功 | `§aExported road preset '{id}' to §e{path}` | 聊天 |  |
| 37 | :417 | 道路预设导出失败 | `§cFailed to export road preset: {msg}` | 聊天 |  |

### 1.8 投影模式进入

| # | 位置 | 触发 | 当前文案 | 通道 | 决定 |
|---|---|---|---|---|---|
| 38 | `projection/network/ProjectionEnterPacket.java:46` | 进入失败 | `[Projection] {error}` | 聊天 |  |
| 39 | `projection/network/ProjectionEnterResponsePacket.java:61` | 无法进入投影模式 | `[Projection] §eCannot enter projection mode` | 动作栏 |  |

### 1.9 欢迎语（已有翻译键 ✓）

| # | 位置 | 触发 | 当前文案 | 通道 | 决定 |
|---|---|---|---|---|---|
| 40 | `WandscapeClient.java:329` | 进入城镇 | `Component.translatable("message.wandscape.town.welcome")` ✅ 已走翻译键 | 聊天 | 保留 |

---

## 二、动作栏提示

| # | 位置 | 触发 | 当前文案 | 决定 |
|---|---|---|---|---|
| 41 | `shared/ui/panel/AnomalyScreen.java:173` | 发送修复/营业指令 | `§a已发送修复指令` / `§a已发送营业指令` |  |
| 42 | `shared/ui/guidance/GuideSession.java:38` | 新手引导开始 | `§e[新手引导] §f跟随引导，逐步建设你的殖民地！` |  |
| 43 | `road/client/RoadPlacementController.java:202` | 未设起终点 | `{tag} §eSet both start and end points first` |  |
| 44 | `road/client/RoadPlacementController.java:217` | 填充任务提交 | `[Fill] §aFill task submitted! NPC will fill the cube.` |  |
| 45 | `road/client/RoadPlacementController.java:224` | 地形平整任务提交 | `[Destroy/Fill] §aTerrain flatten task submitted! NPC will flatten the area.` |  |
| 46 | `road/client/RoadPlacementController.java:235` | 曲线道路任务提交 | `[Spline Road] §aSpline road task submitted! NPC will pave the curve.` |  |
| 47 | `road/client/RoadPlacementController.java:244` | 道路任务提交 | `[Road] §aRoad task submitted! NPC will pave the path.` |  |
| 48 | `road/client/SplineEditorController.java:396` | 空模型无法建造 | `§cCannot build: empty model or template` |  |
| 49 | `road/client/SplineEditorController.java:482` | 建造任务已发 | `§aSent build task with {n} blocks and {n} spline points!` |  |
| 50 | `projection/client/ProjectionFlightController.java:203` | 无法固定投影 | `[Projection] §c` + `I18n.name("message.wandscape.projection.cannot_pin")` ✅ 已走 I18n | 保留 |
| 51 | `overview/client/OverviewFlightController.java:436` | 无法固定 | `[Overview] §c` + `I18n.name("message.wandscape.overview.cannot_pin")` ✅ 已走 I18n | 保留 |
| 52 | `tourist/internal/TouristMoveGoal.java:247` | 游客首次到达 | 到达叙事（`NarrativeGenerator.generateArrival` 生成，含昼夜时段参数） |  |
| 53 | `tourist/internal/TouristMoveGoal.java:606` | 游客入住旅馆 | `✨ {name} 入住了旅馆 {type}!` |  |

---

## 三、调试/开发命令反馈（`command/` + `guard/`）

> 均为 `/wandscape`、`/transport`、`/tourist`、`/logfilter` 等命令反馈，默认建议保持英文原样，仅列出供你确认。

### 3.1 `command/ColonyCommand.java`
| # | 位置 | 当前文案 | 决定 |
|---|---|---|---|
| 54 | :90 | `[Wandscape] No overworld available` |  |
| 55 | :113 | 失败结果透传（result 字符串） |  |
| 56 | :133 | `[Wandscape] Failed: 你已拥有殖民地，不能创建第二个。` |  |
| 57 | :140 | `[Wandscape] no government building config found (need a building JSON with category=government)` |  |
| 58 | :175 | `[Wandscape] Failed to spawn NPC at {pos}` |  |
| 59 | :215 | `[Wandscape] Colony '{name}' created!`（多行：ID/市政厅/NPC/库存/半径/Tip） |  |
| 60 | :271/:295 | `[Wandscape] Player-only command` |  |
| 61 | :278/:302 | `[Wandscape] No colony within 256 blocks of your position` |  |
| 62 | :284 | `[Wandscape] Colony {id8} destroyed` |  |
| 63 | :309 | `[Wandscape] Level manager not ready` |  |
| 64 | :313 | `[Wandscape] Colony {id8} level -> {level}` |  |

### 3.2 其他命令
| # | 位置 | 当前文案 | 决定 |
|---|---|---|---|
| 65 | `command/GuideCommand.java:32/:51` | `该指令只能由玩家在游戏内执行` / `已成功打开 Markdown 引导测试视窗` |  |
| 66 | `command/MagicCommand.java:43/:47/:51/:53` | `仅玩家可施放魔法阵` / `未找到法阵 {id}` / `施放法阵 {id}` / `已有施法进行中`（中文） |  |
| 67 | `command/TransportCommand.java` :101/:114/:132/:135/:140/:162/:166/:199/:204/:214/:220 | `[Transport]` 系列（Unknown item / Spawned / arrived / Round-trip / flying / Batch / Player-only / Not initialized） |  |
| 68 | `command/TouristCommand.java` :78/:91/:116/:128/:136/:141/:157/:180/:187 | `[Tourist]` 系列（Spawn triggered / state 切换 / Unknown state / Unknown layer / Cooldown 开关） |  |
| 69 | `command/GenerateElementMappingsCommand.java` :46/:56/:62/:73/:74/:113/:117 | `[Wandscape]` 系列（映射生成 dry-run/force 报告，含 `msg.trim()` 大段输出） |  |
| 70 | `command/AuditElementsCommand.java` :54/:61/:77 | 种子映射审计结果（大段输出） |  |
| 71 | `command/FillBuildingCommand.java` :81/:89/:108 | 填充注册结果 |  |
| 72 | `command/ConsumeWarehouseCommand.java` :48/:66/:99 | 清空仓库结果 |  |
| 73 | `command/SeedWarehouseCommand.java` :56/:74/:103 | 播种仓库结果 |  |
| 74 | `command/PublishBlueprintCommand.java` :63/:106/:117/:135/:140/:151/:160/:167 | 蓝图发布系列 |  |
| 75 | `command/NavTestCommand.java` :48/:74/:82/:89/:104 | NPC 寻路测试系列 |  |
| 76 | `command/StressTestCommand.java` :60/:69/:124 | 压力测试系列 |  |
| 77 | `command/RecoveryCommand.java` :40/:59/:71/:84 | 恢复/任务池状态系列 |  |
| 78 | `command/LogFilterCommand.java` :62/:67/:69/:77/:84/:112/:120/:123/:126/:134/:141 | 日志过滤开关/白名单系列 |  |
| 79 | `command/SplineEditorCommand.java` :41/:47/:60/:66 | 样条编辑器进出系列 |  |
| 80 | `guard/GuardCommand.java` :44/:78 | 守卫状态系列 |  |

---

## 待你逐条决定的要点提示

1. **必改（混排/硬编码）**：#4、#8 酒馆招募成功中英混排；#2 费用数值硬编码。
2. **建议降级 Log**：#29（node_config 内部错误）、#57（config 缺失）、#34/#37（导出错误细节）。
3. **广播注意**：#10 资源不足发给所有在线玩家，若加键须服务端发键、客户端渲染。
4. **已有翻译键，无需处理**：#40、#50、#51。
5. **纯命令反馈（三）**：默认保留英文，除非你要求统一本地化。

> 决定后请在对应「决定」列填 `保留/删/加键/改`，我再逐条落地并 commit。
