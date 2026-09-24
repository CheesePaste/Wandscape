# 剩余「玩家可见」i18n 硬编码 — 交接单

> 范围：只列**玩家能看见**的未翻译文案。指令回执（`content/command/**`）、日志、
> 配置注释一律不在本单内（见 §C）。
> 本轮已修：法师小屋、共享 `MedievalScreen`、道路工坊面板与预设名、
> `ColonyOwnership.deny` 越权提示。

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
- 当前 2319 键。

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
- **头顶气泡（§A3）最敏感**：气泡框按文本自动撑开，长英文会糊住半个屏幕。
  目标 **≤ 16 个 ASCII 字符**；超了就**别译那一条**（改写得更短、或整条删掉，
  气泡池少一条没有任何功能影响）。

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

## A. 只需补 lang 键（零代码改动）

### A1. 2 个缺失的界面键

键被代码引用了但 lang 里没有，玩家看到的是中文兜底。直接补进两份 lang：

```json
"gui.wandscape.constructionsite.withdraw": "Withdraw",
"gui.wandscape.roadstudio.array_preview": "Enable real-time 3D array preview",
```

对应中文：`"撤回"`、`"预览 3D 阵列生成结果"`。

> 另有 4 个 `message.wandscape.command.magic_*`（`magic_cd_cleared` / `magic_freecast_status` /
> `magic_freecast_toggled` / `magic_mana_filled`）同样缺失，但属**指令回执**，按指示本轮不做。

### A2. 10 个建筑分类键

`CreativeScannerScreen` 的 `CategoryDef` 已经写好了键，只是 lang 没这几条 ——
补上即可，**不用改代码**。补完顺带让建筑信息 tooltip 的分类名也跟着变好（`MedievalScreen` 用的是同一套键）。

| 键 | en | zh |
| --- | --- | --- |
| `category.wandscape.basic` | Basic | 基础建筑 |
| `category.wandscape.government` | Government | 政务市政 |
| `category.wandscape.storage` | Storage | 仓库存储 |
| `category.wandscape.workstation` | Workstations | 工作工坊 |
| `category.wandscape.crafting_station` | Crafting | 物品合成 |
| `category.wandscape.magic_station` | Magic Workshops | 魔法工坊 |
| `category.wandscape.tavern` | Taverns | 冒险酒馆 |
| `category.wandscape.wonder` | Wonders | 奇观奇迹 |
| `category.wandscape.altar` | Elemental Altars | 元素祭坛 |
| `category.wandscape.custom` | Custom | 自定义 |

已有的 6 个别重复加：`node` / `shop` / `service` / `decoration` / `relax` / `atm`。

### A3. 241 个头顶气泡键 —— 最高优先，且长度最敏感

`foundation/ui/bubble/AmbientTextPools.java` 的建筑/游客闲聊气泡**已经全部走
`bubble(key, fallback)`**，键名也是现成的；缺的只是 lang 里的 241 条。

键名派生规则（照抄即可，不用读代码）：

| 池 | 键格式 | 条数 |
| --- | --- | --- |
| 游客 · 无建筑引用 | `bubble.wandscape.tourist.generic.<emotion>.<state>.<idx>` | 90 |
| 游客 · 含 `{building}` 引用 | `bubble.wandscape.tourist.building.<emotion>.<state>.<idx>` | 55 |
| 游客 · 静止 / 睡着 | `bubble.wandscape.tourist.idle.<id 或 sleeping>.<idx>` | 8 |
| 法师（殖民地 NPC） | `bubble.wandscape.npc.<pool>.<idx>` | 53 |

- `<emotion>` = `delighted` / `pleased` / `satisfied` / `neutral` / `disappointed` / `upset`
- `<state>` = `visiting` / `exploring` / `wandering`
- `<pool>` = `idle` / `gathering` / `transforming` / `moving` / `casting` / `transform` /
  `block_interact` / `ritual` / `__fallback__`
- `{building}` 在代码里会被替换成 `%s`，**翻译时原样保留 `{building}` 占位**。

**翻译前先读§0 规矩 2**：气泡 ≤ 16 个 ASCII 字符，装不下就删掉那一条。
下面这份 `键<TAB>现有中文` 表由 `tools/ambient_keys.py` 直接从源码解析生成，
需要重生成就跑 `python tools/ambient_keys.py > tools/ambient_keys.tsv`。

