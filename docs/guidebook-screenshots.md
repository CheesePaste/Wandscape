# 手册配图：截图清单

> 信息截至 2026-09-24 | Minecraft NeoForge 1.21.1 | 分支 1.21.1
> 配套阅读：[guidebook.md](guidebook.md) §5.3（插图硬规格）、§4.1（分页容量）。

- **【何时读】**：给游戏内手册（帕秋莉指南书）补真实截图时——本文是**逐张的拍摄清单**，说明每张图截哪个界面、从哪进、画面上必须出现什么。
- **【不包含什么】**：手册文案怎么改（见 [guidebook.md](guidebook.md) §2）、语言文件（见 [lang-pipeline.md](lang-pipeline.md)）。

---

## 零、现状：22 张已插入

已就位的截图：`guidebook_screenshots/`（原始，22 个 PNG）。
加工成品：`assets/wandscape/textures/guidebook/`（256×256，`prepare_guidebook_images.py` 生成）。
落位：`insert_guidebook_images.py` 把 `patchouli:image` 页写进条目 JSON，中英各一份。

**图不走 md。** 手写进条目 JSON，由图注分语言、图本身语言无关。生成器认得这些页
（`gen_patchouli.py` 的 `harvest_atomic_pages`），重跑生成不会丢、`--check` 也不会当成残留。

### 0.1 加图暴露的三个缺陷（都已修）

| 缺陷 | 表现 | 修法 |
|---|---|---|
| 分页器丢弃图片页 | `balance_pages` 从文本块重建页面，非文本页不在重建范围内，图与图注**静默消失** | `_split_atomic` 把非文本页摘出为**原子页**：不切块、不进行数统计，但页数算进奇偶，重排后按锚点插回 |
| 生成器抹掉手写页 | `entries/` 整目录清空重建，手写图片页活不过一次生成 | `harvest_atomic_pages` 在清空前收进内存，编译完按原位置插回 |
| 插图翻转页数奇偶 | 一张图占一整页，正文页数必须变奇数才凑得回偶数；正文 15–20 行的条目两头够不着 | `_join_text_pages` 先试「几页正文并成一页」；剩下的记进 `PAGINATION_ODD_EXCEPTIONS`（带原因） |

**遗留**：9 个条目仍是奇数页（`paginate --check` 可见）。其中 7 条是**结构性无解**——
一张图 + 正文必须是奇数页，而正文 15–20 行时 1 页装不下、3 页又填不满。要么删图，
要么增删正文，纯排版走不通。

### 0.2 尺寸现实与高清预览支持

书页文字区只有 **116×156 逻辑像素**、字高 8px。而 `PageImage` 把源图**只取左上
200×200、再拉伸成 100×100**（`blit` 的 UV 分母硬编码 256，故采样 UV 0..0.78125）。所以：

- 截图必须先等比缩放进 200×200 **再摆到 256×256 画布左上角**——作为书页缩略图；
- 整屏截图（591×459 ~ 1093×649）缩进 100×100 后，字高只有 1.5–2.7px，在书页内难以直接阅读小字；
- **解决机制（高清弹窗）**：模组已实现交互式大图预览（`GuideImagePreviewScreen`）。
  无论在帕秋莉手册还是内置 Markdown 阅读器中，鼠标悬停图片时均有金色高亮与「点击查看高清大图」提示；
  **左键点击即可展开全屏半透明高清弹窗**，以原图长宽比和无损分辨率清晰呈现，按 ESC 或点击任意处返回。
  高清原图经无损压缩保存在 `textures/guidebook/full/` 目录下。

---

## 一、拍图与出图规格

帕秋莉 `patchouli:image` 页级是硬规格（出处 `PageImage.java:39-45`，详见 [guidebook.md](guidebook.md) §5.3）：

| 项 | 值 |
|---|---|
| 源图尺寸 | **256×256** 正方形 PNG |
| 有效内容 | **严格画在左上 200×200**，右 / 下各 56px 留透明 |
| 显示尺寸 | **100×100**（GUI 逻辑像素），源图 2:1 下采样 |
| 图占位 | 页内 `x 8..108`、`y 10..110` |
| 图注正文 | `y=120` 起，**只有 4 行**（约 48 个汉字） |

