# Element Icons 设计交接文档

## 一、元素类型

模组定义了 7 种元素（`ElementType` 枚举）：

| ID | 名称 | 目前颜色 |
|----|------|---------|
| earth | 地 | 泥土棕 #8B6914 |
| wood | 木 | 森林绿 #2E8B57 |
| water | 水 | 水蓝 #4A90D9 |
| fire | 火 | 火焰红 #B22222 |
| metal | 金/矿 | 银灰 #808080 |
| wind | 风 | 天蓝 #87CEEB |
| dark | 暗 | 深紫 #4B0082 |

## 二、现有图标风格分析

### 已有图标（不需要改）
位于 `textures/gui/icons/`，全部是 **64x64、白色(0xFFFFFF) 通道图**：
- `tab_build.png`, `tab_road.png`, `tab_stats.png`, `tab_editor.png`
- `icon_colony.png`, `icon_comfort.png`, `icon_magic.png`, `icon_wonder.png`

**风格特征**：极简扁平矢量风，纯白色填充 + 透明背景，运行时通过 `WandscapeTheme.drawIcon()` 着色。

### 已生成但不满意的 element 图标
位于同上目录，我生成了 `element_earth.png` 等 5 个。问题是：
- 形状不可识别（看起来不像对应的元素）
- 缺少 metal 和 dark
- 没有元素"感"

### 已生成的通用 OK 图标（不需要改）
- `icon_tourist.png` — 人物剪影 ✔
- `icon_warning.png` — 三角警告 ✔

## 三、UI 图标设计原则（来自调研）

1. **瞬时识别**：20-64px 下看一眼就懂，不需要解释
2. **扁平极简**：纯白通道图，无渐变/阴影/描边，运行时着色
3. **统一风格**：相同的线宽、边距、圆角，成为一套体系
4. **经典隐喻**：用约定俗成的符号——水滴=水，火焰=火，树叶=木，山石=地
5. **一图标一概念**：不把多个含义塞进一个图标

## 四、参考素材

### 最推荐的参考
- **Magic elements Game interface icons**（Robert Brooks, itch.io/GameDevMarket）：含完全可编辑的扁平白色变体，覆盖 earth/water/fire/air/ice/poison/life/light/shadow/darkness/night/day/time。**最匹配我们的风格需求**
- **Element Icons**（Unity Asset Store, $4.99）：12 elements × 5 styles，含白色版。覆盖 fire/wind/soil-rock/water/leaves/ice/lightning/heart/light/darkness/sun/moon。**覆盖最全**
- **12 Elemental Type Symbols**（OpenGameArt, CC-BY 4.0）：9×9 像素风格 sprite，含 water/earth/air/fire/ice/plant/lightning/metal/magic/darkness。**metal 和 darkness 参考**

### Feng Shui 五行参考（传统文化符号，高可识别性）
| 元素 | 传统符号 |
|------|---------|
| 地 (Earth) | 山/方块/三角朝下 |
| 木 (Wood) | 树/植物/发芽 |
| 水 (Water) | 波浪/水滴/曲线 |
| 火 (Fire) | 火焰/三角朝上 |
| 金 (Metal) | 圆/环/齿轮 |

## 五、推荐设计方案

### 每种元素的视觉隐喻建议

| 元素 | 推荐符号 | 理由 |
|------|---------|------|
| earth | ⛰ 山/石块 / ⊞ 菱形方块 | MC 玩家对泥土方块最敏感 |
| wood | 🌲 树(三角冠+矩形干) | 最直接可识别的木象征 |
| water | 💧 水滴 / 〰️ 波浪 | 水滴极小尺寸下可识别 |
| fire | 🔥 火焰(水滴形+三叉) | 火焰轮廓是通用语言 |
| metal | ⚙ 齿轮 / ◆ 菱形矿 | 象征工业/金属 |
| wind | 🌪 螺旋 / ≈ 波浪线 | 风不可见，用动势线表示 |
| dark | 🌙 新月 + 星 / ✦ 星形 | 暗/魔法类通用符号 |

### 技术规格
- **尺寸**：64×64（与其他 icon_*.png 一致）
- **配色**：纯白 (0xFFFFFF) 为主，允许少量浅灰 (0xD0D0D0) 做层次
- **格式**：PNG RGBA，透明背景
- **文件命名**：`element_{id}.png`（如 `element_dark.png`）

### 制作建议
1. 使用矢量工具（Figma/Illustrator/Affinity）导出 64×64 PNG
2. 先画经典元素符号（水滴、火焰、树、山），确保小尺寸可识别
3. 保持各图标视觉重量一致（填充率 ~60-70%，留白均等）
4. 每个元素 icon 导出后缩放到 16×16 验证可读性

