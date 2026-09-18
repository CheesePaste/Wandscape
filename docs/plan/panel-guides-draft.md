# 管理面板四个子模式（简介草稿）

> 写于 2026-09-18 | 供审核，通过后再落进 `guidebook/{zh_cn,en}/` 的四篇占位页

四个子模式 = 管理面板左侧按 1/2/3/4 进入的四页，条目文件分别是
`panel_build_guide` / `panel_road_guide` / `panel_tasks_guide` / `panel_settings_guide`，
现均为「本页还没写完」的占位页，登记在 `management` 分类。

写法：

- **建造 / 道路**：交互特殊，把「按住右键转视角、左键旋转、怎么确认、怎么撤销」这套操作写清楚。
- **任务 / 设置**：只按大类说清"管什么、要不要权限"，不列具体条目。
- 例外的、建一次就懂的细节（放下不扣料、没建过镇的少数情况）一律不写。

下面每篇先给 zh，再给 en（两份要一起改，见 `docs/guidebook-writing.md` §七）。
文末附各条的取材依据，便于你核对哪些是真实代码行为。

---

## 1. 建造子模式

文件：`guidebook/zh_cn/panel_build_guide.md`

```markdown
# 建造子模式

> 管理面板打开时按 1 进入，再按一次 1 或 ESC 退出。

## 怎么放

底部建筑栏单击是选中，双击进入放置。之后准心指到哪、建筑的虚影就跟到哪：按住右键拖动转视角，左键把建筑原地转 90 度，点一下右键能把它钉住，钉住以后可以拖它的轴线，或者用右侧面板的 X±/Y±/Z± 按格微调。确认要点两下：先点右侧「提交施工」，再在施工画面里点「提交」；提交完自动回到建筑栏，可以接着放下一个。

建筑框里原有的东西——别座建筑的墙、你自己摆的家具——会被「清理盒内方块」一起清掉。这个开关默认开着，想叠着建就先去右侧关掉它。

## 谁来盖

材料从本镇仓库出，不扣你背包。仓库凑不齐不算失败，建筑会停在「等待材料」，补齐就自己接着盖。施工由本镇法师动手，不用你发布任务或者点开始；镇上一个法师都没有、或者被冻结，就一直不开工。
```

文件：`guidebook/en/panel_build_guide.md`

```markdown
# Build Mode

> Press 1 with the management panel open; press 1 again or ESC to leave.

## Placing

In the bar along the bottom, one click selects a building and a double click starts placing it. From there the ghost follows your crosshair: hold the right button and drag to turn the view, left-click to turn the building 90 degrees, and a quick right-click to pin it. Once pinned you can drag its axes, or nudge it one block at a time with the X±/Y±/Z± buttons on the right. Confirming takes two clicks: Submit Build on the panel, then Submit in the screen that opens; afterwards you are back at the bar, ready to place the next one.

Anything already inside the footprint — another building's wall, furniture you placed — is cleared out with it by Clear box. That switch is on by default, so turn it off on the right before stacking buildings.

## Who builds it

The materials come out of your town warehouse, never your inventory. Running short is not a failure: the site waits at Waiting for Materials and carries on once you stock up. A mage of your town does the work, so there is nothing for you to publish or start; with no mage around, or with the town frozen, work never begins.
```

---

## 2. 道路子模式

文件：`guidebook/zh_cn/panel_road_guide.md`

```markdown
# 道路子模式

> 管理面板打开时按 2 进入，再按一次 2 或 ESC 退出。

## 怎么用

四个工具：「替换」把一片地面换成你选的建材，「填充」往里填一个立方体，「铲平」以你点的那格为准把地面削下去，这三个都是按住左键在世界里拖出范围——按下的那格是起点，拖到哪是哪，松开定格，起点终点上的手柄还能继续拖。拖错了按 Backspace，先清终点、再清起点。确认只能点面板里的按钮，键盘上没有回车。

「样条」是另一套画法：先在「曲线编辑」页左键点方块表面加锚点，切到「选择与拖拽」拖手柄调曲线，再到「阵列生成」页调宽度、厚度、边框，最后下发。

按住右键拖动转视角；WASD 开的是镜头，人还站在原处不动。

## 只有两种工具铺出来的是路

「替换」和「样条」铺完会登记成一条道路，游客才肯沿着它走；「填充」和「铲平」只是改地形的工具，铺出来的不算路，游客看都不看。

## 地形要你自己先整

「替换」只铺在你框的起点那一层，地面不平的话路面会一半悬空、一半埋进土里，得先用「铲平」整平——而「铲平」是真拆，标高以上的树、水、连别人的建筑都会一起拆掉。

材料从本镇仓库出，由法师一格一格铺，不是点一下就成。
```

