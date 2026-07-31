# Goal
让当前的游客寻路支持室内寻路,如交互目标是室内某个点,tourist需要先抵达建筑,然后开门进建筑,达到某个点

# Current situation
对于游客AI： BuildingSavedData.java:207-245 — getTouristInteractionTarget()

这是完全独立的一套逻辑。它使用从中心向外螺旋扫描（第224行），寻找边界框内可站立的地面位置（第234-236行：上方为空气 + 下方为实心方块）。如果找不到可站立位置，则回退到建筑锚点（第244行）。

游客寻路终点 — 如何确定
完整的决策链如下：

第1步 — 建筑选择 (TouristMoveGoal.java:551-678):

收集所有符合条件的建筑（未关闭、结构完整、未访问过、有库存/有空房）
按偏好权重随机抽取（weightedPick()，第798行），使用 类型偏好 × (舒适度+魔法+奇观) 的分数
优先级：夜间且能住酒店 → 商店 → 服务 → 酒店
第2步 — 目标解析 (TouristMoveGoal.java:669):

BlockPos interactionTarget = api.getInteractionTarget(chosen.getBuildingId());
这调用 BuildingSavedData.getTouristInteractionTarget()（见上文），返回建筑内一个可行走的 X/Y/Z 坐标。

第3步 — 导航 (TouristMoveGoal.java:822-868):

beginNavigation(target, speed) 在第822行被调用
先尝试 planRoute(target)（第827行）——使用 RoadRoutingHelper.planNpcWithRoads()，该函数在道路网络上运行Dijkstra最短路径算法（RoadRouter.java），支持最大6格的断桥间隙连接，NPC最大Y步进为1
如果寻路失败（无道路网络、无路径）：回退至原版MC生物导航直接前往目标（第868行）：tourist.getNavigation().moveTo()
第4步 — 抵达判定 (TouristMoveGoal.java:192-195):

double distSqr = pos.distSqr(target);
int interactionRange = getInteractionRange();  // config.interactionRadius > 0 ? 该值 : 3
if (distSqr < interactionRange * interactionRange) → 抵达！

# Current limitation
自定义道路规划器->若找不到->回退原版
自定义道路规划器不支持开门等

# Solution
分层寻路。
宏观寻路（Macro）： 用你现在写的 RoadRouter（图论 Dijkstra + 玩家道路懒加载）。负责从“伐木场大门”寻路到“仓库大门”。保留现在的逻辑,先自定义规划,规划不出来 fallback到原版,此阶段的终点是要抵达建筑的boundary周围寻找落脚点(可螺旋扫描)
微观寻路（Micro）： 用 MC 原生寻路（Vanilla Pathfinding） 或 魔改的短途 3D A*。负责跨越门槛、开门、上楼梯、绕过室内的桌子。(上层完成后立即切换到此层,进行从落脚点到交互精确位置的短距离寻路,支持开门等等,并在到达(或进行中)标记处于室内,便于下一次寻路先到外部再下一次寻路)

# Key insight
Minecraft 原生寻路对门的支持架构
核心组件
类	作用
PathNavigation	抽象基类，管理路径生成、Tick更新、卡住检测、到达判断
GroundPathNavigation	地面生物的导航（你的游客正在使用它！）
WalkNodeEvaluator	核心的方块通行性评估器——判断每个方块能否行走
PathType	枚举了所有通行类型及其 malus(代价)
DoorInteractGoal	检测面前是否有门、是否挡住了路径
OpenDoorGoal	继承上者，自动开门（可选关）
PathType 枚举 — 方块通行类型清单
PathType.java 中定义的全部枚举值：

BLOCKED(-1.0F)        ← 不可通行
OPEN(0.0F)             ← 空气
WALKABLE(0.0F)         ← 可站立地面
WALKABLE_DOOR(0.0F)    ← 门可通行（已标记可通过）
TRAPDOOR(0.0F)         ← 活板门（可站立）
FENCE(-1.0F)           ← 围栏（默认不可过）
LAVA(-1.0F)            ← 岩浆
WATER(8.0F)            ← 水（高代价）
RAIL(0.0F)             ← 铁轨
DOOR_OPEN(0.0F)        ← 门已打开
DOOR_WOOD_CLOSED(-1.0F) ← 木门关闭（默认不可过）
DOOR_IRON_CLOSED(-1.0F) ← 铁门关闭（不可过）
LEAVES(-1.0F)          ← 树叶
DANGER_FIRE(8.0F)      ← 火旁
DAMAGE_FIRE(16.0F)     ← 火中
... (共28种)
方块→PathType 判定逻辑
WalkNodeEvaluator.getPathTypeFromState()（第503-554行）对每种方块的分类：

空气                         → OPEN
活板门/睡莲/大垂滴叶           → TRAPDOOR（0.0F即可通行）
仙人掌/甜浆果                 → DAMAGE_OTHER
蜜块                         → STICKY_HONEY
可可豆                       → COCOA
门(已打开)                   → DOOR_OPEN（0.0F可通行）
门(关闭,木)                  → DOOR_WOOD_CLOSED（-1.0F默认阻塞）
门(关闭,铁)                  → DOOR_IRON_CLOSED（-1.0F默认阻塞）
铁轨                         → RAIL
树叶                         → LEAVES
围栏/墙/关闭的栅栏门           → FENCE（-1.0F默认阻塞）
其他不可通行的方块             → BLOCKED
关键：寻路引擎天然认识所有门、活板门、围栏等，但它只根据 malus 值决定是否可通行。

控制这三个开关决定一切
NodeEvaluator（WalkNodeEvaluator 的父类）有三个关键布尔开关：

// 在 NodeEvaluator.java 中:
protected boolean canPassDoors;      // 能否"穿过"门（即经过门所在的位置）
protected boolean canOpenDoors;      // 能否"打开"门（遇到关着的木门→自动视为可通行）
protected boolean canWalkOverFences; // 能否"跨过"围栏
判定流程（getPathTypeWithinMobBB 第404-436行）：

PathType pathtype = this.getPathType(context, l, i1, j1);
boolean flag = this.canPassDoors();

// 木门关闭 + canOpenDoors=true + canPassDoors=true → 变成 WALKABLE_DOOR
if (pathtype == PathType.DOOR_WOOD_CLOSED && this.canOpenDoors() && flag) {
pathtype = PathType.WALKABLE_DOOR;  // 可通行！
}

// 门已打开 + canPassDoors=false → 阻塞（避免从门缝挤进去）
if (pathtype == PathType.DOOR_OPEN && !flag) {
pathtype = PathType.BLOCKED;
}