## 六、未完成任务列表（修改暂停中）

### 🔴 代码已完成但依赖于新 icon 文件的任务

| # | 任务 | 文件 | 状态 | 依赖 |
|---|------|------|------|------|
| 1 | 图标纹理生成 | `gen_icons.py` | ⏳ **等待重制** | 需要按本设计重新生成 |
| 2 | 图标常量注册 | `WandscapeTheme.java` + 新常量和 elementIcon() 映射 | ✅ 已完成 | 无 |
| 3 | 同步包扩容 | `ColonyStatsSyncPacket.java` + `WandscapePanelState.java` | ✅ 已完成 | 无 |
| 4 | 服务端数据采集 | `PanelStateTracker.java` + `PanelStateTogglePacket.java` | ⏳ 未完成 | 无 |
| 5 | 顶栏重写+侧边栏 | `WandscapePanelOverlay.java` | ⏳ 未完成 | 依赖 #1 icon 就位 |
| 6 | 侧边栏点击处理 | `WandscapePanelController.java` | ⏳ 未完成 | 依赖 #5 |

### 📋 实现说明

`gen_icons.py` 在项目根目录。用 python3 执行，依赖 PIL。

重新生成 element 图标后，把生成的 `element_*.png` 放到：
`src/main/resources/assets/wandscape/textures/gui/icons/`

所有 icon 就位后，按以下顺序继续实现：

---

## 七、未完成任务详细实现说明

### 7.1 服务端数据采集（`PanelStateTracker.java` + `PanelStateTogglePacket.java`）

**目标**：面板打开或殖民地评估变化时，收集新字段数据并发送 `ColonyStatsSyncPacket`。

**需要的数据与获取方式**：

| 字段 | 来源 | 方法 |
|------|------|------|
| `touristCount` | `WandscapeApis.getTouristApi().getTouristCount(colonyId)` | 用 try-catch 包裹，因 TouristApi 可能为 null |
| `shutdownCount` + `shutdownBuildingNames` | `WandscapeApis.getBuildingApi().getColonyBuildings(colonyId)` | 遍历 `BuildingData.isShutdown()` 过滤，收集 `getBuildingTypeId()` |
| `npcIdleCount` + `npcTotalCount` | `WandscapeApis.getNpcApi().getIdleNpcs(colonyId).size()` + `.getColonyNpcs(colonyId).size()` | try-catch，NpcApi 可能未加载 |
| `earthAmount` / `woodAmount` / ... | `WandscapeApis.getWarehouseApi().getAllElements(colonyId)` | 返回 `Map<ElementType, Long>`，取 5 种主要元素的值转 int |

**修改位置**（两处）：

1. `PanelStateTracker.java:74-75` — `onColonyEvaluationChanged` 事件中，构造 `ColonyStatsSyncPacket` 时传入新字段
2. `PanelStateTogglePacket.java:51-52` — `handleServer` 面板打开时，同样构造带新字段的 packet

**注意事项**：
- 所有 `WandscapeApis.getXxxApi()` 调用可能抛 `IllegalStateException`（模块未加载），**必须用 try-catch 兜底**，失败时传 0/空列表
- `WarehouseApi.getAllElements()` 返回 `Map<ElementType, Long>`，只取 `EARTH/WOOD/WATER/FIRE/WIND` 五种转 `int`（`Long::intValue`）
- `BuildingData.getBuildingTypeId()` 是建筑类型 ID（如 `"minecraft:furnace"`），直接用于 shutdown 列表的显示

---

### 7.2 顶栏重写 + 侧边栏（`WandscapePanelOverlay.java`）

**目标**：将现有底栏的 3 个 tab 移到左侧竖排，在顶部渲染全信息 HUD 栏。

#### 7.2.1 顶栏内容（从左到右，单行 ~28px）

```
[🏠 风谷镇Lv.3] [☘45] [✦32] [◇28] │ D47 │ 👤5/12 ⚡3/8 │ ⚠2 │ [⛰12][🌲8][💧3][🔥2][🌪1]
```

分段设计：

