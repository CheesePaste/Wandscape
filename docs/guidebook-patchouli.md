# 手册（Patchouli）× 兜底指南书

> 信息截至 2026-09-10 | Minecraft NeoForge 1.21.1 | 分支 1.21.1
> 文案怎么写见 [guidebook-writing.md](guidebook-writing.md)；本文只记**已落地实况、映射规则与素材规格**。

- **【何时读】**：改 `guidebook/*.md` 内容、重新生成手册、替换手册美术素材、或接续「跟玩/解锁/入口收口」后续阶段时。
- **【不包含什么】**：玩家向玩法说明（在游戏内手册里）、Patchouli 自身的机制科普（读 `_refs/patchouli`）。

---

## 一、定位：一份内容，两处渲染

```
内容唯一来源（作者只改这里）
    src/main/resources/assets/wandscape/guidebook/{zh_cn,en}/*.md     62 篇 × 2 语（全部编进手册）
                    │
                    │  gen_patchouli.py（本机跑，生成物提交进仓库）
                    ▼
    data/wandscape/patchouli_books/guide/book.json
    assets/wandscape/patchouli_books/guide/{zh_cn,en_us}/{categories,entries}/**.json
    assets/wandscape/guidebook/runtime/{zh_cn,en}.json                （结构清单，两套渲染共用）

运行时：
  装了 Patchouli  →  帕秋莉手册 wandscape:guide（着陆页 / 分类 / 条目 / 跳转）
  没装 Patchouli  →  GuidebookScreen + MarkdownParser 直接读同一份 md（只读兜底，
                     结构读 runtime 清单，因此同样是「着陆页 → 分类 → 条目」三层）
```

**硬约束**：md 是唯一内容来源。**不要**手改 `patchouli_books/**` 或 `guidebook/runtime/**` 下的 JSON——
它们会被下次生成整体覆盖。改内容 = 改 md + 重跑脚本，不存在「改两遍」的路径。

**结构也只有一处**：分类、条目、条目在哪个分类、条目名、正文里《…》指向谁，全写在 `gen_patchouli.py` 的
`CATEGORIES` / `ENTRIES` / `TITLE_TO_DOC` 三张表里，由它发一份**运行期清单**（`guidebook/runtime/<语言>.json`）
给两套渲染共用。帕秋莉那侧的「页名 → 条目 id」曾经是 `PatchouliCompatImpl` 里的一串手写枚举，因此漏过新加的
条目、还和生成器的重复条目规则相反；现在它读同一份清单。兜底那侧同样靠清单才知道有哪些分类，
并据此把正文里的《建筑》改写成可点链接（帕秋莉侧这一步由生成器在编译期完成）。

### 旧 guidebook 已彻底删除

这一版之前写的 15 篇长篇 md（`getting_started` / `npc_guide` / `tourist_guide` / `strategy_guide` /
`overview_guide` / `road_*` / `scanner_guide` / `commands_guide` / `creators_guide` /
`magic_circle_editor_guide` / `creative_scanner_guide` / `test_guide`）**内容过时**，此前只因为
「没装 Patchouli 时兜底屏还能读到」而留在 `guidebook/` 里。

它们**已经删掉**（连同只被它们引用的 3 张配图 `road_diagram` / `magic_editor_diagram` / `sample`，
以及 3 张早已无人引用的 `overview_diagram` / `scanner_diagram` / `scanner_ui`）。留下的唯一一份手册
就是编进书里的这 62 篇。

- 需要旧文里那点内容时**去 git 历史里取**（删除发生在「兜底补齐三层导航」那次提交），别再往 `guidebook/` 里抄回来。
- 旧文里 4 个主题在手册中没有对应页：法阵编辑器、道路工作室/样条编辑器、`/wandscape test` 指令、创作者/API。
  当时的处置是**不迁移**——工具类旁支，等真有人用再按手册文风补写。
- 生成器现在会拦这类问题：md 目录里出现未登记的文档、正文里的《…》不在标题表里、链接指向不存在的文档，
  都会打印警告并以非零码退出（见 §二）。

---

## 二、生成管线

```bash
python gen_patchouli.py                    # 编译手册 JSON + 运行期清单（会先清空上一次的生成物）
python gen_patchouli.py --check            # 只校验：已提交的生成物与当前 md/结构表是否一致（不进游戏就能跑的检查）
python paginate_patchouli_json.py --check  # 分部体检：超容量页 / 奇数页条目 / 残页
python gen_patchouli.py textures           # 生成占位书皮（已存在则跳过）
python gen_patchouli.py textures --force   # 强制覆盖书皮
```