文件：`guidebook/en/panel_road_guide.md`

```markdown
# Road Mode

> Press 2 with the management panel open; press 2 again or ESC to leave.

## How to use it

Four tools. Replace swaps a patch of ground for the material you picked, Fill pours a solid cube into it, and Flatten cuts the ground down to the block you clicked as a reference. All three are the same in the world: hold the left button and drag out the area — the block you pressed on is the start, wherever you drag to is the end, and releasing fixes it; the handles on the start and end can still be dragged afterwards. Made a mistake? Backspace clears the end first, then the start. Confirming is only ever the button on the panel — there is no Enter key for it.

Spline is drawn differently: on the Curve tab, left-click block surfaces to drop anchor points, switch to Select & Drag to shape the curve by its handles, then set width, thickness and border on the Array tab and submit.

Hold the right button and drag to turn the view. WASD flies the camera and your character stays where it was.

## Only two of them leave a road

Replace and Spline register a real road when they finish, and that is what tourists follow. Fill and Flatten only reshape terrain — what they leave behind is not a road and tourists ignore it.

## Terrain is your job

Replace only lays at the height of the start point you picked, so on uneven ground the surface ends up half floating and half buried. Flatten the ground first — and Flatten really demolishes: trees, water and even someone else's building above the target height all come down with it.

Materials come from the town warehouse, and a mage lays the road block by block rather than all at once.
```

---

## 3. 任务子模式

文件：`guidebook/zh_cn/panel_tasks_guide.md`

```markdown
# 任务子模式

> 管理面板打开时按 3 进入，再按一次 3 或 ESC 退出。

这里分三页：任务大厅列出本镇正在干和等着干的活，工坊流水线按建筑看队列、缺什么料，法师名册看每个法师在忙什么。任务卡上能定位、加急、取消。

刚放下的建筑排在自动补料和补产后面，一开始不动是正常的，想让它快点开工就点「加急」——但加急只对还没派出去的活有用。

取消是把这一步活彻底删掉，不会退回队列，建筑缺的方块要去俯瞰模式点「修复」才补得回来。

名册里给法师开了「跟随」，他就不再接任何任务，活会一直堆在那里。
```

文件：`guidebook/en/panel_tasks_guide.md`

```markdown
# Task Mode

> Press 3 with the management panel open; press 3 again or ESC to leave.

There are three pages: the Task Hall lists what your town is doing and waiting to do, the Workshop Line groups the queues by building and shows what each is short of, and the Mage Roster shows what every mage is up to. A task card can be located, rushed or cancelled.

A building you just placed queues behind automatic restocking and production, so it sitting still at first is normal; rush it if you want it to start sooner. Rushing only helps work that has not been handed out yet.

Cancelling deletes that step for good and does not put it back in the queue — missing blocks on a building only come back through Repair in overview mode.

Turning on Follow for a mage in the roster stops him taking any task at all, and work piles up.
```

---

## 4. 设置中心

文件：`guidebook/zh_cn/panel_settings_guide.md`

```markdown
# 设置中心

> 管理面板打开时按 4 进入，再按一次 4 退出；ESC 只回到俯瞰，面板不关。

设置分六页：「本镇」随这座小镇走，「视效控制」管画面，「城镇经营」管经济与产能，「游客生态」管游客来多少、待多久，「规则防护」管刷怪和误伤这类规则，「建筑包库」用来停用不想用的建筑包。

「本镇」页人人能改，但只改得动创始人是你自己的那座小镇；其余各页要管理员（OP 等级 2）才能改。「视效控制」里标着「客户端」的那几项例外，谁都改得动，而且只影响你自己。

改动立刻生效、自动存盘，不用重进世界也不用重启服务器——只有建筑预览的清晰度和帧率要重开游戏。

在「本镇」页关掉「生成游客」只是不再有新游客来，已经在镇上的会照常逛完自己离开。
```

文件：`guidebook/en/panel_settings_guide.md`

