# 帕秋莉手册美术资产规格书（要画什么、每个多大、怎么排）

> 信息截至 2026-09-12 | Patchouli `1.21.1-93-NEOFORGE` | 调研对象 `_refs/` 下 6 个手册
> 本文是**给美术的施工图**：只列「需要哪些图、每张多大、每一格里画什么、以及文字占多大地方」。
> 具体配色/风格/笔触不做规定。接入管线与 md→JSON 生成规则见 [../guidebook-patchouli.md](../guidebook-patchouli.md)。

- **【何时读】**：设计或重画手册书皮、正文插图、图标、书物品贴图时；调整手册字号/边距/分栏时；想用模板做自定义版面时。
- **【不包含什么】**：玩法文案（`assets/wandscape/guidebook/*.md`）、Patchouli 的 JSON 字段全集（读 `_refs/patchouli/web/docs/`）。
- **【事实来源】**：坐标与尺寸取自 `_refs/patchouli` 源码（`GuiBook.java`、`GuiButton*`、`Page*.java`、`Book.java`）；各模组用量为全库 grep 计数；图片尺寸为 PNG IHDR 实测。

---

## 0. 结论速览：一共要出 7 类图

| # | 资产 | 数量 | 单张尺寸 | 是否必须 | 难度 |
|---|---|---|---|---|---|
| 1 | **书皮皮肤图集** `book_texture` | 1 张 | **512×256**（硬约束，不可改） | 必须 | 高（23 类槽位） |
| 2 | 页面填充图 `filler_texture` | 1 张 | **128×128** | 可选（有默认） | 低 |
| 3 | 配方图集 `crafting_texture` | 1 张 | **128×256** | 可选（有默认） | 中（6 个槽位） |
| 4 | 书物品贴图 + 模型 | 1 张 + 1 JSON | **16×16** | 必须 | 低 |
| 5 | 正文插图（`image` 页 / 模板组件） | N 张 | **256×256**，内容限左上 **200×200** | 按需 | 中 |
| 6 | 条目/分类图标 `icon` | 0 张（用物品 ID）或 N 张 **16×16** | 16×16 | 可选 | 低 |
| 7 | 开合音效 | 2 个 ogg | 任意 | 可选 | 低 |

> 不含「书物品 3D 模型」：帕秋莉默认走 `item/generated` + 16×16 平面贴图，够用。

---

## 一、书皮皮肤图集 `book_texture`（核心工作量，512×256）

### 1.1 为什么必须是 512×256

帕秋莉把图集尺寸**硬编码**在取样调用里，没有任何配置项：

```java
// GuiBook.java:258 —— 所有书皮取样都走这里
graphics.blit(book.bookTexture, x, y, u, v, w, h, 512, 256);
```

`512, 256` 是 UV 归一化分母；按钮类还在此基础上做二次 2 倍取样。换成别的尺寸，所有槽位整体错位。**这条是死约束。** 6 个参考手册无一例外都是 512×256。

### 1.2 前提：GUI 双页几何

书皮不是一整张画，而是**一个坐标图集**：左上 `(0,0)` 起的 272×180 区域就是双页本体，其余位置摆放各个按钮。

```
                    0        15              131  141            257      272
                    +---------+----------------+----+--------------+--------+
  0                 |  封面/边框留白带（书脊、页缘、包边都画在这里）        |
                    |         |  左页纸面      |    |  右页纸面     |        |
 18                 |         |  (116×156)     |    |  (116×156)    |        |
                    |         |                |    |               |        |
                    |         |                |    |               |        |
174                 |         |                |    |               |        |
                    |         |                |    |               |        |
180 ----------------+---------+----------------+----+---------------+--------+
                    | 着陆页名牌 140×31 | 分隔条 110×3 | 搜索框 | 锁 16×16 |
256 ----------------+------------------------------------------------------+
                    | 右缘外侧：按钮 / 页签（x≥272）                        |
                    | 右下：插图外框 (405,149) 106×106                      |
```

| 几何常量 | 值 | 出处 |
|---|---|---|
| 书本体 `FULL_WIDTH × FULL_HEIGHT` | **272 × 180**，画在 `(0,0)` | `GuiBook.java:51-52` |
| 单页 `PAGE_WIDTH × PAGE_HEIGHT` | **116 × 156** | `GuiBook.java:53-54` |
| 左页左上角 | `(15, 18)`，即 `x 15..130`、`y 18..173` | `LEFT_PAGE_X=15`、`TOP_PADDING=18` |
| 右页左上角 | `(141, 18)`，即 `x 141..256` | `RIGHT_PAGE_X=141` |

**美术约束**：左页 x15..130 / 右页 x141..256 / y18..173 之内必须是**浅色纸面**。`book.json` 未覆写 `text_color` 时正文用默认 `#000000`，深色纸面不可读（要深色纸就得同时改 `text_color`）。中线 x131..140 是书脊，画装订线。

### 1.3 槽位全表（权威）

**通用规则**：按钮都有**悬停变体**，取样坐标为 `(u + w, v)` —— 同一行向右平移一个自身宽度。每个按钮要画**两份并排**。