### 1.1 怎么截

**取景 = 100×100 GUI 逻辑像素。** 换算成屏幕像素：在 GUI 缩放 N 下，裁 **100N × 100N** 的屏幕区域。

| GUI 缩放 | 裁多大 | 说明 |
|---|---|---|
| 2 | 200×200 | 裁完 1:1 放进 256 画布，无需缩放 |
| 3 | 300×300 | 裁完缩到 200×200，字仍落在 8px |
| 1 | 100×100 | 会放大 2 倍，字发虚，**别用** |

**必须 GUI 缩放 ≥ 2 拍。** 源图字高要到 16px，下采样后才等于 MC 默认的 8px 字高；缩放 1 拍出来字高只有 8px，缩完变 4px，读不了。最细线宽同样要 ≥ 2px 源。

### 1.2 一图一事

100×100 逻辑像素**比一个完整面板小得多**——塞不进「管理面板全貌」这种东西，塞进去也读不清。**每张图只讲一个控件**：一个按钮、一行槽位、一列标签，四周留一圈上下文即可。凡是想拍「整个界面」的，改成拍「那个界面里读者要找的那一处」。对于必须展示全貌的界面，玩家可通过点击图片调出全屏高清预览阅读。

### 1.3 落位与引用

```
guidebook_screenshots/<名字>.png                                          原始截图工作目录
src/main/resources/assets/wandscape/textures/guidebook/<名字>.png         256x256 采样缩略图（打进 jar）
src/main/resources/assets/wandscape/textures/guidebook/full/<名字>.png    高清原图，供点击放大查看（打进 jar）
```

运行 `python prepare_guidebook_images.py` 会自动完成：
1. 压缩优化高清原图并写入 `textures/guidebook/full/`；
2. 生成 256×256 居中缩略图写入 `textures/guidebook/`。

**引用不写进 md**，直接手写进条目 JSON（`insert_guidebook_images.py` 负责落位）：

```json
{ "type": "patchouli:image",
  "images": ["wandscape:textures/guidebook/xxx.png"],
  "text": "图注，中英各一份" }
```

- 路径形如 `命名空间:textures/...`，实际文件在 `assets/命名空间/textures/...`（实证：Goety 的 `goety:textures/gui/entries/arca.png`）。
- **文件名只能用 `[a-z0-9._-]`**——带 `+`（如 `casting_slots+btn`）不是合法的 `ResourceLocation` 路径，帕秋莉取不到图，兜底阅读器会画成占位块。
- **图注控制在 4 行内（约 48 个汉字）**，超了会挤爆图片页。
- 图注是**正文**，会走 `convert_inline`：`《…》` 在这里也会被接成链接。

---

## 二、清单

### 2.0 已插入的 22 张

| 图片 | 落位条目 | 图注讲的 |
|---|---|---|
| `intro_03_placing` | intro_0 / 一、建造市政厅 | 拖动转镜头、左键转 90 度 |
| `intro_05_naming` | intro_0 / 一、建造市政厅 | 施工结束的命名界面 |
| `intro_06_exchange_tab` | intro_0 / 三、往仓库里放东西 | 仓库交换页签 |
| `intro_07_craft_tab` | intro_0 / 五、下发合成订单 | 工作站合成页签 |
| `elem_overview_tab` | 元素与城镇等级 | 顶部信息栏：等级 + 七种元素 |
| `tourist_three_values` | 游客与三值 / 四个数 | 建筑左上角三值图标 |
| `tourist_detail` | 游客与三值 / 怎么逛 | 游客详情页 |
| `bld_repair` | 建筑维护 | 修复按钮 |
| `townhall_panel` | 市政厅 | 改名 / 等级 / 复活法师 |
| `workstation_panel` | 工作站 | 合成与分解 |
| `crafting_panel` | 合成站 | 发布任务 |
| `tavern_panel` | 酒馆 | 雇佣与简历 |
| `altar_panel` | 祭坛 | 复活仪式 |
| `mage_hut_roster` | 法师小屋 | 小镇法师名单 |
| `node_panel` | 元素节点 | 采集次数 + 发布 |
| `shop_panel` | 商店 | 最大库存 |
| `build_adjust` | 建造子模式 | X±/Y±/Z± 微调 |
| `road_tools` | 道路子模式 | 四个工具 |
| `tasks_tabs` | 任务大厅 | 三个页签 |
| `settings_tabs` | 设置中心 | 六个设置页 |
| `mage_panel` | 法师 | 四个开关 |
| `casting_slots` | 施法 | 策略槽 |

