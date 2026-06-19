# UI 组件库

文档编号：NEW-23
版本：1.1
状态：中世纪魔法主题 UI 组件库 — 全模组复用 + 6 个新按钮 + 游戏内位置编辑器
依赖：01-shared-api

---

## 一、职责边界

- 提供一套统一的、中世纪魔法风格的 UI 组件
- 所有组件通过纯代码渲染（`GuiGraphics.fill/gradient/drawString`），不依赖自定义纹理
- 组件与业务逻辑解耦，纯渲染原语
- 预留动画扩展接口（暂不实现具体动画）

**不包含：**
- 具体业务 GUI（仓库、工作站等在各模块中实现，继承本库基类）
- 自定义纹理（除 9 个元素图标外）
- Menu/Slot 逻辑（由 `AbstractContainerScreen` 子类处理）

---

## 二、包结构

```
src/main/java/com/wsteam/wandscape/shared/ui/
├── theme/
│   ├── MedievalColors.java        // 色板常量
│   └── MedievalTexture.java       // 程序化纹理工具（渐变、边框、装饰）
├── component/
│   ├── MedievalScreen.java        // 基础 Screen（羊皮纸背景 + 金边）
│   ├── MedievalButton.java        // 主题按钮（紫底金边）
│   ├── MedievalSmallButton.java   // 小号按钮（关闭/最小化）
│   ├── TabBar.java                // 页签栏（水平排列）
│   ├── ScrollableList.java        // 虚拟滚动列表
│   ├── ElementPanel.java          // 9 元素储量显示
│   ├── SearchBar.java             // 搜索输入框
│   ├── QuantitySlider.java        // 数量滑条（1~max）
│   ├── ProgressIndicator.java     // 进度条（金填充）
│   ├── ItemGrid.java              // 物品网格 + 虚拟滚动
│   ├── TooltipHelper.java         // 中世纪风格 tooltip 辅助
│   ├── IconButton.java            // 图标按钮
│   ├── LessButton.java            // 减少(-)按钮
│   ├── MoreButton.java            // 增加(+)按钮
│   ├── LeftArrowButton.java       // 左箭头按钮
│   ├── RightArrowButton.java      // 右箭头按钮
│   ├── HelpButton.java            // 帮助按钮
│   ├── OptionButton.java          // 选项按钮
│   └── DemoScreen.java            // 组件展示 Demo
├── animation/
│   └── MedievalAnimation.java     // 动画接口（预留）
├── editor/
│   ├── UIEditorScreen.java        // 游戏内 UI 位置编辑器
│   ├── WidgetLayout.java          // 布局数据模型 + JSON 序列化
│   └── UILayoutManager.java       // 布局持久化
└── util/
    └── RenderUtil.java            // drawGradientBorder, drawPanelBg 等工具方法
```

---

## 三、美工策略

### 3.1 零纹理渲染

所有视觉效果通过 `GuiGraphics` API 程序化生成：

| 效果 | 实现方式 |
|------|---------|
| 羊皮纸背景 | 四向渐变 `fillGradient()`（深棕→黑） |
| 边框 | `fill()` 画矩形线框，外暗金 + 内亮金 |
| 按钮 | `fill()` 紫底 + `renderOutline()` 金边 |
| 进度条 | 暗色底 + 金色 `fill()` 从左填充 |
| 滚动条 | 暗色轨道 + 金色细条滑块 |
| 分隔线 | `hLine()` 金色单像素线 |

### 3.2 精灵图纹理（CC0: Tiny RPG - Mana Soul GUI）

按钮、面板、页签、箭头等 UI 元素使用精灵图渲染。纹理文件位于 `assets/wandscape/textures/gui/skin/`（20 个 PNG），来自 CC0 素材包。

### 3.3 元素图标（9 个 16×16）

位置：`assets/wandscape/textures/gui/element/{id}.png`

每个图标：16×16 像素，彩色圆形底 + 元素英文首字母。

| 文件 | 元素 | 颜色 |
|------|------|------|
| `earth.png` | 土 | 棕色 #8B6914 |
| `wood.png` | 木 | 绿色 #2E8B57 |
| `water.png` | 水 | 蓝色 #4A90D9 |
| `fire.png` | 火 | 红色 #B22222 |
| `iron.png` | 铁 | 灰色 #808080 |
| `wind.png` | 风 | 淡蓝 #87CEEB |
| `gold.png` | 金 | 金色 #FFD700 |
| `diamond.png` | 钻石 | 青色 #00CED1 |
| `ender.png` | 末影 | 紫色 #4B0082 |