| # | 用途 | 取样 `u, v` | 尺寸 `w×h` | 悬停版 `u` | 画什么 |
|---|---|---|---|---|---|
| 1 | **双页背景** | `0, 0` | **272×180** | 无 | 封面包边 + 书脊 + 左右两页纸面。所有页面的底图 |
| 2 | **着陆页名牌底条** | `0, 180` | **140×31** | 无 | 画在**屏幕 `(-8, 12)`**，左端超出书外；上方叠印书名（`nameplate_color`）与副标题 |
| 3 | 分隔条 | `140, 180` | 110×3 | 无 | 一条横向细线，页内水平居中 |
| 4 | 搜索框底 | `140, 183` | 99×14 | 无 | 输入框底板（内浅、外描边） |
| 5 | 锁图标 | `250, 180` | 16×16 | 无 | 条目/分类被 `advancement` 锁住时叠加 |
| 6 | 状态标记：未读 | `140, 197` | 8×8 | 无 | 条目列表行右侧小标记 |
| 7 | 状态标记：待办 | `148, 197` | 8×8 | 无 | 同上（`PENDING`，会呼吸闪烁） |
| 8 | 状态标记：已完成 | `156, 197` | 8×8 | 无 | 同上（`COMPLETED`） |
| 9 | 返回按钮 | `308, 0` | 18×9 | `326` | 书底中部 |
| 10 | 翻页箭头：下一页 | `272, 0` | 18×10 | `290` | 书右缘外侧 |
| 11 | 翻页箭头：上一页 | `272, 10` | 18×10 | `290` | 书左缘外侧 |
| 12 | 小箭头：下一张（图片页） | `272, 20` | 5×7 | `277` | 图片页右下角的翻图箭头 |
| 13 | 小箭头：上一张 | `272, 27` | 5×7 | `277` | 同上 |
| 14 | 书签页签（空） | `272, 160` | 13×10 | `285` | 书右缘外凸页签 |
| 15 | 书签页签（已存） | `272, 170` | 13×10 | `285` | 同上 |
| 16 | 「历史」按钮 | `330, 31` | 11×11 | `341` | 着陆页底部按钮排 |
| 17 | 「成就」按钮 | `330, 20` | 11×11 | `341` | 仅 `advancements_tab` 非空时出现 |
| 18 | 「调整大小」按钮（放大镜） | `330, 9` | 11×11 | `341` | 仅 GUI scale > 2 时出现 |
| 19 | 「标记已读」/ 眼睛按钮 | `308, 31` | 11×11 | `319` | 也用于多方块的"投影到世界"按钮 |
| 20 | 「配置」按钮 | `308, 20` | 11×11 | `319` | 书内设置 |
| 21 | 「编辑器」按钮 | `308, 9` | 11×11 | `319` | 仅创造模式出现 |
| 22 | **图片/实体/多方块外框** | `405, 149` | **106×106** | 无 | **必须中空**（只画边框，中心透明），盖在插图/实体渲染上。`border: true` 或实体/多方块页才用 |
| 23 | **分类按钮格子** | `151+24i, 43+24j` | **20×20** | 无 | 见 §1.4 坑 1 |

### 1.4 三个必须知道的坑

**坑 1：分类按钮取样的是「书页背景上的同一块像素」。**
`GuiButtonCategory` 的取样坐标等于按钮在**页内的相对坐标**，即书本体图集内的同一位置：

```java
// GuiButtonCategory.java:35-38, 67
super(parent.bookLeft + x, parent.bookTop + y, 20, 20, ...);
this.u = x; this.v = y;                       // u,v = 页内相对坐标
GuiBook.drawFromTexture(graphics, parent.book, getX(), getY(), u, v, width, height);
```

分类网格起点 `(RIGHT_PAGE_X+10, TOP_PADDING+25) = (151, 43)`，4 列 × 24px 步距，最多 4 行：

| 列 | u | 行 | v |
|---|---|---|---|
| 1 | 151 | 1 | 43 |
| 2 | 175 | 2 | 67 |
| 3 | 199 | 3 | 91 |
| 4 | 223 | 4 | 115 |

后果有两条，**必须在动笔前决定**：

- **想做可见的分类按钮底板**：就在这 16 个 20×20 格子里画。但这些像素**同时属于 272×180 书背景**，会出现在**每一页**右页的同一位置。
- **不想让按钮框出现在别处**：像默认皮肤那样留空白纸面即可 —— 按钮只在悬停时做 0.5→1.0 的 alpha 变化，视觉上只看得见物品图标。

实测：帕秋莉自带 7 张 `book_*.png` 与 Goety 两张自绘黑书皮，该区域都只是纸面（32 色，与周围纸面完全同色）。

**坑 2：分类不超过 16 个。** 着陆页没有分页逻辑，第 17 个分类会画到页外（`GuiBookLanding.java:127`）。

**坑 3：名牌底条画在屏幕 `(-8, 12)`，有 8px 在书外。** 别在左端放重要内容。

### 1.5 各参考手册的做法