```tsv
bubble.wandscape.tourist.idle.idle.0	休息中
bubble.wandscape.tourist.idle.idle.1	站着发呆
bubble.wandscape.tourist.idle.idle.2	稍微歇会儿
bubble.wandscape.tourist.idle.idle.3	喘口气
bubble.wandscape.tourist.idle.sleeping.0	zzz…
bubble.wandscape.tourist.idle.sleeping.1	睡得真香
bubble.wandscape.tourist.idle.sleeping.2	好梦
bubble.wandscape.tourist.idle.sleeping.3	呼噜…
bubble.wandscape.tourist.generic.delighted.visiting.0	好期待进去看看！
bubble.wandscape.tourist.generic.delighted.visiting.1	听说这里很棒！
bubble.wandscape.tourist.generic.delighted.visiting.2	看起来就让人兴奋
bubble.wandscape.tourist.generic.delighted.visiting.3	这个建筑太棒了
bubble.wandscape.tourist.generic.delighted.visiting.4	迫不及待想进去了
bubble.wandscape.tourist.generic.delighted.exploring.0	这里的风景太美了！
bubble.wandscape.tourist.generic.delighted.exploring.1	真是个漂亮的地方
bubble.wandscape.tourist.generic.delighted.exploring.2	魔法小镇的建设真不错
bubble.wandscape.tourist.generic.delighted.exploring.3	每一步都是风景
bubble.wandscape.tourist.generic.delighted.exploring.4	空气清新，心情舒畅
bubble.wandscape.tourist.generic.delighted.wandering.0	今天心情真好~
bubble.wandscape.tourist.generic.delighted.wandering.1	阳光真舒服
bubble.wandscape.tourist.generic.delighted.wandering.2	真希望每天都这样
bubble.wandscape.tourist.generic.delighted.wandering.3	生活真美好
bubble.wandscape.tourist.generic.delighted.wandering.4	悠闲的时光最珍贵
bubble.wandscape.tourist.generic.pleased.visiting.0	看起来不错的选择
bubble.wandscape.tourist.generic.pleased.visiting.1	进去看看吧
bubble.wandscape.tourist.generic.pleased.visiting.2	这家店看起来不错
bubble.wandscape.tourist.generic.pleased.visiting.3	去逛逛
bubble.wandscape.tourist.generic.pleased.visiting.4	来都来了
bubble.wandscape.tourist.generic.pleased.exploring.0	魔法小镇的街道很整洁
bubble.wandscape.tourist.generic.pleased.exploring.1	空气真好
bubble.wandscape.tourist.generic.pleased.exploring.2	绿化做得不错
bubble.wandscape.tourist.generic.pleased.exploring.3	设计得很用心
bubble.wandscape.tourist.generic.pleased.exploring.4	这座魔法小镇发展得挺好
bubble.wandscape.tourist.generic.pleased.wandering.0	嗯…去哪里好呢
bubble.wandscape.tourist.generic.pleased.wandering.1	稍微走走吧
bubble.wandscape.tourist.generic.pleased.wandering.2	漫步一下
bubble.wandscape.tourist.generic.pleased.wandering.3	享受悠闲时光
bubble.wandscape.tourist.generic.pleased.wandering.4	随心走走
bubble.wandscape.tourist.generic.satisfied.visiting.0	就这家吧
bubble.wandscape.tourist.generic.satisfied.visiting.1	进去逛逛
bubble.wandscape.tourist.generic.satisfied.visiting.2	看起来还行
bubble.wandscape.tourist.generic.satisfied.visiting.3	凑合看看吧
bubble.wandscape.tourist.generic.satisfied.visiting.4	试试这家
bubble.wandscape.tourist.generic.satisfied.exploring.0	还可以
bubble.wandscape.tourist.generic.satisfied.exploring.1	一般般吧
bubble.wandscape.tourist.generic.satisfied.exploring.2	还算干净整洁
bubble.wandscape.tourist.generic.satisfied.exploring.3	没什么大问题
bubble.wandscape.tourist.generic.satisfied.exploring.4	中规中矩
bubble.wandscape.tourist.generic.satisfied.wandering.0	随便走走
bubble.wandscape.tourist.generic.satisfied.wandering.1	不着急
bubble.wandscape.tourist.generic.satisfied.wandering.2	慢悠悠地逛
bubble.wandscape.tourist.generic.satisfied.wandering.3	溜达溜达
bubble.wandscape.tourist.generic.satisfied.wandering.4	四处看看
bubble.wandscape.tourist.generic.neutral.visiting.0	去看看有什么
bubble.wandscape.tourist.generic.neutral.visiting.1	随便看看
bubble.wandscape.tourist.generic.neutral.visiting.2	打发下时间
bubble.wandscape.tourist.generic.neutral.visiting.3	路过看看
bubble.wandscape.tourist.generic.neutral.visiting.4	来都来了
bubble.wandscape.tourist.generic.neutral.exploring.0	嗯，没什么特别的
bubble.wandscape.tourist.generic.neutral.exploring.1	就这样吧
bubble.wandscape.tourist.generic.neutral.exploring.2	普普通通
bubble.wandscape.tourist.generic.neutral.exploring.3	没什么好看的
bubble.wandscape.tourist.generic.neutral.exploring.4	到处都一样
bubble.wandscape.tourist.generic.neutral.wandering.0	走一走
bubble.wandscape.tourist.generic.neutral.wandering.1	没什么事做
bubble.wandscape.tourist.generic.neutral.wandering.2	闲逛一下
bubble.wandscape.tourist.generic.neutral.wandering.3	随便走走
bubble.wandscape.tourist.generic.neutral.wandering.4	打发时间
bubble.wandscape.tourist.generic.disappointed.visiting.0	希望这家别太差
bubble.wandscape.tourist.generic.disappointed.visiting.1	唉，试试看吧
bubble.wandscape.tourist.generic.disappointed.visiting.2	不太抱期望
bubble.wandscape.tourist.generic.disappointed.visiting.3	随便看看吧
bubble.wandscape.tourist.generic.disappointed.visiting.4	希望不要踩雷
bubble.wandscape.tourist.generic.disappointed.exploring.0	不太有意思
bubble.wandscape.tourist.generic.disappointed.exploring.1	没什么好看的
bubble.wandscape.tourist.generic.disappointed.exploring.2	有点无聊
bubble.wandscape.tourist.generic.disappointed.exploring.3	也就这样了
bubble.wandscape.tourist.generic.disappointed.exploring.4	浪费时间
bubble.wandscape.tourist.generic.disappointed.wandering.0	有点无聊
bubble.wandscape.tourist.generic.disappointed.wandering.1	想回去了
bubble.wandscape.tourist.generic.disappointed.wandering.2	没什么意思
bubble.wandscape.tourist.generic.disappointed.wandering.3	不如早点回去
bubble.wandscape.tourist.generic.disappointed.wandering.4	唉……
bubble.wandscape.tourist.generic.upset.visiting.0	最好别让我失望！
bubble.wandscape.tourist.generic.upset.visiting.1	算了，看看吧
bubble.wandscape.tourist.generic.upset.visiting.2	哼，就看看
bubble.wandscape.tourist.generic.upset.visiting.3	最后一次机会
bubble.wandscape.tourist.generic.upset.visiting.4	还能更差吗
bubble.wandscape.tourist.generic.upset.exploring.0	什么破地方
bubble.wandscape.tourist.generic.upset.exploring.1	一点都不好
bubble.wandscape.tourist.generic.upset.exploring.2	真没意思
bubble.wandscape.tourist.generic.upset.exploring.3	太失望了
bubble.wandscape.tourist.generic.upset.exploring.4	再也不来了
bubble.wandscape.tourist.generic.upset.wandering.0	烦死了
bubble.wandscape.tourist.generic.upset.wandering.1	不想逛了
bubble.wandscape.tourist.generic.upset.wandering.2	心情都被破坏了
bubble.wandscape.tourist.generic.upset.wandering.3	糟透了今天
bubble.wandscape.tourist.generic.upset.wandering.4	想回家了
bubble.wandscape.tourist.building.delighted.visiting.0	上次在{building}体验很好，看看这家！
bubble.wandscape.tourist.building.delighted.visiting.1	自从去了{building}就爱上这里了
bubble.wandscape.tourist.building.delighted.visiting.2	希望这家和{building}一样棒
bubble.wandscape.tourist.building.delighted.visiting.3	{building}给我留下了深刻印象
bubble.wandscape.tourist.building.delighted.exploring.0	比起{building}那边，这也不差！
bubble.wandscape.tourist.building.delighted.exploring.1	从{building}过来，这边也很不错
bubble.wandscape.tourist.building.delighted.exploring.2	{building}那边好看，这边也不赖
bubble.wandscape.tourist.building.delighted.wandering.0	在{building}玩得很开心，继续逛逛
bubble.wandscape.tourist.building.delighted.wandering.1	刚从{building}出来，心情大好
bubble.wandscape.tourist.building.delighted.wandering.2	回味着{building}的美好体验
bubble.wandscape.tourist.building.pleased.visiting.0	听说{building}不错，这个应该也行
bubble.wandscape.tourist.building.pleased.visiting.1	上次去了{building}感觉挺好，再看看这家
bubble.wandscape.tourist.building.pleased.visiting.2	跟{building}差不多的话就很满意了
bubble.wandscape.tourist.building.pleased.exploring.0	从{building}出来走走，挺舒服
bubble.wandscape.tourist.building.pleased.exploring.1	逛完{building}再来这边，心情不错
bubble.wandscape.tourist.building.pleased.exploring.2	{building}那边逛完了，继续探索
bubble.wandscape.tourist.building.pleased.wandering.0	刚才在{building}收获不错
bubble.wandscape.tourist.building.pleased.wandering.1	去过{building}了，到处转转
bubble.wandscape.tourist.building.pleased.wandering.2	在{building}度过了愉快的时光
bubble.wandscape.tourist.building.satisfied.visiting.0	这家和{building}差不多，试试
bubble.wandscape.tourist.building.satisfied.visiting.1	之前去{building}还行，这家应该也凑合
bubble.wandscape.tourist.building.satisfied.visiting.2	跟{building}差不多档次吧
bubble.wandscape.tourist.building.satisfied.exploring.0	比{building}差不太多
bubble.wandscape.tourist.building.satisfied.exploring.1	和{building}那边差不多吧
bubble.wandscape.tourist.building.satisfied.exploring.2	逛完{building}过来，都差不多
bubble.wandscape.tourist.building.satisfied.wandering.0	刚刚那家{building}还行
bubble.wandscape.tourist.building.satisfied.wandering.1	去过{building}了，四处溜达
bubble.wandscape.tourist.building.satisfied.wandering.2	在{building}出来走走消化一下
bubble.wandscape.tourist.building.neutral.visiting.0	{building}都去了，这家也看看吧
bubble.wandscape.tourist.building.neutral.visiting.1	顺便逛逛这家，跟{building}一样
bubble.wandscape.tourist.building.neutral.visiting.2	从{building}过来，顺便看看
bubble.wandscape.tourist.building.neutral.exploring.0	和{building}那边差不多
bubble.wandscape.tourist.building.neutral.exploring.1	逛完{building}没什么特别感觉
bubble.wandscape.tourist.building.neutral.exploring.2	跟{building}一样普普通通
bubble.wandscape.tourist.building.neutral.wandering.0	刚从{building}出来，散散步
bubble.wandscape.tourist.building.neutral.wandering.1	去了趟{building}，随便走走
bubble.wandscape.tourist.building.neutral.wandering.2	从{building}出来透透气
bubble.wandscape.tourist.building.disappointed.visiting.0	比{building}还差就不好了
bubble.wandscape.tourist.building.disappointed.visiting.1	{building}就挺失望了，这家…
bubble.wandscape.tourist.building.disappointed.visiting.2	希望比{building}强一点吧
bubble.wandscape.tourist.building.disappointed.exploring.0	还没有{building}那边有意思
bubble.wandscape.tourist.building.disappointed.exploring.1	跟{building}一样让人失望
bubble.wandscape.tourist.building.disappointed.exploring.2	{building}不行，这边也够呛
bubble.wandscape.tourist.building.disappointed.wandering.0	连{building}都不怎么样
bubble.wandscape.tourist.building.disappointed.wandering.1	在{building}就不太开心
bubble.wandscape.tourist.building.disappointed.wandering.2	{building}让人失望，逛街心情都没了
bubble.wandscape.tourist.building.upset.visiting.0	希望比{building}好一点
bubble.wandscape.tourist.building.upset.visiting.1	{building}已经够差了
bubble.wandscape.tourist.building.upset.visiting.2	别跟{building}一样就行
bubble.wandscape.tourist.building.upset.exploring.0	跟{building}一样差劲
bubble.wandscape.tourist.building.upset.exploring.1	比{building}还差，服了
bubble.wandscape.tourist.building.upset.exploring.2	从{building}出来心情就不好
bubble.wandscape.tourist.building.upset.wandering.0	再也不去{building}那种地方了
bubble.wandscape.tourist.building.upset.wandering.1	在{building}受够了
bubble.wandscape.tourist.building.upset.wandering.2	{building}的体验太糟糕了
bubble.wandscape.npc.idle.0	休息一下
bubble.wandscape.npc.idle.1	今天也挺忙的
bubble.wandscape.npc.idle.2	歇会儿
bubble.wandscape.npc.idle.3	站着发呆
bubble.wandscape.npc.idle.4	待会儿再干
bubble.wandscape.npc.idle.5	忙碌的一天啊
bubble.wandscape.npc.idle.6	嗯…想想下一步
bubble.wandscape.npc.gathering.0	加把劲
bubble.wandscape.npc.gathering.1	材料还不少
bubble.wandscape.npc.gathering.2	这是好东西
bubble.wandscape.npc.gathering.3	收获不错
bubble.wandscape.npc.gathering.4	再采一点
bubble.wandscape.npc.gathering.5	今天的成果不错
bubble.wandscape.npc.gathering.6	这片区域资源丰富
bubble.wandscape.npc.transforming.0	快完成了
bubble.wandscape.npc.transforming.1	完美
bubble.wandscape.npc.transforming.2	一砖一瓦
bubble.wandscape.npc.transforming.3	结构稳固
bubble.wandscape.npc.transforming.4	尺寸刚好
bubble.wandscape.npc.transforming.5	接下来是这边…
bubble.wandscape.npc.transforming.6	就差一点了
bubble.wandscape.npc.moving.0	该去工作了
bubble.wandscape.npc.moving.1	去那边看看
bubble.wandscape.npc.moving.2	还有活要干
bubble.wandscape.npc.moving.3	走起
bubble.wandscape.npc.moving.4	不能闲着
bubble.wandscape.npc.moving.5	下一站
bubble.wandscape.npc.moving.6	时间不等人
bubble.wandscape.npc.casting.0	魔力汇聚…
bubble.wandscape.npc.casting.1	就是现在！
bubble.wandscape.npc.casting.2	法术释放
bubble.wandscape.npc.casting.3	感受元素的力量
bubble.wandscape.npc.casting.4	能量充盈
bubble.wandscape.npc.casting.5	就是这种感觉
bubble.wandscape.npc.casting.6	集中…
bubble.wandscape.npc.transform.0	转化开始
bubble.wandscape.npc.transform.1	物质重组
bubble.wandscape.npc.transform.2	炼金术的奥妙
bubble.wandscape.npc.transform.3	变了变了
bubble.wandscape.npc.block_interact.0	这个方块…
bubble.wandscape.npc.block_interact.1	让我看看
bubble.wandscape.npc.block_interact.2	这就是目标
bubble.wandscape.npc.block_interact.3	找到了
bubble.wandscape.npc.ritual.0	仪式进行中
bubble.wandscape.npc.ritual.1	古老的力量
bubble.wandscape.npc.ritual.2	遵从契约
bubble.wandscape.npc.ritual.3	元素共鸣
bubble.wandscape.npc.ritual.4	法力在流动…
bubble.wandscape.npc.__fallback__.0	这座魔法小镇真不错
bubble.wandscape.npc.__fallback__.1	环境宜人
bubble.wandscape.npc.__fallback__.2	继续努力
bubble.wandscape.npc.__fallback__.3	日子一天天过
bubble.wandscape.npc.__fallback__.4	希望一切顺利
```

---

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

（第 66-81 行的分类名与第 90-96 行的元素名是键缺失/已存在的**兜底串**，见 §A2 与 §1，不要在这里重复改。）

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

---

## D. 收尾

1. 两份 lang 必须同时改、键集完全一致、保持字母序。
2. 改完跑 `./gradlew build`（唯一门槛）。
3. 自检脚本（本机已有，`tools/` 已 gitignore）：
   - `python tools/i18n_audit.py <文件.java>` —— 列某个文件里没走进 `I18n` 的中文串
   - `python tools/i18n_sweep.py` —— 全项目扫描 + 列出「代码引用了但 lang 没有」的键
   两个脚本都会把 `I18n.name(key, "兜底")` 的兜底串误报成硬编码，读到先看§规矩 3。
