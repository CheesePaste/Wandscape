# 手册（Patchouli）× 兜底指南书

> 信息截至 2026-09-10 | Minecraft NeoForge 1.21.1 | 分支 1.21.1
> 方案与分阶段路线见 [plan/guidebook-patchouli-transform.md](plan/guidebook-patchouli-transform.md)；本文只记**已落地实况、映射规则与素材规格**。

- **【何时读】**：改 `guidebook/*.md` 内容、重新生成手册、替换手册美术素材、或接续「跟玩/解锁/入口收口」后续阶段时。
- **【不包含什么】**：玩家向玩法说明（在游戏内手册里）、Patchouli 自身的机制科普（读 `_refs/patchouli`）。

---

## 一、定位：一份内容，两处渲染

```
内容唯一来源（作者只改这里）
    src/main/resources/assets/wandscape/guidebook/{zh_cn,en}/*.md     50 篇 × 2 语（其中 23 篇编进手册，其余只兜底屏可读）
                    │
                    │  gen_patchouli.py（本机跑，生成物提交进仓库）
                    ▼
    data/wandscape/patchouli_books/guide/book.json
    assets/wandscape/patchouli_books/guide/{zh_cn,en_us}/{categories,entries}/**.json

运行时：
  装了 Patchouli  →  帕秋莉手册 wandscape:guide（目录 / 章节 / 条目 / 跳转）
  没装 Patchouli  →  GuidebookScreen + MarkdownParser 直接读同一份 md（只读兜底）
```

**硬约束**：md 是唯一内容来源。**不要**手改 `patchouli_books/**` 下的 JSON——它们会被下次生成整体覆盖。
改内容 = 改 md + 重跑脚本，不存在「改两遍」的路径，两处渲染因此不可能不一致。

---

## 二、生成管线

```bash
python gen_patchouli.py                    # 编译手册 JSON（会先清空上一次的生成物）
python paginate_patchouli_json.py          # 把超长页切分；紧跟在 gen 之后，必跑
python gen_patchouli.py textures           # 生成占位书皮（已存在则跳过）
python gen_patchouli.py textures --force   # 强制覆盖书皮
```

- **两步都不能省**：`gen_patchouli.py` 的 `render_pages` 一节只出一页，长节全靠
  `paginate_patchouli_json.py` 事后按 `$(br2)`/`$(li)` 切分。gen 会**整体重写**所有条目，
  所以只跑 gen 不跑 paginate = 悄悄把整本书退回「一节一巨页」，而且 git diff 里看着像内容变更。
  分页脚本可 `--dry-run` 先看切分计划，`--max-lines` / `--max-chars` 调阈值。
- 脚本只依赖 Python 标准库（含自写的 PNG 编码），不进构建流程，产物提交进仓库可审计。
- 脚本末尾会对每条生成文本做**静态自检**：未知 `$(命令)`、样式栈下溢都会打印警告（帕秋莉对前者原样显示 `$(xxx)`，对后者抛异常渲染 `[ERROR]`）。
- 语言目录映射：md 的 `en` → 帕秋莉的 `en_us`。帕秋莉以 `en_us` 目录为**枚举索引**、其他语言只做覆盖，所以两套目录必须完整生成，不能只放 `zh_cn`。

### 结构清单在脚本里

分类与「条目 → 分类 / 图标 / 排序」写在 `gen_patchouli.py` 的 `CATEGORIES` / `ENTRIES` 两张表里——这是**结构**元数据，不是内容。条目名取各自 md 的 H1，因此不用双语重复维护。

| 分类 id | 中文名 | 条目（md 文件名去掉 `_guide`） |
|---|---|---|
| `contents` | 指南 | index |
| `start` | 新手入门 | intro_0 / intro_0_5 |
| `playstyle` | 玩法主线 | track_tourist |
| `system` | 通用功能 | economy / panel / mages / tourists |
| `magic` | 魔法 | magic_beam / magic_meteor / magic_desperation / magic_enfeeble_field / magic_conversion / magic_petrification / magic_fortification / magic_heal / magic_teleport / magic_revive |
| `items` | 装备与物品 | wand / oath_ring / scepter / magic_compass / warehouse_terminal |

