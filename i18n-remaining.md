# 剩余「玩家可见」i18n 硬编码 — 交接单

> **本单已清空（2026-09-24）**：A1/A2/A3 与 B1/B3/B4 全做完；B2 在写下这份单子时就已完成
> （5 个道路包早已走 `I18n.name`，只是单子没更新）。§0 的机制与硬规矩已并入 `CLAUDE.md`
> 硬规则 11（`lang_src/` 单一源 + Java 兜底串只写 `%s`），本文件自此只是历史记录，可以删。
>
> 范围：只列**玩家能看见**的未翻译文案。指令回执（`content/command/**`）、日志、
> 配置注释一律不在本单内（见 §C）。
> 本轮已修：法师小屋、共享 `MedievalScreen`、道路工坊面板与预设名、
> `ColonyOwnership.deny` 越权提示、§A 的 12 个缺失界面/分类键、§A 的 206 条气泡英文改写。

## 0. 机制与三条硬规矩

**查表方式**：`com.wsteam.wandscape.foundation.ui.I18n`

```java
I18n.name("gui.wandscape.<域>.<键>", "中文兜底")            // 返回 Component
I18n.name("...", "等级 Lv.%s", argA, argB)                  // 带占位符
I18n.string("...", "中文兜底", args)                        // 返回 String（画布直绘用）
```

引擎是 `Component.translatableWithFallback(key, fallback, args)`：**键在就吃 lang，
键不在就原样显示 fallback**。所以「硬编码」的判定不是"源码里有没有中文"，而是
**这个中文兜底对应的键在不在 lang 里**。

**lang 文件**：`src/main/resources/assets/wandscape/lang/{en_us,zh_cn}.json`
- 两份键集必须完全一致，严格按字母序，2 空格缩进，末尾无逗号。
- `§` 颜色码直接写在值里（如 `"§aRoad withdrawn"`）。
- 当前 2331 键。

**规矩 1 — 占位符只写 `%s`。** lang 值里的 `%d` 会被 MC 加载时归一化，而 **Java 兜底串不会**；
在 Java 兜底里写 `%d` 会直接显示字面量。一律用 `%s`（`%1$s` 这类位置参数同理）。

**规矩 2 — 长度，装不下就别硬译。** 很多文案画在定宽框里，英文比中文宽约 40%。
- 实测换算：**CJK ≈ 9px/字，ASCII ≈ 6px/字符**。
- 已知定宽区（GUI 单位）：
  | 区域 | 可用宽 |
  | --- | --- |
  | 法师小屋空态说明（居中） | ≈ 186px（≈ 31 ASCII 字符） |
  | 法师小屋指派页右栏 | ≈ 134px（≈ 22 ASCII 字符） |
  | 道路工坊面板 | 屏宽 × 0.32，最小 × 0.22（≈ 190 ~ 300px） |
- **头顶气泡（§A）**：气泡框按文本自动撑开，长英文会糊住半个屏幕。**渲染侧已兜底**：
  `SpeechBubbleRenderer` 按词换行（上限 120 GUI 单位 ≈ 20 ASCII 字符/行，中文现有文案不触发行上限），
  所以文案层面只需**尽量短**（单行目标 ≤ 16 个 ASCII 字符），超了只是折成两行；
  **不要再靠删气泡池条目来压长度**——删一条等于中英玩家同时少一条内容。

**规矩 3 — 别把兜底串当硬编码。** 这类代码是**正确**的，不要动：

```java
SettingTab.SETTLEMENT("gui.wandscape.settings.tab.settlement", "本镇")   // 键已存在
new CategoryDef("node", "category.wandscape.node", "采集节点")             // 键已存在
private static String title(String key, String fallback) {                 // 统一加前缀
    return I18n.string("gui.wandscape.settings." + key, fallback);
}
```

## 1. 已核对：以下文件**不用动**（避免白干）

| 文件 | 看似硬编码 | 实情 |
| --- | --- | --- |
| `foundation/ui/settings/SettingsRegistry.java` | 37 处 | 全走 `title(key, fallback)` → `gui.wandscape.settings.<key>`；已核对 **33 个键全部存在** |
| `foundation/ui/settings/SettingTab.java` | 6 处 | 枚举里带 i18nKey，键已存在 |
| `content/tourist/internal/TouristState.java` | 5 处 | `tourist.wandscape.state.*` 5 个键已存在 |
| `content/npc/entity/WandscapeNpc.java` 状态串 | 19 处 | `npc.wandscape.state.*` 19 个键已存在 |
| `CreativeScannerScreen` 的 `ElementDef` 默认串 | 7 处 | `element.wandscape.*` 已存在 |
| `content/building/client/MageHutScreen.java` 属性名兜底 | 7 处 | `gui.wandscape.mage_hut.attr_*` 已存在 |
| `api/**` 里的「重设计阶段——待接入 …」 | ~40 处 | 是 Javadoc/TODO 注释，非上屏文案 |
| `Config.java` / `ClientConfig.java` 的 `.comment()` | 78 处 | **故意中文**：CLAUDE.md 硬规则 10 要求 config 注释中英双语，禁止只写单语 |