| 段 | 内容 | icon 大小 | 颜色 |
|----|------|----------|------|
| 1 | `ICON_COLONY(16x) + name + " Lv." + level` | 16 | `COLOR_TEXT_NORMAL` |
| 2 | `ICON_COMFORT + value`, `ICON_MAGIC + value`, `ICON_WONDER + value` | 12 each | 各自主题色 |
| 3 | `"Day " + day` | — | `COLOR_TEXT_DIM` |
| 4 | `ICON_TOURIST + "cur/total"` | 12 | `COLOR_TEXT_NORMAL` |
| 5 | `"⚡" + idle + "/" + total` | — | `COLOR_TEXT_NORMAL` |
| 6 | `ICON_WARNING + count`（count=0 时不显示） | 12 | count>0 时 `COLOR_TEXT_ACTIVE`，否则 `COLOR_TEXT_DIM` |
| 7 | 5 个 element icons + 值，用逗号分隔 | 10 each | `NAME_COLORS` 对应色 |

**参考代码（当前顶栏渲染位置）**：
- `renderFills()`: 第 105-130 行，顶部 box + 底部 tab bar
- `renderTexts()`: 第 155-235 行，top-left 文本 + stats 内容 + 底部 tab 图标

**改动要点**：
1. **移除底栏**：删除 `renderFills()` 中的底部 tab bar（第 133-146 行）和 `renderTexts()` 中的底部 tab 图标与 help text（第 197-234 行）
2. **顶栏 flush 到顶**：当前 colony widget 在 `y=10`，改为 `y=0`，背景从 `topY=10` 改为 `y=0`
3. **合并绘制**：将顶栏所有内容在 `renderFills()` 中绘制背景框，在 `renderTexts()` 中绘制 icon + 文本
4. **Day 计算**：`mc.level.getDayTime() / 24000` 客户端本地计算，不需要同步
5. **图标绘制**：使用 `WandscapeTheme.drawIcon(g, icon, x, y, w, h, tintColor)` 统一渲染
6. **文字绘制**：使用已有 `drawText()` / `drawCenteredText()` helper

**常量建议**：
```java
public static final int TOP_BAR_H = 28;           // 顶栏高度
public static final int SIDEBAR_W = 36;            // 侧边栏宽度
public static final int SIDEBAR_ICON_S = 24;       // 侧边栏图标大小
public static final int SIDEBAR_GAP = 8;           // 图标间距
public static final int ELEMENT_ICON_S = 10;       // 顶栏元素图标大小
```

#### 7.2.2 侧边栏布局

```
y=0 + TOP_BAR_H
  ┌── SIDEBAR_W (36px) ──┐
  │                       │
  │  [Build icon]  24x24  │  ← tabIndex=0, 蓝色高亮激活
  │  [Road icon]   24x24  │  ← tabIndex=1
  │  [Stats icon]  24x24  │  ← tabIndex=2
  │                       │  ← 12px spacer
  │  [Warning]     24x24  │  ← 底部警告图标，点击切换面板
  │                       │
  └───────────────────────┘
```

- 图标垂直居中在 36px 宽的侧边栏内
- 每个图标 24×24，间距 8px
- 激活 tab 的图标使用 `COLOR_TEXT_ACTIVE`（绿色），非激活用 `COLOR_TEXT_DIM`
- 警告图标有红色小圆点 badge 当 `shutdownCount > 0`（在 icon 右上角画 4×4 红圈）
- 警告图标点击后切换 `WandscapePanelState.isWarningOverlayActive()`
- 侧边栏背景：半透明深色框 `0xAA111214`

**侧边栏渲染函数**（在 `renderFills` 中调）：
```java
private static void renderSidebar(GuiGraphics g, int screenW, int screenH, double mx, double my) {
    int x = 0;
    int y = TOP_BAR_H;
    int w = SIDEBAR_W;
    int h = screenH - TOP_BAR_H;
    // 背景
    g.fill(RenderType.guiOverlay(), x, y, x + w, y + h, 0, 0xAA111214);
    // 绘制图标...
}
```

#### 7.2.3 停摆建筑列表浮层

`warningOverlayActive=true` 时，在侧边栏右侧渲染一个小浮层：

```
┌─── WARNING ──────────────┐
│ ⚠ Shutdown Buildings (2) │
│ • minecraft:furnace      │
│ • wandscape:bakery       │
└──────────────────────────┘
```

- 宽度 200px，从 `x = SIDEBAR_W` 开始
- 背景 `0xEE111214`，白色边框
- 数据来自 `WandscapePanelState.getShutdownBuildingNames()`
- 标题 "Shutdown Buildings" 用 `COLOR_TEXT_ACTIVE`，建筑名用 `COLOR_TEXT_DIM`

---

### 7.3 侧边栏点击处理（`WandscapePanelController.java`）

**目标**：将现有的底部 tab 点击逻辑改为侧边栏点击。

#### 7.3.1 鼠标区域检测

替换 `onMouseButtonPre()` 中的底部 bar 点击：

