# 远程管理面板

文档编号：NEW-14
版本：1.0
状态：远程管理面板 + 小地图 + 远程建造
依赖：01-shared-api

---

## 一、职责边界

- 提供殖民地管理的统一 GUI 界面
- 显示殖民地小地图（俯视图，可缩放）
- 支持远程建造（在面板中选择建筑 → 在小地图上放置）
- 提供所有管理操作：审批任务、查看 NPC、管理建筑、调整队列
- 可通过热键随时打开（默认 `M`，可在按键设置中修改）

**不包含：**
- 面板中所操作数据的实际修改（调用各模块 API）
- 建筑注册和队列（08 模块负责）
- 仓库 GUI（04 模块负责，面板中提供入口按钮）

---

## 二、面板结构

### 2.1 主界面布局

```
┌──────────────────────────────────────────────────────┐
│  Wandscape 殖民地管理                  [关闭] [_][□][×] │
├──────────┬───────────────────────────────────────────┤
│ 页签切换  │                                           │
│           │           殖民地小地图                      │
│ ┌──────┐ │     (俯视图，鼠标滚轮缩放)                    │
│ │ 概览  │ │                                           │
│ │ 建筑  │ │   ┌─□─□─┬───────┬─□─┐                    │
│ │ NPC  │ │   │ 屋 屋 │ 工作站  │ 屋 │                    │
│ │ 任务  │ │   ├─□─┼──┴───────┴─□─┤                    │
│ │ 仓库  │ │   │ ☆ │   市政厅     │                    │
│ └──────┘ │   └───┴──────────────┘                    │
│           │                                           │
│ 信息面板  │  当前选中: [无]                              │
│           │  建筑名称 / NPC名 / 任务信息                 │
│           │                                           │
├──────────┴───────────────────────────────────────────┤
│  操作按钮区: [关停] [重启] [审批] [挂起] [取消] ...       │
└──────────────────────────────────────────────────────┘
```

### 2.2 页签功能

| 页签 | 显示内容 | 操作 |
|------|---------|------|
| 概览 | 三数值、魔力储量、关键物资库存、NPC 总数/工作中/死亡 | 无 |
| 建筑 | 所有建筑列表 + 小地图高亮 | 选中建筑 → 关停/重启/查看队列 |
| NPC | NPC 列表（名称/状态/魔力/法杖） | 选中 NPC → 分配房屋/查看背包 |
| 任务 | 全局任务池（可按状态过滤） | 审批/挂起/取消/调整优先级 |
| 仓库 | 元素储量 + 入口按钮跳转到完整仓库 GUI | 点击 → 打开仓库 GUI |

---

## 三、小地图

### 3.1 渲染

- 以市政厅为中心的俯视图
- 显示殖民地范围内的：
  - 建筑（彩色图标，按类别区分颜色）
  - NPC（小圆点，空闲=绿，工作中=黄，死亡=灰）
  - 地形（简化色块）
- 鼠标滚轮缩放，拖动平移
- 支持点击选中目标

### 3.2 技术实现

```java
public class ColonyMinimap {
    private final int centerX, centerZ; // 市政厅坐标
    private float zoom = 1.0f;
    private int offsetX = 0, offsetZ = 0;

    public void render(GuiGraphics graphics, int x, int y, int width, int height) {
        // 1. 计算可见区域
        // 2. 遍历殖民地内的建筑和 NPC
        // 3. 将世界坐标映射到屏幕坐标
        // 4. 用 GuiGraphics 绘制色块和图标
    }

    public BlockPos screenToWorld(int screenX, int screenY) {
        // 屏幕坐标 → 世界坐标
    }
}
```

---

## 四、远程建造

### 4.1 流程

1. 玩家打开管理面板 → 切换到"建筑"页签
2. 点击"建造新建筑" → 选择建筑类型（从已解锁的 JSON 配置列表中选择）
3. 建筑以半透明预览显示在小地图上
4. 玩家在小地图上点击目标位置 → 在世界中生成该建筑的半透明投影
5. 确认放置 → 建造任务入队市政厅队列（容量 5）
6. 市政厅队列逐个发布建造任务到全局池
7. NPC 接取 → 执行操作 A 放置方块 → 建筑建成

### 4.2 世界投影

建造确认后，在目标位置渲染矩形边界线框（非半透明填充），直到 NPC 完成建造。线框仅勾勒建筑占用的矩形区域边界，告知玩家建造位置即可。完整半透明方块投影属装饰功能，后续版本考虑。

### 4.3 市政厅队列

```java
// TownHallBE
public class TownHallBE extends AbstractWandscapeBE {
    // 队列容量 = 5

    // 玩家通过管理面板调用
    public boolean enqueueBuildingConstruction(String buildingTypeId, BlockPos targetPos) {
        if (taskQueue.size() >= QUEUE_TOWNHALL) return false; // 队列已满

        // 生成建造任务的原子操作序列
        List<AtomicStep> steps = generateBuildSteps(buildingTypeId, targetPos);
        TaskTemplate buildTask = new TaskTemplate(
            BehaviorType.BUILDING,
            getRequiredLevel(buildingTypeId),
            steps
        );
        TaskApi.enqueueBuildingTask(this.getUUID(), buildTask);
        return true;
    }

    private List<AtomicStep> generateBuildSteps(String buildingTypeId, BlockPos pos) {
        BuildingConfig config = BuildingApi.getBuildingConfig(buildingTypeId);
        List<AtomicStep> steps = new ArrayList<>();
        for (BlockOffset offset : config.pattern()) {
            BlockPos target = pos.offset(offset);
            BlockState result = config.getBlockAt(offset);
            Map<ElementType, Long> cost = ElementApi.getBuildCost(result);
            steps.add(new OperationA(target, Blocks.AIR.defaultBlockState(), result, false, cost));
        }
        return steps;
    }
}
```