- **分页是生成的一部分，只有一步**：`gen_patchouli.py` 生成完一节就调
  `paginate_patchouli_json.py` 的 `paginate_data` 切页，再把结果写盘。
  历史上这两步是分开跑的，出过事——忘了第二步，仓库里就留下「生成物是长节、分页器没跑」
  的旧状态，而 `--check` 又用同一个分页器在内存里比对，两边一起空转，谁都没发现。
  `paginate_patchouli_json.py` 现在只剩两个手动入口：`--check`（分部体检）和 `--dry-run`（看切分计划）。
- 脚本只依赖 Python 标准库（含自写的 PNG 编码），不进构建流程，产物提交进仓库可审计。
- 脚本末尾会对每条生成文本做**静态自检**：未知 `$(命令)`、样式栈下溢都会打印警告（帕秋莉对前者原样显示 `$(xxx)`，对后者抛异常渲染 `[ERROR]`）。
- 另外三条自检拦住的是「跳转悄悄失效」这类问题：md 目录里有未登记的文档、正文里的《…》不在 `TITLE_TO_DOC` 里、
  md 链接指向不存在的文档/分类。**有任何警告即非零退出**——警告在游戏里会变成纯文本或 404。
- 语言目录映射：md 的 `en` → 帕秋莉的 `en_us`。帕秋莉以 `en_us` 目录为**枚举索引**、其他语言只做覆盖，所以两套目录必须完整生成，不能只放 `zh_cn`。
  运行期清单按 md 语言取（`runtime/zh_cn.json` / `runtime/en.json`），用的是同一套语言目录名。

### 分页规则：一页到底能放多少

帕秋莉的页是**定尺寸**的：`GuiBook.PAGE_WIDTH=116`、`PAGE_HEIGHT=156`、`TEXT_LINE_HEIGHT=9`，
`PageText.getTextHeight()` 决定正文从哪一行起排。换成行数就是这样：

| 页型 | 起排 y | 容量 |
|---|---|---|
| 条目首页（顶部画条目名） | 22px | **14 行** |
| 带 `##` 小节标题的页 | 12px | **16 行** |
| 普通页 | -4px | **17 行** |

每行约 12 个汉字（116px ÷ 9px）。超容量的页会被 `book.json` 的 `text_overflow_mode` 处理，
本书设的是 `resize`，也就是**把整页字号缩小塞进去**——超出多少行字就缩多少，
最狠的一页曾经缩到六成，玩家根本看不清。所以分页器的硬指标是「一页绝不超容量」，
`check_pagination` 在生成期就把超容量的页报成 build 失败。

`resize` 另外两个取值都不能用：`overflow` 会把文字画到书页外面糊在 GUI 上（书 GUI 没有 scissor 裁剪），
`truncate` 在帕秋莉源码里是坏的（拿绝对屏幕 y 去比页面常量 `PAGE_HEIGHT`）。留着 `resize` 只当兜底，
正常情况下永远不触发。

其余规则：

- **`##` 小节 = 逻辑单元，页 = 物理单元**。小节由作者定（中英文共用一套结构），
  分页器只负责把超长的小节按 `（下一页）` → `$(br2)` 段界 → `$(li)` 列表行 → 句号 → 按字硬切
  这几级切点切开。所以写作时让一节落在 9–14 行，页界自然就是语义边界。
- **页数取偶**：帕秋莉左右两页同时展示（`GuiBookEntry` 里 `leftNum = spread*2`、`rightNum = spread*2+1`），
  奇数页会让最后一个跨页的右半空成白纸。分页器在小节之间不动边界的前提下重排小节占几页、
  必要时并节，实在凑不出偶数就记进 `gen_patchouli.py` 的 `PAGINATION_ODD_EXCEPTIONS`（带原因，不再命中会报错要求删掉）。
- **不留残页**：多页条目里任何一页不足 7 行都要再平衡。
- **中文与英文页数可以不同**：英文比中文长约 1.6 倍，同一节在英文侧会被切成两页。
  这是正常的，别为了对齐两边页数去改结构。

### 结构清单在脚本里

分类、「条目 → 分类 / 图标 / 排序」与《…》标题表写在 `gen_patchouli.py` 的
`CATEGORIES` / `ENTRIES` / `TITLE_TO_DOC` 三张表里——这是**结构**元数据，不是内容。
条目名取各自 md 的 H1，因此不用双语重复维护；这三张表同时决定帕秋莉 JSON 与 `guidebook/runtime/<语言>.json` 的形状。
**同一篇 md 登记两次时先出现者胜出**（`building_scanner_guide` 登在 buildings 与 custom 两处，
帕秋莉条目 id 与清单都取 buildings 那份；脚本会断言别名不与分类/条目撞车）。

