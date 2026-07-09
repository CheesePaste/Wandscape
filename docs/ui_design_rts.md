# Wandscape UI 重构设计文档 (RTS HUD 风格)

## 1. 设计理念 (Design Philosophy)

目前的 Wandscape UI 存在体积过大、遮挡视野以及过度依赖 Vanilla 厚重风格的问题。基于提供的参考图（`UI.png`, `pop.png`, `pop2.png`），我们将对 V 面板（WandscapePanel）及其子模式进行彻底的“现代化 RTS 化”改造。

**三大核心原则：**
1. **纯代码绘制 (Code-Driven Box)**：抛弃所有原版 `demo_background` 和 `button` 贴图。所有背景一律使用高透明度暗色矩形 + 1像素锐利边框。
2. **边缘悬浮 (Floating Edges)**：把中央视野完全还给玩家。UI 作为轻量级的 HUD 悬浮在屏幕的边缘或下方，而不是横跨整个屏幕两端的实心黑条。
3. **图标优先，文字辅助 (Icon-First)**：采用高分辨率纯白单色 PNG 矢量图标库，配合代码动态着色。按钮默认只显示图标，具体信息通过小字号的辅栏或 Hover Tooltip 展示（如 `pop2.png` 底部的小绿字）。

---

## 2. 颜色与视觉规范 (Theme Palette)

- **主背景 (Background)**: `0xCC111214` (深灰蓝色，80% 不透明度)
- **1像素边框 (Border)**: `0xFF3A3E4A` (暗亮灰色)
- **高亮/选中态边框 (Active/Accent)**: `0xFF78A563` (参考 `pop2.png` 中的清爽浅绿色) 或 `0xFF4FA0FF` (淡蓝色)。
- **普通文本 (Text)**: `0xFFE0E0E0` (柔和白，避免纯白刺眼)
- **辅助/置灰文本 (Dim Text)**: `0xFF888888`
- **悬停遮罩 (Hover Overlay)**: `0x33FFFFFF` (鼠标放上去时的微微亮起)

---

## 3. WandscapePanel (V面板) 整体布局设计

当玩家按下 V 键时，不再出现上下两条宽大黑条，而是出现以下悬浮 HUD 组件：

### 3.1 顶部殖民地信息条 (Colony Info Widget)
- **位置**：屏幕左上角 (X: 10, Y: 10)
- **形态**：一个小巧的水平胶囊状半透明黑盒。
- **内容**：
  - `[城堡图标] 殖民地名称 - Lv.X`
  - 右侧紧跟三个数据：`[叶子图标] 10  [星星图标] 5  [皇冠图标] 2` (分别代表 Comfort, Magic, Wonder，带有各自的主题色)。
- **交互**：纯展示，无交互。相比现在横跨顶部，它只占用左上角极小的一块区域。

### 3.2 底部主控工具栏 (Main Command Bar)
- **位置**：屏幕正下方，原版快捷栏的上方 (大约 `Y = screenHeight - 65`)，水平居中。
- **形态**：参考 `pop2.png`，一个包含四个方形图标按钮的横向列表。
  - 按钮尺寸：`24x24`
  - 按钮间距：`4px`
- **四个模式 (Tabs)**：
  1. `[锤子图标]` (Build - 建筑投影)
  2. `[铲子/路径图标]` (Road - 道路投影)
  3. `[图纸图标]` (Editor - 蓝图编辑器)
  4. `[柱状图图标]` (Stats - 数据统计)
- **状态栏 (Status Bar)**：在图标按钮栏的正下方，有一行极其小巧的文字（参考 `pop2.png` 下方的绿字），显示当前选中的模式名称及提示。
  - 示例：`Mode: Build Projection   LMB: Place   RMB: Cancel`

### 3.3 次级内容区设计 (Sub-mode Context Areas)

根据底部选中的不同 Tab，在主控栏的上方展开不同的次级面板。

#### A. 建筑选择栏 (Building Selection - 当选中 Build Tab 时)
- 现在的实现可能非常庞大，我们将其改造为参考图 `pop.png` (RTS Storage) 的样式。
- **形态**：在底部主控栏上方，贴着主控栏弹出一个细长的水平面板。
- **左侧**：类别选择 (Category)，只占很窄的一列，显示诸如 "All", "Storage", "Services"。
- **右侧**：极度紧凑的物品槽 (Compact Slots)。背景是深色方块 + 细边框，里面直接调用 `GuiGraphics.renderItem()` 渲染缩小版的建筑方块图标。选中时边框变绿。

#### B. 数据统计面板 (Stats Panel - 当选中 Stats Tab 时)
- 不再在屏幕正中央粗暴渲染文字，而是在屏幕左侧或右侧弹出一个带透明背景的独立信息面板 (Info Window)。
- 采用双栏或极简表格式排版，清晰列出 Maintenance / Tourists / Elements Consumed 数据。

---

## 4. 实施计划 (Implementation Steps)

为了平滑过渡，我们将按以下步骤重构：

**Step 1: 建立基础 UI 主题库 (`shared/ui/theme/WandscapeTheme.java`)**
- 编写一套专门替代原版绘制的静态工具类。
- 包含方法：`drawRtsBox()`, `drawRtsSlot()`, `drawIcon()`, `drawIconButton()` 等。

**Step 2: 准备图标资产 (Icons)**
- 收集/绘制 4 个主 Tab 的单色白色 PNG 图标（24x24 或 32x32）。
- 收集殖民地三值的单色小图标。
- 存入 `assets/wandscape/textures/gui/icons/`。

**Step 3: 重构 `WandscapePanelOverlay.java`**
- 彻底删除当前的 `TOP_BAR_H` 和 `BOTTOM_BAR_H` 整屏黑条填充逻辑。
- 引入新的 HUD 布局参数，使用 `WandscapeTheme` 绘制左上角 Colony Info 和底部居中图标工具栏。

**Step 4: 重构次级面板 (如 `BuildingSelectionOverlay`)**
- 继承 `WandscapeTheme` 的风格，将列表渲染改为 RTS Storage 风格。
- 更新悬停高亮和选中边框逻辑。