| 手册 | `book_texture` | 尺寸 | 做法 |
|---|---|---|---|
| **Wandscape（现状）** | `wandscape:textures/gui/guidebook/book.png` | 512×256 | 占位图：棕色包边 + 米色纸面；坐标已精确对齐（实测纸面 = 左 15..130 / 右 141..256 / y18..173，与常量逐一吻合） |
| **Goety** `black_book` | `goety:textures/gui/black_book.png` | 512×256 | 真实美术：把默认皮整体重上色为黑金，槽位布局像素级沿用默认 |
| **Goety** `witches_brew` | `goety:textures/gui/witch_book.png` | 512×256 | 同上，另一套配色 |
| **Botania** `lexicon` | `patchouli:textures/gui/book_green.png` | 512×256 | 直接引用帕秋莉自带绿皮，**零书皮美术** |
| **IronSpells** | 未设 → 默认 `book_brown.png` | 512×256 | 零书皮美术 |
| **TouhouLittleMaid** | 未设 → 默认 `book_brown.png` | 512×256 | 零书皮美术 |
| AE2（遗留未引用） | `ae2:textures/patchouli/patchouli_gui.png` | 512×256 | 仓库里躺着但已无任何引用（AE2 换自研手册了） |

**要点**：6 个参考手册里**只有 Goety 出了自绘书皮**，且是**在默认皮上重上色**、布局一字未改——这是最省力且零风险的做法。其余 4 个直接引用帕秋莉自带皮肤。

---

## 二、配方图集 `crafting_texture`（128×256）

只有做 `crafting` / `smelting` / `smithing` / `stonecutting` / `smoking` / `blasting` / `campfire` 页才需要。不设 `crafting_texture` 就用默认的 `patchouli:textures/gui/crafting.png`。