---

## 五、招募界面

### 5.1 入口

玩家在管理面板"建筑"页签中选中酒馆 → 右侧显示招募界面。

### 5.2 界面布局

```
┌─────────────────────────────────────────┐
│  酒馆 — 招募法师                          │
│                                          │
│  殖民地舒适值: 12    可招募等级上限: 3      │
│                                          │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  │
│  │ 候选人 1  │  │ 候选人 2  │  │ 候选人 3  │  │
│  │ 等级: 2  │  │ 等级: 3  │  │ 等级: 1  │  │
│  │ 生命: 50 │  │ 生命: 55 │  │ 生命: 45 │  │
│  │ 魔力: 140│  │ 魔力: 160│  │ 魔力: 120│  │
│  │ 法强: 1  │  │ 法强: 3  │  │ 法强: 1  │  │
│  │ 自带法杖: │  │ 自带法杖: │  │ 自带法杖: │  │
│  │  无      │  │ 建造法杖  │  │  无      │  │
│  │ [招募]   │  │ [招募]   │  │ [招募]   │  │
│  └─────────┘  └─────────┘  └─────────┘  │
│                                          │
│  [刷新候选人] 消耗: 木×16 土×8             │
│  (剩余冷却: 4:32)                         │
└─────────────────────────────────────────┘
```

### 5.3 交互

- 首次打开 → 自动调用 `TavernApi.getCandidates(tavernId)` 生成 3 人
- 点击 `[招募]` → `TavernApi.recruitCandidate(tavernId, index)` → 任务入队
- 点击 `[刷新候选人]` → `TavernApi.refreshCandidates(tavernId)` → 资源不足时按钮灰显
- 招募冷却中 → 所有 `[招募]` 按钮灰显，显示剩余冷却时间
- 候选人被招募后 → 该卡片变为"已入队"，其余两人仍可选

### 5.4 数据获取

```java
private void refreshTavernPanel(UUID tavernId) {
    this.candidates = TavernApi.getCandidates(tavernId);
    this.comfort = BuildingApi.getColonyComfort(colonyId);
    this.onCooldown = /* 检查冷却 */;
    this.canRefresh = WarehouseApi.hasElements(colonyId, refreshCost);
}
```

---

## 六、不可远程操作

以下操作**不**在管理面板中提供，玩家必须亲自到场：
- 移动或旋转已存在的建筑
- 与 NPC 交换物品
- 手动放置或破坏方块
- 修改多方块结构（如仪式祭坛布局）

---

## 七、技术实现

### 6.1 Screen 类

```java
public class ManagementPanelScreen extends Screen {
    private Tab selectedTab = Tab.OVERVIEW;
    private ColonyMinimap minimap;
    private Widget buildingList;
    private Widget npcList;
    private Widget taskList;

    // 每个 tab 对应一组 widget
    // 小地图在所有 tab 中显示
}
```

### 6.2 数据获取

面板不持有数据，所有数据通过各模块 API 实时查询：

```java
private void refreshData(UUID colonyId) {
    this.comfort = BuildingApi.getColonyComfort(colonyId);
    this.magic = BuildingApi.getColonyMagic(colonyId);
    this.wonder = BuildingApi.getColonyWonder(colonyId);
    this.buildings = BuildingApi.getColonyBuildings(colonyId);
    this.npcs = NpcApi.getColonyNpcs(colonyId);
    this.tasks = TaskApi.getTasksByStatus(colonyId, null); // all tasks
    this.elements = WarehouseApi.getAllElements(colonyId);
}
```

---

## 八、MVP 范围

MVP 实现：
- 概览页签（三数值 + 统计）
- 建筑列表 + 远程建造
- NPC 列表
- 任务列表 + 审批
- 小地图（基础版）
- 建筑页签中入口到仓库 GUI
- 酒馆招募界面（候选人三选一 + 刷新）

---

## 九、独立测试方案

### 单元测试

1. **屏幕坐标 ↔ 世界坐标变换**：缩放和平移后映射正确
2. **页签切换**：每个页签 widget 正确显示/隐藏

### 集成测试

1. 按热键打开面板 → 殖民地为空显示空状态
2. 放置市政厅后 → 面板显示殖民地信息
3. 远程建造 → 选择类型 → 放置位置 → 确认 → 市政厅队列 +1
4. 在世界中看到半透明投影
5. NPC 完成建造 → 建筑出现在世界中 → 投影消失
6. 选中酒馆 → 显示招募界面 → 3 个候选人卡片
7. 点击刷新 → 资源扣除 → 新候选人替换
8. 选择候选人 → 任务入队 → NPC 执行后新 NPC 出现