| 分类 id | 中文名 | 条目（md 文件名去掉 `_guide`） |
|---|---|---|
| `playstyle` | 玩法主线 | index / intro_0 / intro_0_5 / element_level / track_tourist / track_adventure / track_tech / track_diplomacy / tourists |
| `buildings` | 建筑 | buildings / anomaly / townhall / warehouse / workstation / crafting / magic_station / tavern / altar / mage_hut / node / decoration / shop / service / relax / atm / building_scanner |
| `management` | 管理 | panel / panel_build / panel_road / panel_tasks / panel_settings |
| `magic` | 魔法 | mages / casting / advanced_casting / magic_beam / magic_meteor / magic_desperation / magic_enfeeble_field / magic_conversion / magic_petrification / magic_fortification / magic_heal / magic_teleport / magic_revive |
| `items` | 装备与物品 | wand / oath_ring / scepter / magic_compass / warehouse_terminal |
| `custom` | 自定义与数据包 | custom / building_scanner / custom_buildings / custom_packs / custom_elements / custom_recipes / custom_magic / custom_loot / custom_commands |
| `compat` | 联动与兼容 | curios / irons_spells / goety / tlm |
| `about` | 关于我们 | about |

**条目文案怎么写**——只写「做了什么」不写能力清单、开头那行「怎么做」的写法、文风与篇幅——
见 [guidebook-writing.md](guidebook-writing.md)。本文只负责管线与结构，不重复文风约定。

`magic` 分类一条魔法一页，正文**照搬 `magic_spells/<id>.json` 的 `description`**——即 JEI 卷轴信息页
（`WandscapeJeiPlugin` 经 `magic.wandscape.<id>.desc` 本地化）那句。`items` 分类把 3 档戒指 /
5 种权杖 / 3 档罗盘各自归并成一条，法杖整族一条（12 支预设不展开）。

两个分类的文案都**同时存在于 md 与 lang**：md 管手册、lang（`magic.wandscape.<id>.desc` /
`item.wandscape.<id>.desc`）管 JEI 信息页，改文案要两边一起改，管线不做同步校验。

「目录页」= `playstyle` 分类下的 `index_guide` 条目（条目名「概览」）：原 `index_guide.md` 的每节标题成为一页，节内文档链接转成帕秋莉可点击链接，点进去直接跳条目。

**《标题》自动接链**：正文里写成《建筑》《合成站》这样的书名号，标题只要在 `TITLE_TO_DOC` 里有键就转成可点链接。值可以是**分类 id**（帕秋莉支持链分类，《建筑》直接进分类页），也可以是条目 id（生成器补上分类前缀）。**分类描述也走同一套转换**（`CATEGORIES` 里的描述串经 `convert_inline` 处理），所以描述里同样能写《…》。

**改了条目名/分类名却忘了同步 `TITLE_TO_DOC`，书名号会静默降级成纯文本，没有任何告警**——改完自查一遍：生成的 JSON 里凡是《…》都应被 `$(l:…)…$(/l)` 包住。

**`compat` 分类只写玩家看得见的效果**（能做什么、要在哪里装什么、有什么前提），不写内部机制——
正文依据是 `compat/{curios,ironspellbooks,goety,tlm}/` 的实际行为，改兼容代码后这几页要跟着复核。

---

## 三、md → 帕秋莉映射规则