```markdown
# Settings

> Press 4 with the management panel open; press 4 again to leave. ESC only returns you to the overview, it does not close the panel.

Settings sit on six pages: This Town follows the town itself, Visuals covers what you see, Town Economy covers money and output, Tourists covers how many arrive and how long they stay, Rules covers mob spawning and friendly fire, and Package Library lets you switch off building packs you do not want.

This Town is open to everyone, but only for the town you founded yourself; every other page needs an admin (OP level 2). The few entries marked Client under Visuals are the exception — anyone can change those, and they only affect you.

Changes apply immediately and save themselves: no rejoining the world, no server restart — apart from preview resolution and frame rate, which need a game restart.

Turning off tourist spawning on the This Town page only stops new arrivals; tourists already in town finish their visit and leave on their own.
```

---

## 取材依据（供核对，不入手册）

| 写法 | 依据 |
|---|---|
| 建造：双击进放置、按住右键拖动转视角、左键转 90 度、右键单击钉住 | `ProjectionFlightController.handleClicks`（左键 `rotate()`，右键 press 切 `setPinned`）；`OverviewFlightController.onMouseTurn` 只在「光标已抬起 且 按住右键」时转视角；`TutorialRegistry` 类注释「Right-drag rotates the view ONLY inside build/road sub-modes」 |
| 建造：确认两步、提交完回建筑栏 | `BuildPopPanelOverlay.isOverSubmitButton` → `openConstructionScreen`；`ConstructionScreen` 内的「提交」才发 `ProjectionPlacePacket` |
| 建造：清盒默认开、会清掉框内别座建筑与家具 | `gui.wandscape.buildpop.clear_on` 默认 `clearBoxBeforeBuild = true`；`docs/domain-notes.md` §五.5「叠放前务必关清盒」 |
| 建造：材料出仓库、缺料停在等待材料、法师施工 | `EnqueueHelper` 组 `material_list`；`ResourceShortageException` → `AWAITING_RESOURCES`；`BuildingTaskSource` 发布任务、由调度器派法师 |
| 道路：左键拖出起终点、Backspace 逐级清、只能点面板按钮提交 | `RoadPlacementController.onLeftPress` / `handleBackspace`；道路包内无 `KEY_ENTER` |
| 道路：按住右键转视角、WASD 控镜头、人物不动 | `SplineEditorController` 右键 press 才 `cameraActive` 并 grab 鼠标；`onMovementInputUpdate` 清零玩家输入 |
| 道路：只有「替换」「样条」登记成道路 | 全仓 `addEdge` 只命中 `RoadPlacePacket:208` 与 `SplineBuildPacket:149` |
| 道路：替换只铺起点那层；铲平会拆树/水/别人的建筑 | `RoadPlacePacket` 取 `targetY = start.getY()`；`DestroyFillPacket` 拆除循环不按方块类型过滤 |
| 任务：新建筑任务排在补料/补产之后、加急只对未派发的活有效 | 建造走 `EnqueueHelper.buildWorkItem(..., 0)`，低于 RESTOCK 60 / PLAYER 80；`GlobalTaskPool.updatePriority` 只重排 PENDING_ASSIGN |
| 任务：取消不回队列、靠「修复」补 | `GlobalTaskPool.cancelTask` 置 COMPLETED、不退 WorkItem；`BuildingRepairHandler` 入队 `build:place_structure` |
| 任务：「跟随」= 不再接任何任务 | `SchedulerSystem` 空闲候选排除跟随中法师 |
| 设置：六页划分与「客户端」项范围 | `SettingTab` / `SettingsRegistry`（28 项 + 建筑包动态项） |
| 设置：本镇页人人可改但只限 founder | 包体不带 colonyId，服务端 `getColonyByFounder`；`SettingItem.canModify` 对本镇项不查 OP |
| 设置：即时生效，仅预览清晰度/帧率需重启 | 其余项消费点现读 `Config.get()`；此项在 `WandscapeClient` 启动时读一次，登记 `hotReloadable=false` |
| 设置：关游客只拦新增 | `TouristSpawnSystem.createSchedule` + 生成窗口；`docs/domain-notes.md` §二.5 |

## 待你定的一处

**《任务子模式》的条目名**：md 标题是「任务子模式」（en 为 Task Mode），但面板左侧页签名是「任务 (3)」、进去第一个页签才叫「任务大厅」，`panel_guide.md` 里又写成《任务大厅》。要统一的话，改名会同时改掉帕秋莉条目名（条目标题取自 md 的 `#` 行），要不要改？