### 2.1 还没拍的

下表是**尚未截图**的位置，按界面族分组。优先级：**P0** = 先拍；**P1** = 定位类控件，读者最容易找不着；**P2** = 锦上添花。

**玩法主线（playstyle）**

| # | 文件名 | 优先 | 从哪进 | 画面上必须出现 |
|---|---|---|---|---|
| 1 | `intro_01_build_tab` | P0 | 按 V 开面板 → 按 1 进【建造】 | 左侧四个子模式列表 + 底部建筑栏，两处同框 |
| 2 | `intro_02_townhall_card` | P0 | 同上，看底部建筑栏 | 【市政厅】卡片本体，**连同卡片上的等级标签** |
| 4 | `intro_04_submit` | P0 | 点【提交施工】后 | 施工画面里的【提交】按钮 |
| 9 | `elem_level_on_card` | P1 | 建造页签的建筑卡片 | 卡片上标等级门槛的那一处（可与 #2 合并成一张） |
| 12 | `adventure_loot_chest` | P1 | 野外找到那种战利品箱子 | 箱子本体 + 附近环境，让读者认得出它（正文叫「野外的战利品箱子」） |

`index_guide` / `intro_0_5_guide` / `track_tech_guide` / `track_diplomacy_guide` **不配图**（目录页、链接列表、未实装内容）。

**建筑（buildings）**

| # | 文件名 | 优先 | 从哪进 | 画面上必须出现 |
|---|---|---|---|---|
| 13 | `bld_outline` | P1 | 开着管理面板，准心对准一座建筑 | **白色包围盒**裹着建筑——这是「能右键了」的唯一信号 |
| 18 | `magic_station_panel` | P1 | 右键魔法工坊 | 可抄写的卷轴清单 |
| 24 | `service_panel` | P2 | 右键服务设施 | 能耗 / 容纳 / 时长三项 |
| 25 | `relax_panel` | P2 | 右键休憩建筑 | 恢复精力 / 次 + 交互时长 |
| 26 | `atm_panel` | P2 | 右键 ATM | 取现上限 / 次 + 交互时长 |
| 27 | `decor_radius` | P2 | 放置或选中一座装饰建筑 | 辐射范围的圈/框——**拍前先确认游戏里真有这个可视化**，没有就撤掉这条 |

**建筑扫描器**（`building_scanner_guide`，全手册 UI 最密的一篇，值得多拍）：

| # | 文件名 | 优先 | 从哪进 | 画面上必须出现 |
|---|---|---|---|---|
| 28 | `scan_range` | P1 | 手持扫描器右键 →【范围结构】页 | Min(小) / Max(大) 两个输入框 |
| 29 | `scan_corner` | P1 | 上面那台设 SAVE，另一台设 CORNER | 模式选择 + 暗号输入框，两处同框；第二张拍【匹配角点】按钮 |
| 30 | `scan_door` | P1 | 扫描器界面 | 【自动检门】按钮 + 已记门方块的列表 |
| 31 | `scan_marker` | P1 | 把交互位标记放在世界里 | 标记在地面上的样子 + 旁边的预览假人 |
| 32 | `scan_identity` | P1 | 扫描器界面 | ID / 显示名称 / 建筑包三个字段 + 建筑包下拉 |
| 33 | `scan_cat_node` | P2 | 类别下拉选「采集节点」 | 界面**多出来的那一块**：元素下拉 + 产出量/次 + 引导 Ticks |
| 34 | `scan_cat_shop` | P2 | 类别下拉选「商店」 | 多出来的那一块：商品行（物品 + 舒/魔/奇）+ 利润% + 时长 Ticks |
| 35 | `scan_export` | P2 | 扫描器界面 | 【扫描区域】与【导出建筑 JSON】两个按钮 |
| 36 | `scan_save_template` | P2 | 包围盒与属性都调好后 | 【存为预设模板】/【加载模板】 |

