# UI 残余代码清理报告

> **状态：第一批 + 第二批已完成 (2026-07-29)**。第三批（组件精灵替换）待后续评估。

## 已完成清理

## 可安全删除（共 12 项）

### MedievalColors.java — 3 个未使用常量

| 常量 | 行号 | 值 | 引用数 |
|------|------|-----|--------|
| `GOLD_HIGHLIGHT` | 22 | `0xFFD4AAFF` | 0 |
| `PURPLE_LIGHT` | 27 | `0xFF8B50C0` | 0 |
| `BUTTON_BG` | 44 | `0xFF2D1050` | 0 |

### SkinSprite.java — 3 个未使用精灵定义

| 精灵 | 行号 | 关联纹理文件 |
|------|------|-------------|
| `BUTTON_B` + `BTN_B_VARIANTS` | 39, 69-73 | `textures/gui/skin/button_b.png` |
| `TAB_D` | 41 | `textures/gui/skin/tab_d.png` |
| `BAR_B` + `BAR_B_SPRITE` + sheet 常量 | 44, 116-118 | `textures/gui/skin/bar_b.png` |

### SkinRender.java — 3 个死方法

| 方法 | 行号 | 说明 |
|------|------|------|
| `drawTabLeft()` | 98-101 | 从未被外部调用 |
| `drawTabCenter()` | 103-106 | 从未被外部调用 |
| `drawTabRight()` | 108-111 | 从未被外部调用 |

### MedievalScreen.java — 3 段死代码

| 代码 | 行号 | 说明 |
|------|------|------|
| `case FULL -> ...` 分支 | renderBackground() | 无任何子类调用 `setDecorationLevel(FULL)` |
| `if (decorationLevel == FULL)` 标题栏分支 | render() | 同上 |
| `case NONE ->` 分支 | renderBackground() | 无任何子类使用 NONE |

---

## 需要谨慎处理

### 仍在使用但可考虑后续替换的精灵组件

| 组件 | 精灵调用 | 说明 |
|------|---------|------|
| `TaskQueuePanel` | `SkinSprite.PANEL_B` (9-slice) | 内嵌面板背景，可改为代码绘制 |
| `ProgressIndicator` | `SkinRender.drawBar()` (BAR_A) | 进度条背景，可改为纯代码绘制 |
| `MedievalButton` | `SkinRender.drawButton()` (BUTTON_A) | 按钮精灵，改动影响面大 |

### 孤儿纹理文件（Java 代码中无引用）

| 文件 | 说明 |
|------|------|
| `textures/gui/skin/panel_9slice_c.png` | SkinSprite 中无 PANEL_C 定义 |
| `textures/gui/skin/button_b.png` | BUTTON_B 无引用 |
| `textures/gui/skin/tab_d.png` | TAB_D 无引用 |
| `textures/gui/skin/bar_b.png` | BAR_B 无引用 |

### 仅被 DemoScreen 引用的

| 资源 | 说明 |
|------|------|
| `SkinSprite.PANEL_A` + `panel_9slice_a.png` | DemoScreen FULL 面板演示用 |
| `MedievalColors.PARCHMENT_BG` | 仅 DemoScreen 引用 |

---

## 无需处理的

### WandscapeTheme 在覆盖层中的使用（正常）
- `WandscapePanelOverlay` — V 面板 HUD
- `BuildingSelectionOverlay` — BUILD 标签页
- `RoadPlacementOverlay` — ROAD 标签页
- `BuildingDebugOverlay` — 调试覆盖层

### Parchment 色系常量（仍被组件使用）
- `PARCHMENT_DEEPEST` — WarehouseScreen 格子、NpcScreen 装备栏、RenderUtil
- `PARCHMENT_DARK` — RenderUtil、IconButton
- `PARCHMENT_MID` — RenderUtil
- `PARCHMENT_LIGHT` — ScrollableList 行悬停

命名虽来自旧 FULL 主题，但色值本身仍在 MINIMAL 中正常使用。后续可考虑重命名但不急。

---

## 建议清理顺序

**第一批（无风险，直接删）：**
1. 删除 MedievalColors 中 3 个未使用常量
2. 删除 SkinSprite 中 3 个未使用精灵定义 + 常量
3. 删除 SkinRender 中 3 个死方法
4. 删除 4 个孤儿纹理文件

**第二批（删死代码）：**
5. 删除 MedievalScreen 中 FULL/NONE 死分支
6. 删除 `setDecorationLevel()` 方法（无调用者）
7. 删除 `DecorationLevel` 枚举中的 FULL/NONE 值 → 或直接删掉枚举，只保留 MINIMAL 行为

**第三批（需验证后替换）：**
8. TaskQueuePanel 精灵面板 → 改用代码绘制
9. ProgressIndicator 精灵进度条 → 改用纯代码绘制

> ✅ SearchBar 组件已删除 (2026-08-04)：Workstation/CraftingStation 搜索框改用 `EditBox + drawInsetField`（仓库样式），原 item 8 完成。

---

## 清理收益预估

| 类别 | 删除量 |
|------|--------|
| Java 代码行数 | ~60 行（常量 + 死方法 + 死分支） |
| 纹理文件 | 4 个 PNG |
| SkinSprite 代码 | ~50 行（精灵定义 + sheet 常量） |
| 如果做完第三批 | 额外约 4 个 PNG + SkinRender 方法精简 |
