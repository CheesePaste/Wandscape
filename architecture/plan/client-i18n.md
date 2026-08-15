# 客户端 i18n 全量迁移（玩家可见文本英文化）

状态：进行中 — Batch 1/2/3 已完成（2026-08-15），Batch 4 已完成（2026-08-15，未提交），Batch 5 待做
日期：2026-08-15
作者：Wandscape 开发（AI 协作）

## 1. 背景与目标

模组绝大多数 UI/聊天文本已走 i18n（`assets/wandscape/lang/en_us.json` + `zh_cn.json`，各 713 键），
但仍有一批 **~48 个文件、~600 处硬编码中文**（含 `\uXXXX` 转义形式），英文玩家会直接看到中文。
目标：把**全部玩家可见文本**迁移到 i18n，为英文发行做准备。

### 1.1 排除项（不翻，开发向）

- `Config.java`（48 处）：TOML 配置注释，开发者向。
- 服务端 `Log.*` 语句（`building/executor/AltarCastExecutor.java` 11、`engine/HostileTargetingHandler.java` 2、`engine/service/GuideProgressService.java` 等）：开发日志，非玩家可见。
- 纯代码注释/Javadoc（`shared/ui/panel/BuildingSort.java` 排序注释、`tourist/internal/TouristMoveGoal.java` 字段注释等）。

### 1.2 已完成

- **新手引导 10 步全量 i18n**（本迁移前）：`GuideRegistry` 存键 → `GuideRenderer` 渲染时 `Component.translatable(key).getString()`；键 `guide.wandscape.*`，两语言文件各 +59。引导用这套机制，**本次不动**。
- **`BuildPopPanelOverlay`**（建筑参数右侧面板，9 键 `gui.wandscape.buildpop.*`）：本迁移的**模式范本**。

## 2. i18n 模式（权威约定）

所有新迁移**严格照抄**以下模式（参考已完成的 `BuildPopPanelOverlay.java`）。

### 2.1 代码写法

引入：`import com.wsteam.wandscape.shared.ui.I18n;`

| 上下文 | 写法 |
|---|---|
| 渲染字符串（`g.drawString` / `g.drawCenteredString` / `ImGui.text` / `ImGui.button` / `textColored` / `drawTooltip` / `textMuted` / `addSectionHeader` / `drawModeButton` 等） | `I18n.name("<key>", "<中文fallback>").getString()` |
| 组件上下文（`displayClientMessage` / `setToast` / `setTitleBar`） | `I18n.name("<key>", "<中文fallback>")`（返回 MutableComponent） |
| 带占位符 | `I18n.name("<key>", "<中文fallback 含 %s/%d>", arg1, arg2).getString()`，占位符与参数顺序一一对应 |

Fallback 一律用**原中文**：键缺失时回退中文（模组主语言），不破坏现行为。

### 2.2 ImGui 特有规则（`SplineEditorImGui` 等）

1. **`##ID` 后缀必须保留在同一字符串**（ImGui 用它做元素哈希）：`"捕捉脚下位点##SetFeetStart"` →
   `I18n.name("<key>", "捕捉脚下位点").getString() + "##SetFeetStart"`。
2. **FontAwesome 图标常量（`ICON_*`）保留拼接**：`ICON_CUBE + " 下发直线铺设任务"` →
   `ICON_CUBE + " " + I18n.name("<key>", "下发直线铺设任务").getString()`（空格保留）。
3. **`\uXXXX` 转义的中文**（如 `"曲线..."`）同样视为待翻字符串；Java 源码可直接写中文（UTF-8），
   建议迁移时顺手还原成可读中文。
4. `String.format("模式: %s  |  视角: %s", toolName, topDownStr)`：外层走 `I18n.name(key, fallback, ...)`，
   内层 `toolName`/`topDownStr` 各自先本地化再作参数传入。

### 2.3 § 颜色码与 \n

`§e`/`§b`/`§a` 与 `\n` 等格式码**原样保留在字符串内**（fallback 与语言文件值都带）。

### 2.4 键命名

- 统一 `gui.wandscape.<area>.<name>`；聊天消息用 `message.wandscape.<area>.<name>`（与现有 `message.wandscape.*` 一致）。
- **每个批次用独立 area 前缀**，跨批不得重名（见 §4），避免共享语言文件键冲突。
- 同一批内相同字符串复用同一键（如两个扫描器的「扫描区域」）。