> 「类别」下拉还有**服务设施**、**休憩**、**ATM** 三档，各自也会让界面多出一块（服务设施：能耗 / 容纳 / 时长 + 元素产出；休憩：恢复精力 / 次 + 交互时长；ATM：取现上限 / 次 + 交互时长）。
> 机制与 33、34 两张同构，**不必各拍一张**——用 33、34 讲清「选对类别才会出现这一块」这件事即可。

**管理（management）**

| # | 文件名 | 优先 | 从哪进 | 画面上必须出现 |
|---|---|---|---|---|
| 37 | `panel_overview` | P1 | 按 V | 左侧四个子模式列表（1 建造 / 2 道路 / 3 任务大厅 / 4 设置中心）——与 #1 是同一处，可复用 |
| 38 | `build_bar_select` | P1 | 建造子模式，看底部建筑栏 | 单击选中态 vs 双击进入放置的提示 |
| 40 | `build_clear_box` | P1 | 放置中，看右侧面板 | 【清理盒内方块】开关（默认开，本篇重点） |
| 42 | `road_range` | P1 | 用替换或铲平拖出一片范围 | 拖出的范围 + **起点终点上的手柄** |
| 43 | `road_spline_edit` | P2 | 样条 →【曲线编辑】页 | 左键点在方块表面加出的锚点 + 拖出的曲线；【选择与拖拽】页拖手柄可并进这张 |
| 44 | `road_spline_array` | P2 | 样条 →【阵列生成】页 | 宽度 / 厚度 / 边框三项 |
| 46 | `tasks_card` | P1 | 任务大厅页 | 一张任务卡上的【定位】【加急】【取消】三处 |
| 47 | `tasks_roster_follow` | P1 | 法师名册页 | 某个法师的【跟随】开关 |
| 49 | `settings_town_page` | P1 | 设置中心 →【本镇】页 | 小镇名 + 「生成游客」开关 |

**魔法（magic）**

| # | 文件名 | 优先 | 从哪进 | 画面上必须出现 |
|---|---|---|---|---|
| 51 | `mage_inventory_btn` | P1 | 法师面板，看预览图 | 预览图**左上角**的背包按钮——位置反直觉，值得一张 |
| 53 | `casting_strategy_btns` | P1 | 策略页顶部 | 四个总体策略：均衡 / 火力 / 支援 / 防御 |

13 篇魔法条目（`magic_*`）正文照搬 JEI 卷轴描述，各只有一句效果说明。**建议本批不配图**：法术是动态效果，缩到 100×100 静态帧往往还不如一句话说得清。若一定要配，只挑视觉最具体的四张——`magic_beam`（光束）、`magic_meteor`（六颗陨石）、`magic_enfeeble_field`（自身周围的力场）、`magic_petrification`（石化后的自身）。

**装备与物品（items）**

| # | 文件名 | 优先 | 从哪进 | 画面上必须出现 |
|---|---|---|---|---|
| 54 | `item_wand_tip` | P2 | 鼠标悬停法杖 | 属性加成行 + 一句「玩家拿着没有加成」的对照 |
| 55 | `item_ring_tip` | P2 | 悬停三档盟誓戒指 | 三档并列，突出「1 名 / 2 名 / 4 名」的差别 |
| 56 | `item_wand_mode_tip` | P2 | 悬停法杖（切到不同模式） | 提示里的「当前模式」行，以及法杖头宝石随模式变的颜色；四种模式各截一张并排对照 |
| 57 | `item_compass_tip` | P2 | 悬停高级/终极指南针 | 提示里的市政厅坐标行 |
| 58 | `item_terminal_curios` | P2 | 仓库终端放进 Curios 手饰槽 | 手饰槽位 + 按键设置里的快捷键项，两处同框 |