---

## A. 头顶气泡 —— 已完成（留档备查）

**先纠正原单的一处误判**：气泡键**早在 v1.10.23a（`ab279fb0`）就已全部进 lang**，206 条
（游客 generic 90 / building 55 / idle 8，法师 53），中英俱全、`{building}` 已转 `%s`，
并非"缺 241 条"。本轮修的是**长度**，两处：

1. **渲染器按词换行**（`foundation/ui/bubble/SpeechBubbleRenderer.java`）
   原先 `bubbleW = font.width(text) + padding` —— 文本多宽气泡多宽，46 字符的英文气泡
   能横跨近 12 格。现改为 `font.split(text, MAX_TEXT_WIDTH=120)` 取行、宽度取最长行、
   高度按行数、逐行居中：任何语言、任何长度（含长建筑名）都不再撑爆视野。
   中文现有气泡（≤12 字 ≈ 108px）不触发行上限，视觉零变化。
2. **英文改写变短**：206 条英文全部重写为短句，超 16 字符的 **121 → 32 条**
   （32 条里 27 条带 `%s`，最长 21 字符，仍在单行内）。中文值未动，仍是源码兜底原文。

判据：**气泡长度问题在渲染侧解决，别在文案侧砍内容。**

## B. 需要改代码 + 补键（玩家可见）

### B1. `CreativeScannerScreen`（创造模式扫描器，28 处）

`§` 开头的是点击后弹在面板上的即时反馈（`showFeedback`），其余是面板固定标签。
注意 229 / 322 / 353 / 389 这几处是**字符串拼接**出来的，要拆成整句键带占位符，
别把中文片段留在 Java 里：

| 行 | 现状 | 建议键 |
| --- | --- | --- |
| 192 | `§e已切换到 SAVE 主扫描模式` | `message.wandscape.scanner.…` |
| 218 | `§e已切换到 CORNER 辅角点模式` | `message.wandscape.scanner.…` |
| 229 | `§e已切换为 ` | `message.wandscape.scanner.…` |
| 229 | `建筑模式` | `message.wandscape.scanner.…` |
| 229 | `道路模式` | `message.wandscape.scanner.…` |
| 248 | `§a匹配成功！已更新 3D 边界 (%d×%d×%d)` | `message.wandscape.scanner.…` |
| 249 | `§a已匹配角点并更新边界！尺寸: %d×%d×%d` | `message.wandscape.scanner.…` |
| 251 | `§c未找到同名暗号的 CORNER 扫描器 (需在 64 格内)` | `message.wandscape.scanner.…` |
| 252 | `§e未找到同名暗号的 CORNER 扫描器。请确认暗号是否一致。` | `message.wandscape.scanner.…` |
| 291 | `§6已清除全部门偏移记录 (默认走外围入口)` | `message.wandscape.scanner.…` |
| 292 | `§7已清除全部门偏移记录。` | `message.wandscape.scanner.…` |
| 310 | `自定义道路` | `gui.wandscape.scanner.…` |
| 322 | `§e已切换为建筑模式` | `message.wandscape.scanner.…` |
| 335 | `自定义建筑` | `gui.wandscape.scanner.…` |
| 353 | `§e已切换为道路模式` | `message.wandscape.scanner.…` |
| 389 | `§e已切换为道路模式` | `message.wandscape.scanner.…` |
| 445 | `§a已上架新商品行 (请编辑物品ID)` | `message.wandscape.scanner.…` |
| 467 | `§6已移除该商品条目` | `message.wandscape.scanner.…` |
| 509 | `§a已添加元素产出` | `message.wandscape.scanner.…` |
| 512 | `§e所有 7 种元素已全部添加完毕` | `message.wandscape.scanner.…` |
| 538 | `§6已移除该元素产出` | `message.wandscape.scanner.…` |
| 561 | `§e产出元素切换为: ` | `message.wandscape.scanner.…` |
| 573 | `§e采集元素切换为: ` | `message.wandscape.scanner.…` |
| 962 | `道路 (ROAD)` | `gui.wandscape.scanner.…` |
| 962 | `建筑 (BUILDING)` | `gui.wandscape.scanner.…` |
| 963 | `自定义 (custom)` | `gui.wandscape.scanner.…` |
| 966 | `未命名ID` | `gui.wandscape.scanner.…` |
| 1199 | `§e目标包: ` | `message.wandscape.scanner.…` |

（第 66-81 行的分类名与第 90-96 行的元素名是**已存在的兜底串**，见 §1，不要在这里重复改。）

### B2. 道路网络包回执（约 7 处）

| 文件 | 行 | 现状 | 建议键 |
| --- | --- | --- | --- |
| `content/road/network/RoadWithdrawPacket.java` | 71 | `§a已撤回道路` / `§c无法撤回该道路` | `message.wandscape.road.withdraw.success` / `.failed` |
| `content/road/network/RoadPlacePacket.java` | 71 | `§c[魔法小镇] 请先创建小镇（先放置市政厅并命名）` | 已有 `message.wandscape.road.place_failed_no_colony`，直接改用 |
| `content/road/network/FillBoxPacket.java` | 65 | 同上 | 同上 |
| `content/road/network/DestroyFillPacket.java` | 72 | 同上 | 同上 |
| `content/road/network/SplineBuildPacket.java` | 46 | 同上 | 同上 |