### 2.5 语言文件

每个新键**同时**写入两个文件：
- `src/main/resources/assets/wandscape/lang/en_us.json` → 英文值
- `src/main/resources/assets/wandscape/lang/zh_cn.json` → 中文值（= 代码里的 fallback）

JSON：2 空格缩进，`\n` 用 JSON 转义 `\\n`。末尾键无逗号。

## 3. 扫描与验证

### 3.1 找残留硬编码中文

Python（在仓库根执行）——找「引号内 CJK（含 `\uXXXX`）且该行不是 I18n 调用」：

```python
import re, glob
cjk_esc = re.compile(r'\\u[0-9a-fA-F]{4}')
def is_cjk_esc_in_quotes(s):
    return any(cjk_esc.search(m.group(1)) for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', s))
for path in sorted(glob.glob('src/main/java/**/*.java', recursive=True)):
    for i, l in enumerate(open(path, encoding='utf-8').read().splitlines(), 1):
        s = l.strip()
        if s.startswith(('*','//','/*','*/')): continue
        if re.search(r'I18n\.name|Component\.translatable|\.name\(|translatable', s): continue
        if re.search(r'"[^"]*[一-鿿]', s) or is_cjk_esc_in_quotes(s):
            print(f'{path}:{i}: {s[:100]}')
```

### 3.2 验证（每批完成时）

```bash
# 1) 两个 lang 文件 JSON 合法 + 键集对称（en/zh 一一对应）
python - <<'EOF'
import json
en = json.load(open('src/main/resources/assets/wandscape/lang/en_us.json', encoding='utf-8'))
zh = json.load(open('src/main/resources/assets/wandscape/lang/zh_cn.json', encoding='utf-8'))
print(len(en), len(zh), set(en) == set(zh))
EOF
# 2) 编译
./gradlew compileJava
# 3) 测试（如涉及纯逻辑）
./gradlew test
```

**完成标准**：目标文件无残留硬编码 CJK 字符串；两个 lang 文件 JSON 合法且键集一致；
代码用到的每个新键都在两语言文件存在；编译通过。

## 4. 批次划分（5 批，互不依赖）

> 每批 = 一组文件 + 独立键前缀。**完成一批提交一批**（`feat(i18n): <批次名> 屏幕文案英文化`）。
> 语言文件是共享文件：批间**顺序做**最稳（每批只加自己的键，前缀不同不冲突）；若并行需合并键、由一人统一收。

### Batch 1 — 道路与建造（Road & Build）✅ 本批由主会话完成
| 文件 | 处数 | 键前缀 |
|---|---|---|
| `road/client/SplineEditorImGui.java` | 150 | `gui.wandscape.roadstudio.` |
| `road/data/RoadPreset.java` | 7 | `gui.wandscape.road.` |
| `projection/client/BuildPopPanelOverlay.java` | 7 | `gui.wandscape.buildpop.`（已完成） |
| `projection/client/ProjectionFlightController.java` | 1 | `message.wandscape.projection.` |
| `shared/ui/panel/WandscapePanelController.java` | 1 | `message.wandscape.projection.` |
| `building/internal/BuildingUnlockChecker.java` | 2 | `message.wandscape.unlock.` |
| `building/scanner/InteractSpotMarkerBlock.java` | 4 | `gui.wandscape.scanner.` |

### Batch 2 — 建筑经营界面（酒馆/祭坛/市政厅/游客屏）
| 文件 | 处数 | 键前缀 |
|---|---|---|
| `building/client/TavernScreen.java` | 23 | `gui.wandscape.tavern.` |
| `building/network/TavernRecruitPacket.java` | 8 | `message.wandscape.tavern.` |
| `building/client/AltarScreen.java` | 2 | `gui.wandscape.altar.` |
| `building/internal/AltarCastHandler.java` | 9 | `message.wandscape.altar.` |
| `building/client/TownHallCreateScreen.java` | 1 | `gui.wandscape.townhall.` |
| `building/client/TownHallScreen.java` | 1 | `gui.wandscape.townhall.` |
| `tourist/client/TouristScreen.java` | 19 | `gui.wandscape.touristscreen.` |
| `tourist/internal/HotelStayHandler.java` | 1 | `message.wandscape.tourist.` |
| `tourist/internal/TouristSpotManager.java` | 1 | `message.wandscape.tourist.` |

