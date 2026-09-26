# 指令

> 全部指令都挂在 `/wandscape` 下。查状态的不需要权限，会动到小镇的东西才要管理员；开发调试的那一批统一藏在 `/wandscape test` 里，本页不介绍。

面板上点得到的操作，指令大多也做得到。指令真正补上的是另外两件事：**把数字摊开看**，以及**在小镇卡住时急救**。

## 权限，以及指令认哪个小镇

只读的查询指令（`status`、`list`、`view`）任何人都能用；会改到小镇资产的——增删元素与物品、设等级、发经验、重命名、招募法师、取消或拆除建筑与路段——一律要求管理员权限。开发调试指令另挂一棵 `/wandscape test`，整棵子树要求管理员，普通玩家的补全列表里根本看不到它们。

每条指令都要先确定自己操作哪个小镇，顺序是固定的：**先看你是不是某个小镇的创建者**，是就认那个镇，哪怕你人站在别处；不是创建者时，管理员按脚下位置认镇，普通玩家则直接报「未检测到小镇」。所以在别人的存档里查数据需要管理员——同一个坐标上你看得见房子，指令却会说没有小镇。

## 小镇

| 指令 | 作用 |
|---|---|
| `/wandscape colony status` | 等级、经验、三值、游客与法师人数、在建数量、七种元素存量 |
| `/wandscape colony list` | 服务器上所有小镇的名称、短 id 与等级 |
| `/wandscape colony create <名称>` | 创建小镇（op-2） |
| `/wandscape colony destroy` | 销毁小镇（op-2） |
| `/wandscape colony level <等级>` | 把等级直接设到指定值，受等级上限约束（op-2） |
| `/wandscape colony exp <数量>` | 发放经验，可能顺带升级（op-2） |
| `/wandscape colony name <名称>` | 给小镇改名（op-2） |

`create` 把镇建在你面前 5 格处，潜行时改建成脚下，同时生成 3 名初始法师并放一圈庆祝烟花。**一人只有一个小镇**：你已经有镇时这条指令直接失败，不会建出第二个。

`destroy` 销毁的是**你创建的那个镇**；只有你从没创建过小镇时，才退而按脚下位置找。

## 元素与仓库

| 指令 | 作用 |
|---|---|
| `/wandscape element view` | 查看七种元素的存量 |
| `/wandscape element add <元素> <数量>` | 增加元素（op-2） |
| `/wandscape element remove <元素> <数量>` | 减少元素（op-2） |
| `/wandscape element clear` | 清空七种元素（op-2） |
| `/wandscape warehouse view` | 查看物品的类数、件数与容量占用 |
| `/wandscape warehouse add <物品> <数量>` | 增加物品（op-2） |
| `/wandscape warehouse remove <物品> <数量>` | 减少物品（op-2） |
| `/wandscape warehouse clear` | 清空物品（op-2） |

元素写英文 id：`earth`、`wood`、`water`、`fire`、`metal`、`wind`、`dark`。仓库只管**物品**，元素走 `element`；物品 id 要写完整的，比如 `minecraft:iron_ingot`。

`remove` 数量超过现有存量时会失败，而且**一点都不扣**——不会出现扣到零的半个操作。

## 建筑与道路

| 指令 | 作用 |
|---|---|
| `/wandscape building list [类别]` | 列出小镇建筑：名字、类别、状态、尺寸、坐标与短 id |
| `/wandscape building cancel <建筑id>` | 取消在建建筑并退还材料（op-2） |
| `/wandscape building demolish <建筑id>` | 拆除建筑，掉落进小镇仓库（op-2） |
| `/wandscape road status` | 路网段数、各种状态的数量与铺装总长 |
| `/wandscape road cancel <路段id>` | 撤回在建路段并退还材料（op-2） |

id 不用敲全：`list` 里显示的 8 位短 id 直接抄过来就行，写其中任意一段连续字符也认。

`demolish` 会被拦下来：**市政厅、仓库、工作站这三类，只要还剩最后一座就拆不掉**，免得把殖民地运转离不开的建筑拆没了。

## 法师、游客与酒馆

| 指令 | 作用 |
|---|---|
| `/wandscape npc list [idle]` | 法师名单：等级、空闲还是任务中、血量、蓝量与已装备法术 |
| `/wandscape tourist list` | 游客名单：状态、等级与三条需求 |
| `/wandscape tourist clear` | 清空小镇游客 |
| `/wandscape tavern list` | 酒馆里现有的法师简历 |
| `/wandscape tavern recruit` | 招募一名法师（op-2） |
| `/wandscape guard status` | 守卫区数量、最近的威胁与活跃的守卫任务 |

`tourist clear` 不需要权限，走的是**正常离城**而不是凭空抹掉，游客太多或卡住时用它收场。

`tavern recruit` 每次都要凑齐每种元素，价格在服务端配置里；元素不够时直接失败，不扣任何东西。它招来的是 1 级法师，和小镇等级无关——想要高等级的法师，得去录用简历。

酒馆这两条按**脚下位置**认镇，和你是不是镇主无关：站在小镇范围外，它们会退回到服务器上第一个小镇，而不是报错——想给哪个镇招人，得先走到那个镇里。

## 卡住了怎么办

| 指令 | 作用 |
|---|---|
| `/wandscape recover status` | 任务池与建筑队列的实时统计 |
| `/wandscape recover clear` | 清空任务池与建筑队列，把法师放回空闲 |

`clear` 同样不要权限，代价是**当场作废所有进行中的任务**：法师手里的活全部中断，建筑队列一起清掉。它是在「法师站着不动、什么都不干了」时的最后一招，不是日常维护手段。

## 指南书

| 指令 | 作用 |
|---|---|
| `/wandscape guide [页名]` | 打开游戏内指南书 |

只能由玩家在游戏里执行。不带页名时打开手册首页，给一个页名可以直接跳到那一页：写条目名（`/wandscape guide townhall_guide`）、去掉 `_guide` 的简称（`/wandscape guide warehouse`），或者写分类（`/wandscape guide category:buildings`）都可以。装没装帕秋莉都是同一套页名，装了就开帕秋莉手册，没装就开模组自带的阅读器。

## 装了 Curios 才有

| 指令 | 作用 |
|---|---|
| `/wandscape curios list [目标]` | 列出法师的饰品槽 |
| `/wandscape curios set <槽位> <数量> [目标]` | 把槽位数量设成指定值（op-2） |
| `/wandscape curios add <槽位> <数量> [目标]` | 增加槽位（op-2） |
| `/wandscape curios remove <槽位> <数量> [目标]` | 减少槽位（op-2） |

这组指令只在装了 Curios 时存在。**不写目标就是对全服的法师下手**，不只是你身边那几个；要只改某一位，得把目标写出来。
