# Tier 4 迁移作业单（v1 · 人工定版）

> 用途：在 IDEA 里逐个执行「Refactor → Move」的作业单。目标包目录已建好（`src/main/java/com/wsteam/wandscape/{api,content/*,foundation/*,compat,impl}`，含 .gitkeep）。
> 依据：`newplan/packages.md` 全局结论表（tier0 实测）。**一个 move = 一次 commit**，做完一个包更新 `status.md`。
> 纪律：移动不改逻辑。只改被搬类的 `package` 声明 + 它处 `import`；不重命名类；被搬类对 `core`/`engine`/`shared` 的 import **原样保留**（等桥层解散时再统一改）。
> 旧包名 = 源码完整顶层名（`com.wsteam.wandscape.xxx`）；目标 = 已建空目录。
> **执行纪律（决策 #7，硬原则）**：归属看语义不看依赖方向——本表已按"它是什么"定版（`raid`→colony 殖民地袭击、`scepter`→items 功能性物品），执行时若发现某个目标归属让你想"但谁依赖它怎么办"，**停下重判，不要改归属**；跨域直接调用正常，被搬类引用其它域直接保留。

## 迁移表（按执行顺序）

| # | 旧位置 | 目标位置 | 动作类型 | 备注 |
|---|--------|---------|---------|------|
| 1 | `com.wsteam.wandscape.ring` | `com.wsteam.wandscape.content.items` | 整树搬 | 纯物品域，最安全先搬 |
| 2 | `com.wsteam.wandscape.wand` | `com.wsteam.wandscape.content.items` | 整树搬 | 纯数据驱动 item |
| 3 | `com.wsteam.wandscape.compass` | `com.wsteam.wandscape.content.items` | 整树搬 | 含 compass/network |
| 4 | `com.wsteam.wandscape.guidebook` | `com.wsteam.wandscape.content.items` | 整树搬 | 含 guidebook/network |
| 5 | `com.wsteam.wandscape.scepter` | `com.wsteam.wandscape.content.items` | 整树搬 | 功能性物品域；含系统层（ScepterMarks/ScepterService/SavedData）——npc/guard 消费它属跨域直接调用，正常 |
| 6 | `com.wsteam.wandscape.element` | `com.wsteam.wandscape.content.element` | 整树搬 | |
| 7 | `com.wsteam.wandscape.production` | `com.wsteam.wandscape.content.production` | 整树搬 | 含 production/{data,internal,client,network} |
| 8 | `com.wsteam.wandscape.tourist` | `com.wsteam.wandscape.content.tourist` | 整树搬 | 含 tourist/{entity,internal,client,network} |
| 9 | `com.wsteam.wandscape.warehouse` | `com.wsteam.wandscape.content.warehouse` | 整树搬 | 含 warehouse/network |
| 10 | `com.wsteam.wandscape.task` | `com.wsteam.wandscape.content.task` | 整树搬 | 含 task/{engine,dsl,pool,scheduler,source,runtime} |
| 11 | `com.wsteam.wandscape.op` | `com.wsteam.wandscape.content.task` | 整树搬 | 并入 task（作 content/task/op 子包） |
| 12 | `com.wsteam.wandscape.magic` | 拆：`SpellItem`→`content/items`，其余→`content/magic` | 跨包拆分 | 见拆分明细 |
| 13 | `com.wsteam.wandscape.overview` | `com.wsteam.wandscape.content.colony` | 整树搬 | 殖民地管理面 |
| 14 | `com.wsteam.wandscape.stats` | `com.wsteam.wandscape.content.colony` | 整树搬 | 殖民地报表，并入 colony |
| 15 | `com.wsteam.wandscape.npc` | `com.wsteam.wandscape.content.npc` | 整树搬 | 含 npc/{entity,internal,data,network,client} |
| 16 | `com.wsteam.wandscape.guard` | `com.wsteam.wandscape.content.npc` | 整树搬 | 并入 npc（combat/defense 语义） |
| 17 | `com.wsteam.wandscape.raid` | `com.wsteam.wandscape.content.colony` | 整树搬 | 殖民地袭击域（目标=殖民地，非 npc）；目前原版袭击、后续可做模组自建袭击 |
| 18 | `com.wsteam.wandscape.road` | `com.wsteam.wandscape.content.road` | 整树搬 | 含 road/{core,algorithm,engine,client,network} |
| 19 | `com.wsteam.wandscape.projection` | `com.wsteam.wandscape.content.building` | 整树搬 | 并入 building（placement 工具层） |
| 20 | `com.wsteam.wandscape.building` | `com.wsteam.wandscape.content.building` | 整树搬 | 含 building/{internal,data,client,network,scanner}；依赖最重，最后搬 |
| 21 | `com.wsteam.wandscape.dataconfig` | `com.wsteam.wandscape.foundation.registry` | 整树搬 | datapack JSON 基建 |

## 拆分明细

### magic（#12）
- **→ `content/items`**：`magic/item/SpellItem`（一个类）
- **→ `content/magic`**：magic 其余全部（含 magic/internal、magic/data、magic/client）

## 暂缓/不迁移

| 旧顶层包 | 处置 | 备注 |
|---------|------|------|
| `core` | 暂缓 | 桥层，后续单独处理 |
| `engine` | 暂缓 | 桥层，后续单独处理 |
| `shared` | 暂缓 | 桥层，后续单独处理 |
| `compat` | 保留 | 三集成保留现状 |
| `command` | 暂缓 | 命令归各域依赖域内结构定后；大量 debug 命令后续删 |
| `mixin` | 暂缓 | 各归各域依赖域内结构定后 |
| `gametest` | 暂缓 | 测试/工具桶 |
| `client`（顶层） | 暂缓 | 仅 `TransportItemEntityRenderer`（依赖 engine.transport），随 engine 拆时走 |

## 执行约定

1. **每步前 `git status` 干净**；一个 move 后立刻 `git add` + commit（`refactor: 迁移 <旧包> → <目标域>`）。
2. IDEA 用「Refactor → Move」（不是剪切粘贴），自动改 import；move 后 `./gradlew build` 绿才提交。
3. 一个整树 move 若子包多，允许拆成同一 commit 内的连续 move，但一个旧包 = 一个 commit。
4. 做完 21 步，全仓 grep `com.wsteam.wandscape.{ring,wand,compass,guidebook,scepter,element,production,tourist,warehouse,task,op,magic,overview,stats,npc,guard,raid,road,projection,building,dataconfig}` 应只剩 `content.*` 引用 + 桥层残留（core/engine/shared 的 import 指向保留）。
5. 每步更新 `newplan/status.md`（打勾 + 记录 build 结果）。