> `RoadWithdrawPacket` 的两条已有对应键（`message.wandscape.road.withdraw.*`，见 lang 1467/1468 行），
> 代码却还在写死中文 —— 属于漏接，改成 `I18n.name(...)` 即可。

### B3. NPC 头顶的「动态状态」前缀（`WandscapeNpc.statusFallback` 默认分支）

`content/npc/entity/WandscapeNpc.java` 的 `statusFallback` 里，19 个固定状态键都已覆盖，
但默认分支是**现拼中文**、任何语言下都是中文：

```java
if (statusKey.startsWith("op:"))     yield "执行: " + statusKey.substring(3);
if (statusKey.startsWith("ritual:")) yield "施法: " + statusKey.substring(7);
if (statusKey.startsWith("task:"))   yield statusKey.substring(5);
```

要修得让 `WandscapeNpcRenderer`（第 146 行）先按前缀查键、再把 payload 当 `%s` 参数传：

```
npc.wandscape.state.op      = "Executing: %s"   / "执行: %s"
npc.wandscape.state.ritual  = "Casting: %s"     / "施法: %s"
npc.wandscape.state.task    = "%s"              / "%s"
```

> `task:` 的 payload 本身是任务名（来自 B4 的 `BlueprintDefaults` / `TaskPanelSyncTracker`），
> 那两处补完键之后这里才会真正出英文。

### B4. 其余上屏点

| 文件 | 行 | 现状 | 备注 |
| --- | --- | --- | --- |
| `content/task/network/TaskPanelSyncTracker.java` | 305 / 308 / 342-348 / 451 / 496 / 544 | `任务`、`未知任务 #`、`地元素`…`暗元素`、`正在执行中`、`自动化供应链补料`、`工坊手动排队`、`未知生产项` | 任务管理面板正文 |
| `content/task/engine/dsl/BlueprintDefaults.java` | 245/252/259/266/279 | `节点采集`、`制作物品`、`制作魔法卷轴`、`分解物品`、`合成物品` | 任务序列名，随任务面板显示 |
| `content/building/internal/BuildingApiImpl.java` | 909 | `该位置与已有建筑方块重叠，不能占用同一格` | 建筑放置失败反馈 |
| `foundation/ui/guidebook/GuidePages.java` | 123 | `\n- [返回目录](` | 手册页脚链接，玩家可见 |
| `content/building/scanner/client/gizmo/ScannerGizmoState.java` | 22/23 | `Min 最小角点`、`Max 最大角点` | 3D gizmo 手柄标签 |
| `content/building/scanner/InteractSpotMarkerBlock.java` | 113-116 | `北`/`东`/`南`/`西` | 交互点朝向标签 |
| `compat/jei/ElementRecipeCategory.java` | 199 | `解析物品 <x> 失败，跳过该槽位` | JEI 里少见的报错路径，低优先 |

---

## C. 明确不做

| 类别 | 位置 | 原因 |
| --- | --- | --- |
| 指令回执 | `content/command/**`（约 155 处） | 按指示本轮不做 |
| 日志 | 各文件 `Log.warn/info/debug(...)` | 不上屏，且是排障用的稳定串 |
| 配置注释 | `Config.java` / `ClientConfig.java` 的 `.comment()` | **故意中文**，CLAUDE.md 硬规则 10 |
| API TODO | `api/**` 的「重设计阶段——待接入 …」 | Javadoc，非上屏 |
| 名称池 | `foundation/util/CharacterNames.java`（144 条） | 小镇命名风格的**内容**（中文名池），不是待翻译 UI 串；要改属玩法决策 |
| 叙事模板 | `content/tourist/internal/NarrativeTemplates.java`（13 条） | 同上，属内容 |
| 指南加载日志 | `GuideManifest.java` / `GuideImagePreviewScreen.java` | 是 `Log.*`，不是手册正文 |

> 指令回执里有 4 个键 lang 同样没写（`message.wandscape.command.magic_cd_cleared` /
> `magic_freecast_status` / `magic_freecast_toggled` / `magic_mana_filled`），将来做指令回执时一并补。

---

## D. 收尾

1. 两份 lang 必须同时改、键集完全一致、保持字母序。
2. 改完跑 `./gradlew build`（唯一门槛）。
3. 自检脚本（本机已有，`tools/` 已 gitignore）：
   - `python tools/i18n_audit.py <文件.java>` —— 列某个文件里没走进 `I18n` 的中文串
   - `python tools/i18n_sweep.py` —— 全项目扫描 + 列出「代码引用了但 lang 没有」的键
   两个脚本都会把 `I18n.name(key, "兜底")` 的兜底串误报成硬编码，读到先看§规矩 3。