**每条的开头都有一行「怎么做」**：md 里写成 `> 制作：…`，编译后是引用的样式（斜体 + 棕字），
排在条目名下方、正文上方。写「在哪做 + 殖民地等级门槛」，**不写元素成本**——具体价格在合成站
GUI 里现成可见，写进手册只会随平衡调整过期。天生自带或祭坛专属的（传送/复活）就照实写「不需制作」。

`magic` 分类一条魔法一页，正文**照搬 `magic_spells/<id>.json` 的 `description`**——即 JEI 卷轴信息页
（`WandscapeJeiPlugin` 经 `magic.wandscape.<id>.desc` 本地化）那句。`items` 分类把 3 档戒指 /
5 种权杖 / 3 档罗盘各自归并成一条，法杖整族一条（12 支预设不展开）。

两个分类的文案都**同时存在于 md 与 lang**：md 管手册、lang（`magic.wandscape.<id>.desc` /
`item.wandscape.<id>.desc`）管 JEI 信息页，改文案要两边一起改，管线不做同步校验。

「目录页」= `contents` 分类下的 `index_guide` 条目：原 `index_guide.md` 的每节标题成为一页，节内文档链接转成帕秋莉可点击链接，点进去直接跳条目。
首页的《魔法》《装备与物品》链到对应 **分类**（`TITLE_TO_DOC` 的值可以是分类 id，帕秋莉支持链分类），
其余未编进手册的标题（《建筑》《关于我们》等）仍是不可点的纯文本。

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
| `### 三级标题` | 加粗一行 | 当前内容未使用 |

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

### 4.2 正文配图：【占位，待重画】

帕秋莉 `patchouli:image` 页固定 `blit(images[i], x, y, 0, 0, 200, 200)` 再整体 0.5 缩放，即：

- 只取贴图**左上 200×200 区域**；
- 屏幕上只有 **100×100 像素**；
- **没有 width/height 字段**可调。

现有 3 张配图都是 **1376×768**，直接被裁左上角，等于显示不全：

| 源图 | 引用处 | 现状 |
|---|---|---|
| `wandscape:textures/gui/guidebook/road_diagram.png` | `road_guide.md` | 裁左上角 |
| `wandscape:textures/gui/guidebook/magic_editor_diagram.png` | `magic_circle_editor_guide.md` | 裁左上角 |
| `wandscape:textures/gui/guidebook/sample.png` | `test_guide.md` | 裁左上角 |

**重画规格**：**256×256 正方形 PNG**，有效内容严格放在**左上 200×200**（其余 56px 留白可透明），字号要按「最终显示只有 100×100」来定——即文字高度至少 16px 源尺寸才勉强可读。重画后直接覆盖同名文件即可，无需改 md（md 里的 `=WxH` 后缀会被忽略，但 md 阅读器兜底仍用它排版，所以别删）。

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
| **入口收口** | H 键 / `guide_book` 物品 / `/wandscape guide [page]` / 各建筑屏 `?` 帮助**仍直接开 md 兜底屏** | 装 Patchouli 时应改走帕秋莉条目，GuidebookScreen 退居兜底。需要新增 `GuideFacade` + `compat/patchouli`（`compileOnly` 门禁，照 JEI/Curios 模式） |
| **解锁与跟玩** | 无 | 条目 `advancement` 锁定 + `turnin` 待办 + `patchouli:quest` 打勾；需先做 `intro_*` 成就 |
| **配方页** | 12 支法杖现为文本表 | 可换成 `patchouli:crafting` 页自动展示配方并接入 JEI |
| **美术** | 书皮 / 配图 / 模型 / 图标全是占位 | 见 §四 |
| **旧引导删除** | `content/tutorial` + `foundation/ui/tutorial`（HUD 浮层）仍在 | 见方案文档 §5 P2 |

游戏内验证当前进度：`/openbook wandscape:guide`（需要管理员权限）。