```java
// 原底部 tab 检测（第 186-192 行）：
if (mouseY >= screenH - BOTTOM_BAR_HEIGHT) {
    int tabIndex = getTabAt(mouseX, mouseY, screenW, screenH);
    ...
}

// 替换为侧边栏检测：
if (mouseX <= SIDEBAR_W && mouseY >= TOP_BAR_H) {
    int sidebarIconIndex = getSidebarIconAt(mouseY, screenH);
    if (sidebarIconIndex >= 0 && sidebarIconIndex < 3) {
        // 前三个是 Build/Road/Stats tab，复用 handleTabClick()
        handleTabClick(sidebarIconIndex);
    } else if (sidebarIconIndex == 3) {
        // 第四个是警告图标，切换警告浮层
        WandscapePanelState.toggleWarningOverlay();
    }
    event.setCanceled(true);
    return;
}
```

#### 7.3.2 侧边栏图标索引计算

```java
public static int getSidebarIconAt(double mouseY, int screenH) {
    int startY = TOP_BAR_H + 8; // 8px top padding
    int iconArea = SIDEBAR_ICON_S + SIDEBAR_GAP; // 32px per icon
    int count = 4; // Build, Road, Stats, Warning
    for (int i = 0; i < count; i++) {
        int iconY = startY + i * iconArea;
        if (mouseY >= iconY && mouseY <= iconY + SIDEBAR_ICON_S) {
            return i;
        }
    }
    return -1;
}
```

#### 7.3.3 需要修改的方法

| 方法 | 修改内容 |
|------|---------|
| `onMouseButtonPre()` | 替换底部 bar → 侧边栏检测逻辑 |
| `getTabAt()` | **保留**（供 hover 效果用），但改为 `getSidebarIconAt()` |
| `handleTabClick()` | 保持不变，侧边栏点击继续调用它 |
| `onScreenOpen()` | 底部 bar 相关检查逻辑改为侧边栏（`mouseY > screenH - BOTTOM_BAR_HEIGHT` → `mouseX < SIDEBAR_W`） |
| `onMouseScroll()` | 保持不变 |

#### 7.3.4 hover 效果

侧边栏 hover 检测在 `WandscapePanelOverlay.getSidebarHoveredIcon()` 中实现：
- 当 `isCursorLifted()` 时获取鼠标位置
- 计算 hover 到的 icon index
- 渲染时对 hovered icon 使用 `COLOR_TEXT_NORMAL`，其他非激活用 `COLOR_TEXT_DIM`

---

### 7.4 涉及文件清单

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `gen_icons.py` | 修改 | 重新生成 7 种 element icon（极简扁平矢量风，64×64，白通道图） |
| `WandscapeTheme.java` | ✅ 已完成 | 已添加所有 icon 常量和 `elementIcon()` 映射 |
| `ColonyStatsSyncPacket.java` | ✅ 已完成 | record 已扩展新字段 |
| `WandscapePanelState.java` | ✅ 已完成 | 已添加新字段 + getter + overloaded setter |
| `PanelStateTracker.java` | 修改 | 收集新数据传入 packet |
| `PanelStateTogglePacket.java` | 修改 | 面板打开时同上 |
| `WandscapePanelOverlay.java` | 重写 | 顶栏重写 + 移除底栏 + 新增侧边栏渲染 |
| `WandscapePanelController.java` | 修改 | 底部 tab 点击 → 侧边栏点击 |
| `BuildingSelectionOverlay.java` | 可能需微调 | 底部空间位置常量调整 |
| `RoadPlacementOverlay.java` | 可能需微调 | 同上 |
| `WandscapePanelOverlay.java:isInBottomBar()` | 删除/修改 | 不再需要 |

### 7.5 数据流总结

```
Server (tick / panel open)
  │
  ├─ ColonyStatsSyncPacket ──→ Client (WandscapePanelState)
  │     ├ colonyId, name, level, exp
  │     ├ comfort, magic, wonder
  │     ├ touristCount, shutdownCount
  │     ├ npcIdleCount, npcTotalCount
  │     ├ earth~wind amounts
  │     └ shutdownBuildingNames (List<String>)
  │
  └─ PanelStateTogglePacket ←── Client (面板打开/关闭)
        → 触发 server 回传 ColonyStatsSyncPacket

Client:
  ├─ WandscapePanelState.setColonyStats()  ← 存储所有数据
  ├─ WandscapePanelOverlay.renderFills()   ← 顶栏背景 + 侧边栏背景
  └─ WandscapePanelOverlay.renderTexts()   ← 顶栏 icon/文本 + 侧边栏 icon + 警告浮层
```