---

## 四、色板

```
羊皮纸层次:
  PARCHMENT_DEEPEST = 0xFF1A0E04  最暗边缘
  PARCHMENT_BG       = 0xFF2A1A0A 背景主色
  PARCHMENT_MID      = 0xFF3D2A14 中间调
  PARCHMENT_LIGHT    = 0xFF4D3A20 亮部

金色系:
  BORDER_GOLD_DARK   = 0xFF8B6914 外边框暗金
  BORDER_GOLD        = 0xFFB8960F 标准金边
  ACCENT_GOLD        = 0xFFFFD700 强调金

紫色系:
  PURPLE_BG          = 0xFF2D1050 面板紫底
  PURPLE_BORDER      = 0xFF6B30A0 紫边框
  PURPLE_LIGHT       = 0xFF8B50C0 亮紫

文字:
  TEXT_WARM_WHITE    = 0xFFFFF8DC 主文字
  TEXT_MUTED         = 0xFF9A8A6A 次要文字
  TEXT_DIM           = 0xFF5A4A3A 禁用文字

功能色:
  DANGER_RED         = 0xFF8B0000 警告
  SUCCESS_GREEN      = 0xFF2E8B57 正常
  INFO_BLUE          = 0xFF4A90D9 信息
```

---

## 五、组件清单

### 5.1 MedievalScreen

所有 mod 内 Screen 的基类。

- 继承 `net.minecraft.client.gui.screens.Screen`
- 居中面板区域：`leftPos`, `topPos`, `panelWidth`, `panelHeight`
- 背景：羊皮纸四向渐变 + 双层金边 + 四角装饰块
- 动画钩子：`animations` 列表 + `addAnimation()`
- `isPauseScreen() = false`

### 5.2 MedievalButton

- 继承 `AbstractButton`
- 紫底 + 金边 + 居中金色文字
- hover：半透明白色叠加提亮
- disabled：整体变灰

### 5.3 TabBar

- 继承 `AbstractWidget`
- 构造函数传入 `List<Tab>`（label + 可选 icon）
- 选中态：底部 2px 金线 + 金色文字
- 切换回调：`Consumer<Integer>` 传入 tab index

### 5.4 ScrollableList

- 继承 `AbstractWidget`
- 泛型 `T`，抽象方法 `renderRow(GuiGraphics, T item, int x, int y, int index, boolean selected, boolean hovered)`
- 虚拟滚动：只渲染可见行
- 右侧金色滚动条
- 鼠标滚轮、点击选中

### 5.5 ElementPanel

- 继承 `AbstractWidget`
- 显示 9 个元素的图标(16×16) + 名称 + 数值
- 数值格式化：\<1000 直接，≥1000 K，≥1M M
- 按层级着色元素名
- 储量不足时数值闪烁变红（动画钩子）

### 5.6 SearchBar

- 继承 `AbstractWidget`
- 内部包裹 `EditBox`（`setBordered(false)`）
- 自定义深棕色圆角背景 + 金边
- 左侧搜索提示符

### 5.7 QuantitySlider

- 继承 `AbstractWidget`
- 范围 [1, max]，值显示在滑条上方
- 暗色轨道 + 金色滑块
- 支持鼠标拖动和键盘左右箭头

### 5.8 ProgressIndicator

- 继承 `AbstractWidget`
- 0.0 ~ 1.0 进度
- 暗色底 + 金色从左到右填充
- 可选文字 "60%" 或 "3s"

### 5.9 ItemGrid

- 继承 `AbstractWidget`
- 固定列数 × 虚拟滚动行数
- 每格：ItemStack 渲染 + 数量角标
- 点击选中 + tooltip

### 5.10 IconButton

- 继承 `AbstractButton`
- 小型图标按钮（14×14 或 16×16）
- 用途：关闭[X]、最小化[_]、设置齿轮等

### 5.11 LessButton / MoreButton

- 继承 `AbstractButton`
- 基于 `less_button.png` / `more_button.png` 精灵图（22×24，4 态）
- 用途：数量调整、翻页等增减操作
- 支持原生尺寸和自定义缩放两种构造

### 5.12 LeftArrowButton / RightArrowButton

- 继承 `AbstractButton`
- 基于 `left_arrow.png` / `right_arrow.png` 精灵图（20×14，3 态：normal/hover/disabled）
- 用途：页签切换、列表翻页等方向操作

### 5.13 HelpButton