| md 写法 | 帕秋莉产物 | 说明 |
|---|---|---|
| `# 标题`（全文首个） | 条目 `name` | |
| `## 小节` | 新 `patchouli:text` 页 + 页面 `title` | 一页一节 |
| 首个 `##` 之前的正文 | 首页，无 title | 帕秋莉首页画的是**条目名** |
| 首个 `##` 之前无正文 | 该节标题降级为页首加粗行 | 否则标题会被条目名顶掉 |
| `**粗体**` | `$(bold)…$()` | |
| `*斜体*` | `$(italic)…$()` | |
| `~~删除线~~` | `$(strike)…$()` | |
| `` `行内代码` `` | `$(#8a5a2b)…$()` | 帕秋莉没有等宽字体，用颜色区分 |
| `> 引用` | `$(italic)$(#6b5a45)…$()` | 帕秋莉无引用块 |
| `- 项` / `1. 项` | `$(li)项` | **有序列表编号会丢**，顺序靠文档次序 |
| 表格 | 加粗表头行 + `$(li)` 数据行，列间 ` — ` | 帕秋莉无表格 |
| `---` | 丢弃 | 分节已由 `##` 分页承担 |
| 空行 | `$(br2)` | |
| `![alt](wandscape:path/x.png =WxH)` | 独立 `patchouli:image` 页 | **`=WxH` 尺寸后缀被忽略**，见 §四 |
| `[文字](doc.md)`、`[文字](guidebook:doc)` | `$(l:wandscape:doc)文字$(/l)` | 文档 id 与条目 id 一致 |
| `[文字](https://…)` | `$(l:https://…)文字$(/l)` | 外部链接走系统浏览器 |
| `[文字](action:wandscape:overview_mode)` | 降级为纯文本 + 生成警告 | 帕秋莉没有 action 协议 |
| `### 三级标题` | 加粗一行（留在同一页里当小标题） | `about_guide.md` 的制作者分组 |

### 两个必须知道的帕秋莉坑

1. **`$()` 是「清空整个样式栈」，不是「弹出当前样式」。** 于是嵌套强调（引用块里的 `**粗体**`、链接标签里的粗体）在内层闭合后会把外层样式一起抹掉。生成器用 `resume` 机制：内层 `$()` 之后补回外层样式的重开命令串（`convert_inline` 的第三个参数）。
2. **样式栈下溢会抛异常并渲染 `[ERROR]`。** 同一个机制顺带保证了 `$(l:…)` 与 `$(/l)` 的配对——标签内的 `$()` 之后会重新 push 链接样式，结尾 `$(/l)` 弹的是重新 push 的那一层。

---

## 四、素材规格（占位 → 待重画）

> 完整的槽位全表、排版几何与字号规则、各参考手册的装饰手法实测见
> [plan/patchouli-art-asset-spec.md](plan/patchouli-art-asset-spec.md)。本节只保留接入侧的结论。

### 4.1 书皮：`assets/wandscape/textures/gui/guidebook/book.png` 【占位，待重画】

**硬约束**：必须**恰好 512×256**。帕秋莉 `GuiBook.drawFromTexture` 把尺寸硬编码成 `(512, 256)`，没有任何缩放配置项；换了尺寸就会取到错位的贴图。底图透明，GUI 逻辑尺寸 272×180 的双页框画在 `(0,0)`。

替换正式美术时的坐标表（u, v, w, h；**悬停变体 = 同一 v、u + w**）：

| 用途 | u, v | w×h | 美术要求 |
|---|---|---|---|
| 双页背景（左页 x15–131、右页 x141–257、y18–174） | 0, 0 | 272×180 | 内页必须浅色，`book.json` 未覆写字色，文字用默认 `#000000` |
| 着陆页名称牌 | 0, 180 | 140×31 | 画在页头名条位置 |
| 分隔条 | 140, 180 | 110×3 | 横向细线 |
| 搜索框底 | 140, 183 | 99×14 | 内浅外框 |
| 锁图标 | 250, 180 | 16×16 | 条目 `advancement` 未达成时显示 |
| 条目状态：未读 / 待办 / 已完成 | 140 / 148 / 156, 197 | 8×8 | 三个并排小标记 |
| 返回按钮 | 308, 0 | 18×9 | |
| 翻页箭头：上一页 / 下一页 | 272, 10 / 272, 0 | 18×10 | |
| 小箭头（图片页）：上一张 / 下一张 | 272, 27 / 272, 20 | 5×7 | |
| 书签页签 / 加书签页签 | 272, 160 / 272, 170 | 13×10 | 画在书右缘外侧，形如右凸页签 |
| 调整大小 / 编辑器 | 330, 9 / 308, 9 | 11×11 | |
| 进度 / 配置 | 330, 20 / 308, 20 | 11×11 | |
| 历史 / 标记已读 | 330, 31 / 308, 31 | 11×11 | |
| 图片・实体・多方块外框 | 405, 149 | 106×106 | **中空**，只画边框；当前生成物未启用 `border` |

当前占位图由 `gen_patchouli.py textures` 用纯代码绘制（平涂色块 + 简单符号），**不含正式美术**。

### 4.2 正文配图：当前一张都没有

手册正文**现在不含任何配图**：唯一引用过图片的 3 篇旧文（`road_guide` / `magic_circle_editor_guide` /
`test_guide`）连同它们的 3 张图一起删掉了，另外 3 张早已无人引用的
（`overview_diagram` / `scanner_diagram` / `scanner_ui`）也一并清掉，`textures/gui/guidebook/`
下只剩 `book.png`。