**自定义与数据包（custom）**

这一分类绝大多数是 JSON 与数据包写法，**照文档写就行，配图无收益**。只留三张：

| # | 文件名 | 优先 | 从哪进 | 画面上必须出现 |
|---|---|---|---|---|
| 59 | `custom_in_build_list` | P2 | 导入一份建筑 JSON 后 → 建造面板 | 自定义建筑出现在建筑列表里的那一行 |
| 60 | `custom_pack_library` | P2 | 设置中心 →【建筑包库】页 | 建筑包列表 + 停用开关 |
| 61 | `custom_command_status` | P2 | 敲 `/wandscape colony status` | 聊天栏里摊开的那串数字（等级 / 三值 / 人数 / 七种元素存量）——本篇正文说的「把数字摊开看」就是这一处，比拍指令补全列表更贴 |

`custom_guide` / `custom_elements_guide` / `custom_recipes_guide` / `custom_magic_guide` / `custom_loot_guide` **不配图**。`building_scanner_guide` 是同一篇 md（登在 buildings 与 custom 两处），图只出一份。

**联动与兼容（compat）**

| # | 文件名 | 优先 | 从哪进 | 画面上必须出现 |
|---|---|---|---|---|
| 62 | `compat_curios_slots` | P1 | 法师面板 →【饰品栏】按钮 | 按钮本身 + 打开后的饰品槽，两处同框 |
| 63 | `compat_iron_slots` | P1 | 法师策略页 | 铁魔法卷轴所在的那个分类槽 + 法术书槽 |
| 64 | `compat_goety_slots` | P1 | 法师策略页 | 聚晶槽，突出**整个策略栏只有这一个** |

---

## 三、拍摄顺序

第一轮（跑通链路）**已完成**——22 张已插入并验证。补拍时按「一次进游戏能干完一批」组织：

1. **补拍建筑面板**：`magic_station_panel` / `service_panel` / `relax_panel` / `atm_panel` / `decor_radius`
   ——需要一座建齐各类建筑的小镇；缺的用创造模式补。
2. **建筑扫描器**：需另开创造存档，交互位标记只在创造模式物品栏里。
3. **管理面板细部**：`build_bar_select` / `build_clear_box` / `road_range` / `road_spline_*` / `tasks_card` / `tasks_roster_follow` / `settings_town_page`。
4. **联动**：需装齐 Curios / 铁魔法 / 诡厄巫法，单独开一个整合包档。

**补拍的出图流程**：原图丢进 `guidebook_screenshots/` → `python prepare_guidebook_images.py`
→ 在 `insert_guidebook_images.py` 的 `PLACEMENTS` 里加一行（锚点写该页正文里的一个片段）
→ 跑它、再跑 `python gen_patchouli.py`。

## 四、加图后必做的回归

- `python gen_patchouli.py` 重生成，**产物必须提交**（帕秋莉读的是仓库里的 JSON，兜底屏读的是运行时 md，忘了跑两边静默分叉）。
- `python gen_patchouli.py --check` 必须过。
- `python paginate_patchouli_json.py --check`：加图必然改变页数奇偶，很可能撞出新的残页或奇数页条目——撞上了要么调 md 正文长度，要么记进 `PAGINATION_ODD_EXCEPTIONS`（带原因；不再命中时生成器会报错要求删掉）。
- 中英两侧**都要插**，各自翻译图注；英文比中文长约 1.6 倍，两侧页数可以不同，属正常。
- **没进游戏看过就不要说验证过**——`./gradlew build` 只证明能编译，图片路径写错、图片被吃、图注溢出都是运行期才看得见。