- 继承 `AbstractButton`
- 基于 `help_button.png` 精灵图（30×32，4 态）
- 用途：打开帮助/提示信息

### 5.14 OptionButton

- 继承 `AbstractButton`
- 基于 `options_button.png` 精灵图（30×32，4 态）
- 用途：打开设置/选项菜单

### 5.15 MedievalAnimation（预留）

- 接口：`isComplete()`, `tick()`, `render(GuiGraphics, ...)`
- 存储于 `MedievalScreen.animations`
- 每帧：清理已完成 + 渲染活跃动画
- 暂不提供具体实现

---

## 六、各模块 UI 使用矩阵

| 组件 | 仓库 | 工作站 | 制作站 | 魔药站 | 酒馆 | 管理面板 |
|------|:---:|:-----:|:-----:|:-----:|:----:|:-------:|
| MedievalScreen | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| MedievalButton | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| TabBar | | ✓ | | | | ✓ |
| ScrollableList | | | | | | ✓ |
| ElementPanel | ✓ | ✓ | ✓ | ✓ | | ✓ |
| SearchBar | ✓ | | ✓ | | | |
| QuantitySlider | ✓ | ✓ | ✓ | ✓ | | |
| ProgressIndicator | | ✓ | ✓ | ✓ | | |
| ItemGrid | ✓ | | | | | |
| IconButton | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| LessButton | ✓ | ✓ | ✓ | ✓ | | |
| MoreButton | ✓ | ✓ | ✓ | ✓ | | |
| LeftArrowButton | | ✓ | | | | ✓ |
| RightArrowButton | | ✓ | | | | ✓ |
| HelpButton | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| OptionButton | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

---

## 七、后续阶段扩展预留

| 预留点 | 用途 | 目标阶段 |
|--------|------|---------|
| `MedievalAnimation` | 粒子飘浮、书页翻动、魔力流动光效 | 4-5 |
| `TabBar` icon 字段 | 页签带图标（建筑图标、NPC 头像等） | 5 |
| `ElementPanel` 闪烁动画 | 资源不足警告 | 4 |
| `ItemGrid` slot 拖拽 | 仓库物品拖拽取出 | 3 |
| minimap 渲染扩展 | 管理面板小地图 | 5 |

---

## 八、UI 位置编辑器

按 `U` 键打开游戏内 UI 布局编辑器。只管理位置信息，不涉及业务逻辑。

### 功能

- **拖拽移动**：鼠标拖拽组件改变位置
- **8 点缩放**：选中组件后拖拽四角/四边手柄调整尺寸
- **组件面板**：点击面板中的组件类型添加新组件到画布
- **删除**：选中后按 Delete 键或点击 Delete Selected 按钮
- **键盘微调**：方向键 ±1px（网格模式 ±4px）
- **网格吸附**：可切换 4px 网格
- **保存/加载**：布局导出到 `config/wandscape/ui_layouts/<name>.json`

### JSON 布局格式

```json
{
  "name": "my_layout",
  "panel": { "width": 360, "height": 250 },
  "widgets": [
    { "id": "button", "x": 18, "y": 40, "width": 100, "height": 20 },
    { "id": "search_bar_1", "x": 18, "y": 70, "width": 180, "height": 16 }
  ]
}
```

### 编辑器界面布局

- 继承 `MedievalScreen`（360×250 面板，自适应居中）
- 左侧：组件画布（金边线框 + 网格点 + 选中高亮 + 8 个缩放手柄）
- 右侧上部：组件面板（点击添加新组件）
- 右侧中部：属性显示（名称、X/Y/W/H）
- 右侧下部：操作区（布局名输入 + Save/Load/Reset/Grid + Delete 按钮 + 已存布局列表）

---

## 九、独立测试方案

### 编译验证

1. `./gradlew build` 编译通过

### 手动验证

1. 按 `M` 键打开 Demo → 看到羊皮纸背景 + 金边面板
2. 所有组件在面板中渲染正常（含新增 Less/More/Arrow/Help/Option 按钮）
3. 按钮 hover/点击交互正确
4. 滚动列表虚拟滚动不卡顿
5. 元素面板 9 个图标正确显示
6. 按 `U` 键打开编辑器 → 拖拽组件 → 保存布局到 JSON
7. 重新加载布局验证数据正确

### 集成验证

1. 仓库 GUI 继承 `MedievalScreen` → 背景和边框风格一致
2. 工作站 GUI 复用 `TabBar` + `ElementPanel` → 风格统一
3. 编辑器导出布局可供其他 Screen 加载使用