### Batch 3 — 扫描器与异常面板 ✅ 本批由主会话完成
| 文件 | 处数 | 键前缀 |
|---|---|---|
| `building/scanner/client/CreativeScannerScreen.java` | 83 | `gui.wandscape.scanner.` |
| `building/scanner/client/ScannerScreen.java` | 35 | `gui.wandscape.scanner.`（同串同键，跨文件去重） |
| `building/scanner/network/ScannerExportPacket.java` | 3 | `message.wandscape.scanner.` |
| `building/scanner/network/ScannerValuePacket.java` | 7 | `message.wandscape.scanner.` |
| `shared/ui/panel/AnomalyScreen.java` | 12 | `gui.wandscape.anomaly.` |
| `shared/ui/panel/WandscapePanelOverlay.java` | 5 | `gui.wandscape.panel.` |

### Batch 4 — 游客与 NPC 内容（对话/气泡/叙事）
| 文件 | 处数 | 键前缀 |
|---|---|---|
| `shared/client/bubble/AmbientTextPools.java` | 109 | `gui.wandscape.bubble.` |
| `tourist/internal/NarrativeTemplates.java` | 13 | `gui.wandscape.narrative.` |
| `shared/data/CharacterNames.java` | 5 | `gui.wandscape.npcname.` |
| `npc/entity/WandscapeNpc.java` | 22 | `message.wandscape.npc.` |
| `npc/internal/ReviveHandler.java` | 4 | `message.wandscape.npc.` |
| `tourist/internal/TouristState.java` | 5 | `message.wandscape.tourist.` |
| `tourist/internal/TouristSimulation.java` | 6 | `message.wandscape.tourist.` |
| `tourist/internal/TouristSimSystem.java` | 3 | `message.wandscape.tourist.` |
| `tourist/internal/TouristSpawnSystem.java` | 1 | `message.wandscape.tourist.` |
| `tourist/internal/TouristMoveGoal.java` | 1 | `message.wandscape.tourist.` |
| `shared/network/ColonyCreateRequestPacket.java` | 2 | `message.wandscape.colony.` |
| `magic/internal/MagicSpellExecutors.java` | 2 | `message.wandscape.magic.` |
| `engine/colony/ColonyLevelData.java` | 1 | `gui.wandscape.colony.` |

> 注：`TouristMoveGoal`/`TouristSimulation`/`TouristState` 等的多数行是注释/日志，实际要翻的字符串远少于"处数"；逐处核对 player-facing。

### Batch 5 — 命令输出与杂项
| 文件 | 处数 | 键前缀 |
|---|---|---|
| `command/ColonyCommand.java` | 3 | `message.wandscape.command.` |
| `command/GuideCommand.java` | 2 | `message.wandscape.command.` |
| `command/MagicCommand.java` | 4 | `message.wandscape.command.` |
| `command/TavernCommand.java` | 10 | `message.wandscape.command.` |
| `command/TouristCommand.java` | 8 | `message.wandscape.command.` |
| `imgui/ImGuiManager.java` | 10 | `gui.wandscape.imgui.` |
| `shared/ui/guide/GuideTestScreen.java` | 2 | `gui.wandscape.guide.` |
| `shared/ui/markdown/navigation/DocumentLoader.java` | 1 | `gui.wandscape.doc.` |
| `shared/ui/guidance/GuideSession.java` | 1 | `message.wandscape.guide.` |

> `AltarCastExecutor`（日志）与 `Config.java`（配置注释）**不在任何批次**，跳过。

## 5. 每批实施步骤

1. 打开文件，找出所有硬编码中文（含 `\uXXXX`）。
2. 按 §2 模式替换为 `I18n.name(...)`，保留格式码 / `##ID` / 图标 / 占位符。
3. 新键加入 `en_us.json`（英文）+ `zh_cn.json`（中文）。
4. §3 验证本批：无残留 + JSON 合法 + 键一致 + 编译。
5. 提交：`feat(i18n): <批次名> 屏幕文案英文化`。

## 6. Batch 1 状态（主会话已完成）

- [x] `SplineEditorImGui` 全量键迁移（150，含 `\uXXXX` 转义串）
- [x] `RoadPreset` 预设显示名（7）
- [x] `BuildPopPanelOverlay`（9 键，模式范本）
- [x] `ProjectionFlightController` / `WandscapePanelController` / `BuildingUnlockChecker` / `InteractSpotMarkerBlock`
- [x] 语言文件合并（en_us + zh_cn）+ JSON 校验 + 编译