| 槽位 | 取样 `u, v` | 尺寸 `w×h` | 用途 |
|---|---|---|---|
| 1 | `0, 0` | **100×62** | 工作台配方框：3×3 网格 + 箭头 + 右侧产物格 |
| 2 | `0, 64` | **11×11** | "无序配方"角标（带本地化提示） |
| 3 | `11, 71` | **96×24** | 单步处理配方框（熔炉/高炉/烟熏/营火/**切石机**共用）：原料格 → 箭头 → 产物格 |
| 4 | `11, 135` | **96×43** | 锻造台配方框：上二下二的输入格 + 箭头 + 产物格 |
| 5 | `20, 102` | **26×26** | 带框物品格（模板组件的 `"framed": true`） |
| 6 | `0, 102` | **66×26** | **聚光页底托**：`patchouli:spotlight` 大图标下面那条横向装饰条 |

**布局说明**：图集被有意切成四段 —— `y 0..62` 工作台、`y 64..95` 单步配方、`y 102..128` 装饰条带、`y 135..178` 锻造。`y 128..256` 核心逻辑不取样（可留空或留作扩展；Botania 模板反而从 `(65,27) 9×9` 抠小图标当箭头用）。

**物品格一律 16×16、步距 18**：格子内容是原版 `renderItem` 直接画的，图集里只画**边框/底板**，不要画物品本身。

**Wandscape 现状**：未设 `crafting_texture`，走默认。若要做配方页（12 支法杖），可直接沿用默认图集，零美术成本。

---

## 三、其余小件

### 3.1 页面填充图 `filler_texture` — 128×128

空页/内容不足时垫在页面中央的装饰图。

```java
// GuiBook.java:561
graphics.blit(book.fillerTexture, x + PAGE_WIDTH/2 - 64, y + PAGE_HEIGHT/2 - 74, 0, 0, 128, 128, 128, 128);
```

- **128×128**，整张 1:1 绘制，不缩放。
- 落点 `(116/2-64, 156/2-74) = (-6, 4)` —— 左右各溢出 6px、上下溢出 74/54px 被页边裁掉。
- 默认是 `patchouli:textures/gui/page_filler.png`（淡色羽毛笔线稿）。
- **建议**：低对比度淡色线稿/纹理，别抢正文。不设 = 用默认。

### 3.2 书物品贴图 — 16×16（+ 模型 JSON）

```
assets/wandscape/textures/item/guide_book.png          16×16   ← 已有
assets/wandscape/models/item/guide_book.json           item/generated，layer0 指向上面   ← 已有
```

**现状缺口**：`book.json` 没有 `"model"` 字段，帕秋莉会回落到自带的 `patchouli:book_brown`（棕色书）。贴图和模型 JSON 都在仓库里躺着没用上。补一行 `"model": "wandscape:guide_book"` 即可接上（帕秋莉自动补 `item/` 前缀，所以写不带目录的 id）。

参考：IronSpells `textures/item/patchouli_book.png` 16×16、TLM `textures/item/memorizable_gensokyo.png` 16×16、Botania `model: botania:lexicon`，都是这套。

### 3.3 正文插图 — 256×256，内容限左上 200×200

`patchouli:image` **页级**的硬规格（`PageImage.java:39-45`）：

```java
graphics.pose().scale(0.5F, 0.5F, 0.5F);
graphics.blit(images[index], x*2 + 6, y*2 + 6, 0, 0, 200, 200);
```

| 项 | 值 |
|---|---|
| 源图**只取左上** `(0,0)`–`(200,200)` | 其余像素**完全不显示** |
| 该区域在屏幕上只占 | **100×100** 像素 |
| 可调尺寸的字段 | **没有**（`width`/`height` 只在**模板组件**的 `patchouli:image` 里有） |
| `border: true` 时外框 | 106×106，取自书皮槽位 `(405,149)` |

**施工要求**：

- 出 **256×256** 正方形 PNG，**有效内容严格画在左上 200×200**，右/下各 56px 留透明（帕秋莉官方推荐值）。
- **字号按"最终只显示 100×100"来定**：源图字高至少 **16px**，否则缩到 1/2 后小于 8px 读不了。最细线宽 ≥ 2px。
- **现状**：`assets/wandscape/textures/gui/guidebook/` 下 6 张图（`road_diagram` / `magic_editor_diagram` / `overview_diagram` / `scanner_diagram` / `scanner_ui` / `sample`）**全是 1376×768**，会被裁成左上 200×200，等于只显示左上角一小块。**全部需要按 256×256 重画。**

**各参考手册的出图量**（都是 256×256，说明这是社区事实标准）：

| 手册 | 插图张数 | 位置 |
|---|---|---|
| Goety | **29** | `goety:textures/gui/entries/*.png`，配 `"border": true` |
| TLM | **18**（17 被引用） | `touhou_little_maid:textures/book/*.png`（尺寸实测 256×256，其中 `sound.png` 是 257×257） |
| Botania | **15** 张条目图 + **6** 张整页覆盖图 | `botania:textures/gui/entries/*.png`（`azulejos_0..3`、`banner_0..3` 成组）+ `botania:textures/gui/{paper,paper_left,petal_overlay,terrasteel_overlay,mana_infusion_overlay,elven_trade_overlay}.png` |
| IronSpells | **0** | 全靠引擎自带渲染 |

> **进阶手法（Botania `lore_page` 模板）**：用**模板组件**的 `patchouli:image` 把整页纸铺满 ——
> `{"type":"patchouli:image","image":"botania:textures/gui/paper_left.png","x":-5,"y":-10,"width":123,"height":163,"u":13,"v":9}`
> 也就是**逐页替换页面纸底**（左侧用 `paper_left`、右侧用 `paper`、特殊页用 `elven_garde_left` 512×512）。需要"这一页换成另一种纸"时，这是唯一手段。

### 3.4 图标 `icon` — 16×16 或直接写物品 ID

分类和条目的 `icon` 字段接受两种写法（`BookIcon.java:43-56`）：

```jsonc
"icon": "minecraft:compass"                                    // ① 物品 ID（含 NBT/组件语法）
"icon": "wandscape:textures/gui/icons/icon_colony.png"         // ② 以 .png 结尾 → 16×16 贴图，整张 1:1 画
```

- 用物品 ID：**零美术成本**。**6 个参考手册、上千个图标，无一例外全部用物品 ID**（Botania 分类 11 + 条目 224 个去重值；Goety 分类 22 + 条目 361 个去重值；IronSpells 与 TLM 同样 100%）。
- 用自定义贴图：路径必须以 `.png` 结尾，**只画 16×16，不支持多分辨率/缩放**。图标画在分类按钮的 `(x+2, y+2)` 处，即内缩 2px。
- **建议**：优先物品 ID；只有某概念没有对应物品时才出一张 16×16。

### 3.5 音效 — 2 个 ogg（可选）

```jsonc
// book.json
"open_sound": "wandscape:guide_open",
"flip_sound": "wandscape:guide_page"
```

```
assets/wandscape/sounds/guide_open.ogg
assets/wandscape/sounds/guide_page.ogg
assets/wandscape/sounds.json        // 加两条，category 建议 "player"
```

- 翻页音有 6 tick（0.3 秒）节流，且在书内做 `0.7 + rand*0.3` 的随机音高（`GuiBook.java:566`）—— **做单次干净音即可，不做音高变体**。
- 默认音是 `patchouli:book_open` / `patchouli:book_flip`。Wandscape 未设 → 用默认。Botania 自定义了 `botania:lexicon_open` / `lexicon_page`，可作音色参考。

---

## 四、排版规格（字号、行高、边距、分页）

这一节回答「**给文字留多少地方**」和「**排版能玩出什么花样**」，全部来自源码常量。

### 4.1 字号与行高（关键）

| 项 | 值 | 出处 |
|---|---|---|
| 字体 | 默认 `minecraft:uniform`（**平滑字体**，非像素字体） | `Book.java:235` |
| `"use_blocky_font": true` 时 | 去掉该 Style，退回 MC 默认像素字体 | 同上 |
| 字号 | **无独立字段**。就是 MC 默认字号（字高 8px，大写字高约 7px） | — |
| **行高** | **9px**（`TEXT_LINE_HEIGHT = 9`） | `GuiBook.java:58` |
| **正文宽度** | **116px**（= 单页宽） | `BookTextRenderer.java:30` |
| 正文可用高度 | **156px 减去各页型的文字区起点**（见 §4.2） | — |
| 换行 | 按语言规则断词（`BreakIterator.getLineInstance`），中文按字断 | `TextLayouter.java:104` |

按 9px 行高、116px 宽换算：**一页满打满算约 17 行**，中文一行约 **13–14 个全角字**。这就是版面容量。

### 4.2 各页型的文字区起点

`getTextHeight()` 返回文字区距页内顶部的偏移，负数表示上移：

| 页型 | `getTextHeight()` | 含义 |
|---|---|---|
| `text`（首页，第 0 页） | `22` | 标题 + 分隔条占掉 22px |
| `text`（有 `title`） | `12` | 标题行占 12px |
| `text`（无 `title`） | `-4` | 从最顶上 4px 开始塞 |
| `image` | `120` | 图占 `y 7..113`，文字从 120 起 |
| `entity` | `115` | 106×106 框占 `y 7..113` |
| `multiblock` | `115` | 同上 |
| `spotlight` | `40` | 顶托 26px 高（`y 10..36`）+ 标题行 |
| `quest` | `22` | 标题 + 分隔条 + 复选框行 |
| `relations` | `22 + 关联条目数 × 11` | 每个关联条目占 11px 一行 |
| 配方页（`crafting` 等） | 按配方高度动态算 | `PageDoubleRecipe.getTextHeight()` |

### 4.3 版面几何速查（画底图/插图都要用到）

| 元素 | 位置（页内相对坐标） | 尺寸 |
|---|---|---|
| 单页 | 左上角为原点 | 116 × 156 |
| 页标题 | 水平居中，`y = 0` | 高 9px，用 `header_color` |
| 分隔条 | 页内水平居中，`y = 12` | 110 × 3 |
| 条目列表行 | 每行 `y + i × 11` | 116 × 10（悬停时有一道从左展开的深色扫过动画） |
| 搜索框 | 相对内缩 | 99 × 14 |
| **进度条** | `(19, 144)` 起 | **106 × 12**，纯 `fill` 渐变绘制（**无贴图**），标签在其上方 `y = 135` |
| 分类按钮 | 右页 `(151, 43)` 起，4 列 | 20 × 20，列/行步距均 24 |
| 聚光页物品图标 | 页内 `(50, 15)` | 16 × 16 |
| 聚光页底托 | 页内 `(25, 10)` | 66 × 26 |
| 实体/多方块/插图 | 页内 `(5, 7)` | 106 × 106 外框 |
| 着陆页名牌 | 屏幕 `(-8, 12)` | 140 × 31 |
| 着陆页书名 / 副标题 | 屏幕 `(13, 16)` / `(24, 24)` | 9px 行高 |
| 书签页签 | 书右缘外 `x = 272`，`y = 18 + 12i` | 13 × 10；"加书签"页签在 `y = 174` |
| 「标记已读」按钮 | 书右缘外 `x = 272`，`y = 164` | 11 × 11 |

### 4.4 颜色旋钮（不用出图，但在 `book.json` 里，直接决定观感）

```jsonc
{
  "text_color":             "000000",  // 正文
  "header_color":           "333333",  // 页标题、分隔条旁文字、进度条标签
  "nameplate_color":        "FFDD00",  // 着陆页书名
  "link_color":             "0000EE",  // 链接
  "link_hover_color":       "8800EE",  // 链接悬停
  "progress_bar_color":     "FFFF55",  // 进度条前景
  "progress_bar_background":"DDDDDD"   // 进度条底色
}
```

这 7 个默认值是按**浅色纸面**配的深字。若美术把书皮做成深色/彩色纸，这 7 个必须**成组重配**，否则正文不可读。**Wandscape 目前一个都没覆写。**

> 反面教训：`book.json` 里 `"progress_bar_color": "FFDD00"` 这种写法是**错的**——源码是 `0xFF000000 | Integer.parseInt(hex, 16)`，只吃 6 位 RGB。

### 4.5 文本装饰能力清单（排版手法）

帕秋莉没有富文本布局系统，只有一套**行内转义**。全部可用命令（`BookTextParser.java` + 官方 `text-formatting.md`）：

| 命令 | 效果 |
|---|---|
| `$(br)` / `$(br2)`（=`$(p)`） | 1 / 2 个换行 |
| `$()`（=`$(reset)`/`$(clear)`） | **清空整个样式栈**（不是弹出当前样式，见下） |
| `$(l)` / `$(bold)` | 加粗 |
| `$(o)` / `$(italic)` / `$(italics)` | 斜体 |
| `$(m)` / `$(strike)` | 删除线 |
| `$(n)` / `$(underline)` | 下划线 |
| `$(k)` / `$(obf)` | 乱码 |
| `$(li)` `$(li2)` `$(li3)`… | 项目符号，数字越大缩进越深 |
| `$(#rrggbb)` / `$(#rgb)` | 十六进制颜色 |
| `$(0)`–`$(f)` | 原版 16 色 |
| `$(item)` / `$(thing)` | 默认宏：物品色 `#b0b` / 概念色 `#490`（可在 `book.json` 重绑） |
| `$(l:条目id)` / `$(l:分类id)` | 内部跳转链接 |
| `$(l:条目id#锚点名)` | 跳到指定页（目标页要设 `anchor`） |
| `$(l:https://…)` | 外链（点击开系统浏览器） |
| `$(/l)` | 结束链接 |
| `$(t:提示文字)` / `$(/t)` | 悬停提示 |
| `$(k:keybindName)` | 插入按键名（随玩家改键变化） |
| `$(c:/命令)` / `$(/c)` | 点击执行命令 |
| `$(playername)` | 玩家名 |
| `$(nocolor)` | 回到基色 |

**排版能力的边界（画图时要心里有数）**：

- **没有** 表格、图片内联、多栏、缩进块、引用块、等宽字体。表格要用"加粗表头 + `$(li)` 行 + 破折号"凑。
- **没有** 独立字号/字距/行距字段。**唯一能改字号的是溢出模式**（§4.6）。
- **`$()` 是清栈不是弹栈**：嵌套强调（如链接标签里加粗）内层闭合会把外层一起抹掉。生成器已用"重开样式串"处理，**手写 JSON 时要小心**。
- **样式栈下溢会抛异常并渲染成 `[ERROR]`**（不是静默失败）。

**社区实证的排版范式**（Botania 手册，`botania.page.*` 统计）：

```text
$(item)魔力钢锭$(0)    ← 术语上色后用 $(0) 复位到黑色
$(thing)符文祭坛$(0)
```
`$(0)` 出现 **1917 次**、`$(item)` **1286 次**、`$(thing)` **631 次** —— 即"彩色术语 + 立即复位"是这套系统里最主要的排版节奏。Botania 还把宏重绑成了 `"$(item)": "$(1)"`、`"$(thing)": "$(4)"`（深蓝 / 深红）。

### 4.6 溢出模式 = 唯一的"字号"旋钮

`book.json` 的 `"text_overflow_mode"` 四选一：

| 值 | 行为 | 排版含义 |
|---|---|---|
| `resize`（**Wandscape 当前用的**；Goety `black_book` 也用） | 整页文字**等比缩字**直到塞下 | 唯一能"缩字号"的开关 |
| `overflow`（Goety `witches_brew` 用） | 超出部分溢出到下一页 | 内容多时自动多页 |
| `truncate` | 直接砍掉超出基线的行 | 有内容丢失风险 |
| `errorenabled` | 不处理，超出即报错 | 编辑期自检用 |

`resize` 机制（`TextLayouter.layout`）：反复重排并令 `scale = 116 / 实际可用宽度`。**后果：一页字太多会整体变小，但不会换页。** 这直接决定文案长度预算 —— 按 §4.1 的 17 行估算，超了就是字变小，不会多一页。

---

## 五、装饰手法实测（哪些值得抄）

### 5.1 谁出了自绘美术

| 手册 | 自绘书皮 | 自绘配方图集 | 书物品贴图 | 手册专用插图 | 自定义页型 |
|---|---|---|---|---|---|
| Goety `black_book` | **有** 512×256 | 否 | 有 | **29** 张 256×256 | **2** 个模板 |
| Goety `witches_brew` | **有** 512×256 | 否 | 有 | 复用上者 | **3** 个模板 |
| Botania `lexicon` | 否（默认绿皮） | 否 | 有（`botania:lexicon` 模型） | **15 + 6** 张 256×256 | **8** 个模板 |
| TLM `memorizable_gensokyo` | 否 | 否 | 有 16×16 | **18** 张 256×256 | **1** 个模板 |
| IronSpells `iss_guide_book` | 否 | 否 | 有 16×16 | **0** | **0** |
| Wandscape（现状） | 占位 512×256 | 否 | 有 16×16（未接） | 6 张（尺寸错） | 0 |

**两条都成立的路**：

- **IronSpells 路线（零自绘）**：106 个 spotlight + 65 个 crafting 页全靠引擎自带渲染撑版面，只出了 9 张 16×16 物品贴图，**美术总量 = 1 张 16×16**。
- **TLM / Goety 路线（出图换版面）**：TLM 出 18 张插图 + 1 个模板，换来 43 个配方页统一排版；Goety 出 2 张书皮 + 29 张插图 + 5 个模板，换来 2500+ 个模板页。

### 5.2 哪些页型真的在撑版面

| 手册 | 主力页型（次数） |
|---|---|
| **Goety**（跨 5 语言） | `text` 4474、**自定义 `goety:ritual` 1506**、`crafting` 883、`entity` 773、**自定义 `goety:catalyst` 535**、`spotlight` 295、`item` 270、`image` 170、`multiblock` 68、`quest` 35 |
| **Botania**（仅 en_us） | `text` 491、`crafting` 278、**自定义 `botania:mana_infusion` 58**、`item` 54、`petal_apothecary` 43、`crafting_multi` 23、`quest` 20、`brew` 20、`runic_altar` 17、`lore_page` 14 |
| IronSpells（4 语言） | `spotlight` 106、`crafting` 65、`text` 40、`entity` 17、`empty` 8、`multiblock` 4 |
| TLM（en_us） | 自定义 `altar_recipe` 43、`text` 32、`spotlight` 29、`image` 16、`crafting` 3、`link` 3 |

**给美术的推论**：手册的实际观感**大头不是书皮，是 `spotlight` / `crafting` / `entity` / 自定义模板页**。这四种页的视觉大多由**图集槽位**决定（spotlight 用配方图集 `(0,102) 66×26`，crafting 用 `(0,0) 100×62` 等，entity 用书皮 `(405,149) 106×106` 外框）。**优先画这三处，收益远高于精修书皮角落的按钮。**

### 5.3 完整的页型清单（16 种）

| 页型 | 视觉来源 | 需要出图吗 |
|---|---|---|
| `text` | 纯文字 + 分隔条 | 否 |
| `empty` | 页面填充图 | 否（用默认 filler） |
| `image` | **插图 + 可选外框** | **是**（§3.3） |
| `spotlight` | **聚光底托 + 大物品图标** | 底托来自配方图集 |
| `entity` | 运行时渲染实体 + 外框 | 否（外框来自书皮） |
| `multiblock` | 运行时渲染方块结构 + 外框 | 否（同上） |
| `crafting` | 配方 GUI | 来自配方图集 |
| `smelting` / `blasting` / `smoking` / `campfire` | 配方 GUI | 同上（共用槽位 3） |
| `smithing` / `stonecutting` | 配方 GUI | 同上（槽位 4 / 槽位 3） |
| `relations` | 条目列表按钮 | 否 |
| `link` | 正文 + 跳转按钮 | 否 |
| `quest` | 标题 + 分隔条 + 复选框 + 正文 | 否 |

模板组件（`templates/*.json` 里用）9 种：`text`、`item`、`image`、`header`、`separator`、`frame`、`entity`、`tooltip`、`custom`。其中 `frame` 复用书皮 `(405,149)` 的 106×106 外框；`image` 组件**自带 `width`/`height`/`scale`/`x`/`y`/`u`/`v`**，比页级 `image` 灵活得多。

### 5.4 模板 = 自定义页型和整套自制版面（最值得学的装饰手法）

**机制**：`<lang>/templates/<名>.json` 定义一个版面；条目里写 `"type": "<书命名空间>:<名>"` 就调用它（`PageTemplate` 走 `BookTemplate.createTemplate`）。模板里塞 `processor` 字段可以挂 Java 类做变量替换，从而用**同一套版面批量渲染 N 个配方**。

| 手册 | 模板数 | 自定义页型（次数） |
|---|---|---|
| Goety | 5 | `goety:ritual` 1506、`goety:catalyst` 535、`goety:sacrifice` 20、`goety:cauldron` 20、`goety:brazier` 14 |
| Botania | 8 | `botania:mana_infusion` 58、`petal_apothecary` 43、`crafting_multi` 23、`brew` 20、`runic_altar` 17、`lore_page` 14、`elven_trade` 6、`terrasteel` 1 |
| TLM | 1 | `altar_recipe` 43 |
| IronSpells | 0 | — |

**Botania 的模板组件用法**：`image`、`text`、`header`、`item`、`tooltip`、`custom`（`custom` 挂 Java 类 `ManaComponent` / `RotatingRecipeComponent` / `TerraPlateComponent`，做运行时动画渲染）。

**两处对美术有直接影响**：

1. **模板目录是按语言分的**（`<book>/<lang>/templates/`）。Goety 5 语言 = 5 份模板。Wandscape 走"按语言分目录"（`zh_cn/` + `en_us/`），所以模板要**两处都放**。
2. **模板能让某一页铺满自定义纸底**（Botania `lore_page` 的 `paper_left.png` / `elven_garde_left.png` 512×512 整页覆盖，见 §3.3 脚注）。需要"这一页换成另一种纸"时，这是唯一手段。

### 5.5 文本装饰符使用频率（合并 4 个手册）

| 标记 | Botania | Goety | IronSpells | TLM | 说明 |
|---|---|---|---|---|---|
| `$(0)`（黑色复位） | **1917** | 0 | 0 | 0 | Botania 的术语上色复位范式 |
| `$(item)` | **1286** | 0 | 0 | 0 | Botania 重绑为 `$(1)` |
| `$(thing)` | **631** | 0 | 0 | 0 | Botania 重绑为 `$(4)` |
| `$(l:` / `$(/l)` | 339 | 279 | 1 | 4 | 内部/外部跳转链接 |
| `$(p)`（= `$(br2)`） | 341 | 0 | 0 | 0 | 段落 |
| `$()`（复位） | 75 | **341** | 0 | 10 | |
| `$(li)` | 5 | **165** | 0 | 20 | 项目符号 |
| `$(br2)` | 0 | 3 | **62** | 46 | |
| `$(o)`（斜体） | 92 | 3 | 0 | 0 | |
| `$(br)` | 2 | 21 | 0 | 2 | |
| `$(bold)` | 0 | 2 | 0 | 0 | |
| `$(k:...)`（按键） | 3 | 0 | 0 | 0 | |
| `$(#hex)` | **0** | **0** | 0 | 2 | 基本没人用十六进制色 |
| `$(t:...)`（悬停提示） | **0** | 0 | 0 | 0 | 内联提示几乎没人用 |

**可直接抄的排版节奏**：`$(li)` 做要点、`$(br2)` 分段、`$(l:...)` 跳转、`$(item)`/`$(thing)` 给术语上色并**立即 `$(0)` 复位**。十六进制色和悬停提示在实战中几乎没人用。

---

## 六、给美术的产出清单

### 6.1 必做

- [ ] 书皮皮肤 `book.png`，**512×256**，按 §1.3 的 23 类槽位出图；按钮类槽位每个都要出**常态 + 悬停两份**（并排，悬停份在 `u + w`）
- [ ] 书物品贴图 `item/guide_book.png`，**16×16**（已有，可沿用或重画）
- [ ] 决定 §1.4 坑 1：分类按钮槽位画可见底板，还是留纸面（**推荐留纸面**）

### 6.2 按需

- [ ] 正文插图：**256×256**，内容限**左上 200×200**，字高 ≥ 16px（现有 6 张 1376×768 全部作废重画）
- [ ] 配方图集 `crafting.png`，**128×256**（若沿用默认则跳过）
- [ ] 页面填充图 `filler.png`，**128×128**（若沿用默认则跳过）
- [ ] 自定义图标：**16×16**，仅在找不到合适物品 ID 时
- [ ] 开合音效 2 个 ogg

### 6.3 若把书皮做成深色

- [ ] 成组重配 `book.json` 的 7 个颜色字段（§4.4），逐页目视检查对比度

### 6.4 落地注意（工程侧）

1. 贴图放 `src/main/resources/assets/wandscape/textures/gui/guidebook/`，`book.json` 写 `"book_texture": "wandscape:textures/gui/guidebook/book.png"`。
2. **别再跑 `python gen_patchouli.py textures --force`** —— 它会用代码画占位图覆盖正式美术（不带 `--force` 时检测到文件已存在会跳过，是安全的）。
3. 插图放 `assets/wandscape/textures/gui/guidebook/`，md 里用 `![说明](wandscape:path =WxH)` 引用；生成器会转成独立 `patchouli:image` 页（`=WxH` 后缀被帕秋莉忽略，但 md 兜底阅读器仍用它排版，别删）。
4. 分类数控制在 **≤16**（§1.4 坑 2）。
5. 页级 `patchouli:image` **没有**尺寸字段，只有 256×256/左上 200×200 一条路；要自由尺寸就用**模板组件**的 `patchouli:image`（带 `width`/`height`/`scale`/`x`/`y`/`u`/`v`）。
6. 用模板做自定义版面时，模板文件要**同时放 `en_us/` 和 `zh_cn/`** 两处；挂了 `processor` 的模板需要对应的 Java 类（`compat/patchouli` 走 `compileOnly` 门禁）。

---

## 附：源码出处索引

| 结论 | 文件 | 行 |
|---|---|---|
| 图集尺寸 512×256 | `_refs/patchouli/Xplat/src/main/java/vazkii/patchouli/client/book/gui/GuiBook.java` | 258 |
| 双页 272×180 / 页 116×156 / 左 15 / 右 141 / 顶 18 / 行高 9 | 同上 | 51–58 |
| 名牌底条、分隔条、搜索框、锁、状态标记、外框取样坐标 | 同上 | 525–561 |
| 状态标记 u 值 140/148/156 | `client/book/EntryDisplayState.java` | 5–9 |
| 按钮取样 + 悬停 `u+w` | `client/book/gui/button/GuiButtonBook.java` | 43 |
| 分类按钮 `u,v = 页内相对坐标` | `client/book/gui/button/GuiButtonCategory.java` | 35–38, 67 |
| 分类网格 (151,43)、每行 4 个、步距 24、上限 16 | `client/book/gui/GuiBookLanding.java` | 101–102, 127 |
| 各按钮槽位坐标 | `gui/button/GuiButton*.java`、`GuiBookLanding.java` | — |
| 配方图集取样坐标 | `client/book/page/PageCrafting.java`、`PageSmithing.java`、`page/abstr/PageSimpleProcessingRecipe.java` | 33 / 30 / 28 |
| 聚光页底托 `(0, 128-h, 66×h)` | `client/book/page/PageSpotlight.java` | 43 |
| 图片页 200×200 @0.5 | `client/book/page/PageImage.java` | 39–45 |
| 各页型 `getTextHeight()` | `client/book/page/*.java` | — |
| 文本宽 116 / 行高 9 / 基色 | `client/book/gui/BookTextRenderer.java` | 30 |
| `resize` 缩字机制 | `client/book/text/TextLayouter.java` | 61–100 |
| 全部行内命令与宏 | `client/book/text/BookTextParser.java` | 50–210 |
| 7 个颜色字段 + 各默认值 + 字体 | `common/book/Book.java` | 100–115, 232–236 |
| 图集/filler/配方 默认资源名 | 同上 | 39–42 |
| `icon` 两种写法 | `client/book/BookIcon.java` | 43–56 |
| 16 种页型注册名 | `client/book/ClientBookRegistry.java` | 44–61 |
| 9 种模板组件注册名 | `client/book/template/BookTemplate.java` | 32–40 |
| 模板即自定义页型 | `client/book/page/PageTemplate.java` | — |
| 官方排版命令文档 | `_refs/patchouli/web/docs/patchouli-basics/text-formatting.md` | — |
| 官方图片页规格建议 | `_refs/patchouli/web/docs/patchouli-basics/page-types.md` | 76–107 |
| 各模组用量统计 | 见正文各表；数据源为 `_refs/{Botania,Goety,IronSpells,TouhouLittleMaid}` 全库 grep + PNG IHDR 实测 | — |
