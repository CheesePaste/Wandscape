# Wandscape 引导系统（Onboarding & Tutorial Guide System）设计文档

> **文档状态**：已落盘并完成核心渲染器实现  
> **更新日期**：2026-08-02  
> **适用版本**：NeoForge 1.21.1 / Wandscape 1.3.1a+  

---

## 一、 系统定位与设计原则

Wandscape 拥有两大核心系统（**殖民地自动化**与**游客模拟经营**），包含了复杂的经营界面、建造工具与特效编辑器。为了让新玩家与创作者平滑上手，引导系统必须满足以下原则：

1. **非侵入式高亮（Spotlight Focus）**：通过暗化背景与组件框选高亮（Spotlight Highlight），精确引导玩家注意到关键按钮/区域，避免信息过载。
2. **渐进式解锁（Contextual Progression）**：根据玩家的游戏阶段（如：首次建造市政厅、首次游客入城、首次打开扫描器）触发引导，不进行一次性鸭霸式灌输。
3. **完全数据驱动（JSON + Markdown 混合契约）**：引导步骤、高亮目标 ID 由 JSON 配置，引导指南说明文本使用标准的 Markdown 文档排版。
4. **资深向与跳过保护（Replay & Skip）**：提供“跳过引导”与“再次播放”选项，老玩家不被打扰，新手随时可温故知新。

---

## 二、 核心渲染器架构（`shared/ui/markdown/`）

为了让引导视窗拥有媲美网页端的高质感排版，且不依赖任何重量级外部 Jar（防止 LWJGL/RenderSystem 线程死锁与跨平台崩溃），我们自研了纯 Java + MC 原生结合的 Markdown 渲染引擎：

```
shared/ui/markdown/
├── ast/
│   ├── MarkdownNode.java          // AST 节点抽象基类 (Header, Text, Image, Quote, List)
│   ├── HeaderNode.java            // 标题节点 (H1~H3，绑定中世纪调色板)
│   ├── TextParagraphNode.java     // 富文本段落 (内含 FormattedSpan 记录)
│   ├── ImageNode.java             // 图像节点 (绑定资源路径与尺寸)
│   ├── QuoteBlockNode.java        // 引用块节点 (金边暗底提示框)
│   └── ListNode.java              // 列表节点 (有序与无序 bullet 标记)
├── parser/
│   └── MarkdownParser.java        // 纯 Java 零依赖 AST 简易解析器
├── texture/
│   └── MarkdownTextureManager.java // 全能图像管理器 (支持 PNG, GIF 动态帧, JPG/JPEG/BMP 自动转码)
├── gif/
│   └── GifDecoder.java            // GIF 动画解码器 (处理帧序列与 Disposal Method)
└── widget/
    └── MarkdownRenderWidget.java  // MC GUI 渲染控件 (自动分行、滚动条裁剪、点击响应)
```

---

## 三、 引导 Markdown 自定义语法规范 (Syntax Specification)

引导指南文档放在 `assets/wandscape/guide/<locale>/` 资源路径中（locale 子目录：`zh_cn/` 已有 20 个 md，`en/` 待翻译），支持以下丰富语法：

### 1. 标题语法（Header）
- `# H1 大标题`：使用 `BORDER_GOLD` (#C8A040) 金色发光渲染，自带下方黄金分界线。
- `## H2 二级标题`：使用 `ACCENT_GOLD` 紫金色渲染。
- `### H3 三级标题`：使用 `TEXT_WARM_WHITE` 暖白渲染。

### 2. 行内富文本样式（Inline Formatting）
- `**粗体**`：加粗显示文本。
- `*斜体*`：倾斜显示文本。
- `~~删除线~~`：文本添加删除线。
- `` `代码块` ``：使用等宽代码格式。

### 3. 提示引用框（Quote Block）
- `> 提示文本`：渲染为左侧带有黄金竖线（`BORDER_GOLD`）与暗色卷轴背景（`PARCHMENT_DARK`）的精致提示卡片，支持多行与嵌套样式。

### 4. 列表语法（List）
- `- 无序列表项` 或 `* 列表项`：渲染为带有金黄色圆点（`• `）的缩进列表。
- `1. 有序列表项`：渲染为带有金黄色序号（`1. `, `2. `）的步骤列表。

### 5. 图像与动画语法（Image & Animation）
语法格式：`![图片描述](ResourceLocation =WIDTHxHEIGHT)`

- **静态 PNG 图片**：
  `![架构图](wandscape:textures/gui/guide/townhall_demo.png =180x90)`
  - 自动在视窗中央居中绘制，带有中世纪暗金外框（`BORDER_GOLD_DARK`）。
- **GIF 动态动画**：
  `![操作演示](wandscape:textures/gui/guide/anim_demo.gif =160x90)`
  - 自动逐帧解析 GIF 并根据真实时间戳（`System.currentTimeMillis()`）在 UI 中流畅播放。
- **JPG / JPEG / BMP 自动转码图片**：
  `![插画](wandscape:textures/gui/guide/illustration.jpg =180x90)`
  - 自动在后台解码并转注册为 `DynamicTexture`，创作者无需手动转换格式。

### 6. 交互动作链接（Action Link）
语法格式：`[链接文字](action:动作标识符)`

示例：
- `[点击开启选建模式](action:wandscape:overview_mode)`
- `[打开市政厅](action:open_screen:townhall)`

当玩家在 Markdown 视窗中将鼠标悬停在链接上时，链接会自动高亮下划线并弹出 Tooltip；点击后触发注册的 `actionClickListener` 句柄在游戏内直接响应动作！

---

## 四、 全屏幕与编辑器引导清单（14 大 UI 屏幕/编辑器全收录）

本模组包含的所有界面、工具 Overlay 及创作者编辑器均已纳入引导系统：

```
Wandscape UI 体系
├── 1. 殖民地经营与核心 Screen (8个)
│   ├── TownHallScreen (市政厅)
│   ├── WarehouseScreen (仓库)
│   ├── NodeScreen (资源节点)
│   ├── ShopScreen (商店)
│   ├── HotelScreen (旅馆)
│   ├── TavernScreen (酒馆)
│   ├── CraftingStationScreen / WorkstationScreen (合成/工作站)
│   └── AnomalyScreen (异象/奇观)
├── 2. 角色与实体 Screen (2个)
│   ├── NpcScreen (法师/NPC 角色)
│   └── TouristScreen (游客面板与调试)
├── 3. 玩家建造工具 & Overlay (2个)
│   ├── BuildingSelectionOverlay & WandscapePanelOverlay (选建与 Overview 模式)
│   └── RoadPlacementOverlay (道路铺设与 Replace/Fill 侧边栏)
└── 4. 创作者与开发向编辑器 (2个)
    ├── BuildingScannerScreen (游戏内建筑扫描与数据编辑器)
    └── Magic Circle Web Editor (独立 Web 魔法阵粒子特效编辑器)
```

---

## 五、 测试与调试指令

开发与调试期间，可直接使用内置命令在游戏内打开测试视窗：

```bash
/wandscape guide
```

执行该命令后，服务端会向客户端发送 `GuideTestPacket`，并在游戏内弹出基于 `MedievalScreen` 玻璃金边样式的 Markdown 测试视窗；视窗内容由客户端 `DocumentLoader` 按当前语言从 `assets/wandscape/guide/<locale>/` 加载（当前为 `zh_cn/`），展示所有排版、图片与点击交互效果。