## 6.1 Batch 2 状态（已完成，未提交）

- [x] `TavernScreen`（23 处，`gui.wandscape.tavern.*` + 复用 `resume_hint`/`first_free`/`hired_success`/`specialty_*`）
- [x] `TavernRecruitPacket`（8 处，`message.wandscape.tavern.*`）
- [x] `AltarScreen`（2 处，`gui.wandscape.altar.cost_duration`）
- [x] `AltarCastHandler`（9 处，`message.wandscape.altar.*`）
- [x] `TouristScreen`（19 处，`gui.wandscape.touristscreen.*`）
- [x] `HotelStayHandler`（1 处，`message.wandscape.tourist.inn`）
- [x] 语言文件合并（en/zh 各 +43 −2）+ JSON 校验 + 编译 + 测试
- [ ] 备注：`TownHallCreateScreen`/`TownHallScreen` 扫描残留为多行 `I18n.name` 误报，无需改；`TouristSpotManager` 唯一中文在 `Log.warn`（§1.1 排除），跳过

## 6.2 Batch 4 状态（已完成，未提交）

- [x] `TouristSimulation`（5 处 whatHappened → `message.wandscape.tourist.what_*`，客户端解析；ATM「取钱 N」改无金额「取钱」，金额仍见钱包栏）
- [x] `TouristSimSystem`/`TouristMoveGoal`（建筑名 fallback `建筑`→`unknown`）
- [x] `AmbientTextPools`（bubble 键已迁移，补空 typeId → `building.wandscape.unknown` 解析）
- [x] `ColonyCreateRequestPacket`（2 处，`message.wandscape.colony.*`）
- [x] 已验证既有迁移键齐全：`AmbientTextPools`（206 bubble 键）、`CharacterNames`（44 名字键）、`WandscapeNpc`（19 state 键）、`TouristState`（5 state 键）
- [x] 语言文件合并（en/zh 各 +8）+ JSON 校验 + 编译 + tourist 测试
- [ ] 备注：`ReviveHandler`/`MagicSpellExecutors`/`TouristSpawnSystem`/`TouristSimSystem` 残留均为日志（§1.1 排除）；`NarrativeTemplates`（13）无显示消费方、数据驱动中文模板，待有显示时整体设计；`ColonyLevelData`（殖民地名 fallback）显示在 TownHallScreen，随市政厅屏幕处理

## 6.3 Batch 3 状态（已完成，未提交）

- [x] `CreativeScannerScreen`（83 处 → 68 键，`gui.wandscape.scanner.*`；多行 I18n fallback 已压单行避免 §3.1 逐行扫描误报）
- [x] `ScannerScreen`（35 处，复用 `gui.wandscape.scanner.*` 同串键 + `title_survival`/`building_id_header`/`export_fixed_custom_header`/`category_locked_custom` 4 新键）
- [x] `ScannerExportPacket`（3 处，`message.wandscape.scanner.export_*`；服务端 `sendSystemMessage` 走 translatable 组件、客户端解析；`自定义道路`默认显示名复用 `gui.wandscape.scanner.custom_road`）
- [x] `ScannerValuePacket`（7 处，`message.wandscape.scanner.value_*`；`StringBuilder` 拼接改为 `MutableComponent` append，逐片段客户端解析）
- [x] `AnomalyScreen`（12 处 → 13 键，`gui.wandscape.anomaly.*`）
- [x] `WandscapePanelOverlay`（5 处，`gui.wandscape.panel.*`；stats/panel.day/npc_count 为既有键不动）
- [x] 语言文件合并（en/zh 各 +95：scanner 68 / anomaly 13 / panel 5 / message.scanner 9）+ JSON 校验 + 编译 + 测试

## 7. 风险

| 风险 | 缓解 |
|---|---|
| ImGui `##ID` 被改导致控件状态错乱 | §2.2 规则 + 逐处核对 |
| 占位符顺序错 | `I18n.name(key, fallback, args...)` 严格对序；英文值保留 %s/%d |
| 键跨批冲突 | §2.4 每批独立前缀 |
| 误翻日志/注释 | §3.1 残留扫描 + 人工核对 player-facing |
| 英文文本超长导致 UI 挤压 | 保留 wrap/宽度逻辑；scan 后 runClient 目测 |