要重新加图时的规格（帕秋莉 `patchouli:image` 页固定 `blit(images[i], x, y, 0, 0, 200, 200)`
再整体 0.5 缩放）：**256×256 正方形 PNG**，有效内容严格放在**左上 200×200**（其余 56px 留白可透明），
字号要按「最终显示只有 100×100」来定——即文字高度至少 16px 源尺寸才勉强可读。
md 里的 `![alt](wandscape:path/x.png =WxH)` 尺寸后缀帕秋莉不认（兜底阅读器认）。

### 4.3 物品模型：【占位，待换】

`book.json` 未设 `model`，用帕秋莉默认的 `patchouli:book_brown`（物品栏里是棕色书）。要换成自己的模型，需补 `assets/wandscape/models/item/<id>.json` + 贴图，并设 `"model": "wandscape:<id>"`（帕秋莉会自动加 `item/` 前缀）。

### 4.4 条目与分类图标：【占位，待换】

目前全部用原版物品 id（`minecraft:chest`、`minecraft:bell` 等）或模组现有物品（`wandscape:wand`、`wandscape:element_earth`）当图标，写在 `gen_patchouli.py` 的清单里。帕秋莉的 `icon` 也接受**以 `.png` 结尾的资源路径**（16×16 贴图），将来可以换成专属图标。

---

## 五、book.json 关键字段与取舍

```jsonc
{
  "name": "wandscape.guide_book.name",        // 走 lang key，随语言变
  "landing_text": "wandscape.guide_book.landing",
  "subtitle": "wandscape.guide_book.subtitle", // version 为 "0" 时 subtitle 才按 lang key 解析
  "book_texture": "wandscape:textures/gui/guidebook/book.png",
  "use_resource_pack": true,                   // 1.20 起非 external 书必须为 true，否则直接抛异常
  "show_progress": false,                      // 无成就锁定时进度条恒 0%，先关；做解锁时再打开
  "text_overflow_mode": "resize"               // 超长页缩字号而不是截断
}
```

- **不开 `i18n`**：`i18n: true` 会把条目名和页面正文当 lang key 查表；我们走的是**按语言分目录**（`zh_cn/`、`en_us/`），两者是互补机制，同时用会打架。
- 所有条目 `read_by_default: true`：当前没有完成态追踪，避免出现无意义的「未读」标记。

---

## 六、尚未做（后续阶段）

| 项 | 现状 | 说明 |
|---|---|---|
| **解锁与跟玩** | 无 | 条目 `advancement` 锁定 + `turnin` 待办 + `patchouli:quest` 打勾；需先做 `intro_*` 成就。相关未决点见下表 |
| **配方页** | 12 支法杖现为文本表 | 可换成 `patchouli:crafting` 页自动展示配方并接入 JEI |
| **美术** | 书皮 / 配图 / 模型 / 图标全是占位 | 见 §四 |
| **旧引导删除** | `content/tutorial` + `foundation/ui/tutorial`（HUD 浮层）仍在，已收到 5 步（市政厅 / 仓库 / 存入物品 / 工作站 / 合成订单），与《0，入门》一章内容重合 | 再往下收就只剩「按 V 打开面板」一句话，HUD 可以整体删掉、只留手册那一章 |

**入口收口已完成**：H 键、`guide_book` 物品、`/wandscape guide`、各建筑屏 `?` 全部经
`foundation/ui/guidebook/GuideFacade` 路由——装了 Patchouli 走条目，没装退 `GuidebookScreen` 只读兜底。

### 尚未决的几个问题

原先这些记在 `plan/guidebook-patchouli-transform.md`（方案评估文档，已删）。**已定的两条**：
教程不设领取式奖励（激励回归游戏自身）；内容继续走 md 单源 + 生成器（就是现在的管线）。

| 编号 | 问题 | 现状 |
|---|---|---|
| D3 | 行为类教学步的成就授予「操作玩家」，状态类沿用 `AchievementService` 授予 **founder**；多人服两条语义并存需先定 | 待定 |
| D4 | 无 Patchouli 时的 md 兜底保持**只读**，不模拟锁定 / 打勾 | 待定 |
| D5 | 法杖讲解放哪：新手第一步进新手条目阶梯，还是把 `crafting_guide` 富化成 crafting 页 | 未决 |

游戏内验证当前进度：`/openbook wandscape:guide`（需要管理员权限）。
