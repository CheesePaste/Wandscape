# 道路系统重构方案

## 背景

当前 V 面板 → Road 模式功能瘫痪：绿色路面覆盖不渲染、右键删路不生效。与其修，彻底重做为**两点一线铺设工具**——选择预设（可单方块或权重随机多方块），右键定起点，左键定终点，Enter 发布，NPC 沿路径替换地表方块。**不渲染路网、不右键删路、无清空步骤、无装饰物**。

**道路数据模型**（RoadNetwork, RoadEdge, RouteSegment 等）和**自动规划**保留——游客系统、NPC 寻路、物品运输继续依赖它们。

---

## 交互流程

```
状态机：
  IDLE       → 右键点击地面 → PLAN_START（设置起点）
  PLAN_START → 左键点击地面 → PLAN_END（设置终点，显示完整路径预览）
  PLAN_START → 右键点击地面 → PLAN_START（覆盖起点）
  PLAN_START → Backspace    → IDLE（清除起点）
  PLAN_END   → 左键点击地面 → PLAN_END（覆盖终点）
  PLAN_END   → 右键点击地面 → IDLE（清除全部）
  PLAN_END   → Backspace    → PLAN_START（回到起点模式）
  PLAN_END   → Enter        → 提交 → IDLE
```

**预设选择**：C 键抬起鼠标 → 点击预设面板中的方块。
**预设类型**：可以是单一方块（如 `minecraft:water`），也可以是权重随机多方块（如当前路面 palette：圆石权重 5、砂砾权重 3、石砖权重 2）。

---

## 核心变更

| 项目 | 旧行为 | 新行为 |
|------|--------|--------|
| 交互 | 左键设点+右键删路+Enter发布 | **右键起点→左键终点→Enter发布** |
| 部署流程 | 先放空气清空，后 NPC 铺路 | NPC **直接替换**地表方块 |
| 路径 | L 形，Y 预计算 | L 形，**Y 取地表高度** |
| 宽度 | 滚轮 1-9 奇数 | 固定 1 格宽（单线） |
| 预设 | TOML palette（3 种随机） | 灵活：单方块 / 权重随机多方块 |
| 预览 | 路网覆盖 + 节点 + 路径线 | 两点标记 + 路径方块预览 |
| 装饰物 | 路灯/长椅（`DecorationBuilder`） | 无 |
| 右键删路 | `RoadEdgeRemovePacket` | 无 |

---

## 文件变更总览

| 类别 | 数量 |
|------|------|
| 删除（旧编辑器/客户端） | 11 |
| 删除（装饰物） | 3 |
| 删除（命令） | 2~3 |
| 删除（测试） | 6 |
| **删除合计** | **~22** |
| **新建** | **6 文件**（状态/控制器/渲染/覆层/数据包/预设） |
| **修改** | **8 文件** |

> 不新建蓝图——复用已有的 `road:build_segment` JSON，只需传入不同 tiles。

---

## 一、删除清单

### 客户端
| 文件 | 原因 |
|------|------|
| `road/client/RoadProjectionClientState.java` | 旧投影状态（路径点列、路段列、路宽、Y偏移） |
| `road/client/RoadProjectionController.java` | 旧投影输入（左键设点、右键删路、滚轮宽、PgUp/Dn高） |
| `road/client/RoadProjectionRenderer.java` | 旧投影渲染（路网着色、节点盒、路径线） |
| `road/client/RoadEditorClientState.java` | V1 编辑器死代码 |
| `road/client/RoadEditorRenderer.java` | V1 编辑器死代码 |

### 网络包
| 文件 | 原因 |
|------|------|
| `road/network/RoadBatchPublishPacket.java` | 旧批量发布 |
| `road/network/RoadEdgeRemovePacket.java` | 旧右键删边 |
| `road/network/RoadEdgePlanPacket.java` | 旧路径规划 |
| `road/network/RoadEditorTogglePacket.java` | 旧编辑器切换 |
| `road/network/RoadEditorNetwork.java` | 旧编辑器玩家管理 |
| `road/network/RoadNetworkSyncPacket.java` | 客户端不再需路网可视化 |

### 服务端
| 文件 | 原因 |
|------|------|
| `road/server/RoadEditorHandler.java` | 旧编辑器服务端逻辑 |

### 装饰物
| 文件 | 原因 |
|------|------|
| `road/core/DecorationPoint.java` | 路灯/长椅数据 |
| `road/algorithm/DecorationPlanner.java` | 装饰物规划 |
| `road/engine/DecorationBuilder.java` | 装饰物执行 |

### 命令
| 文件 | 原因 |
|------|------|
| `command/RoadCommand.java` | 旧调试命令 |
| `command/RoadTestCommand.java` | 旧测试命令 |
| `command/SpiralTestCommand.java` | 如含道路引用一并删除 |

### 测试
`src/test/java/.../road/` 下 6 个文件全部删除。

---

## 二、新建——新道路铺设系统（6 文件）

### 2a. `road/data/RoadPreset.java`

```java
public record RoadPreset(String id, String displayName, List<WeightedEntry> blocks) {
    public record WeightedEntry(String blockId, int weight) {}

    /** 位置确定性权重选取（单方块时直接返回） */
    public String pickBlock(int x, int z) {
        if (blocks.size() == 1) return blocks.get(0).blockId();
        return pickWeighted(blocks, x, z);
    }
}
```

预设示例：
| ID | 显示名 | 内容 |
|----|--------|------|
| `dirt_path` | 土径 | `dirt_path@1`（单方块） |
| `road` | 石路 | `stone@5, gravel@3, stone_bricks@2`（权重随机） |
| `water` | 水源 | `water@1` |
| `grass` | 草方块 | `grass_block@1` |
| `oak_planks` | 橡木木板 | `oak_planks@1` |

权重选取用 `RoadBuilder.pickFromPalette` 的 Splitmix64 确定性哈希。

### 2b. `road/client/RoadPlacementState.java`

客户端状态，类似旧 `RoadProjectionClientState` 但精简：
- `projecting` — 是否在铺设模式
- `selectedPresetIndex` — 当前选中预设下标
- `startPos` — 右键设定的起点（null = IDLE 态）
- `endPos` — 左键设定的终点（null = PLAN_START 态）
- `ghostPos` — 准星下的地面位置（用于预览）
- `presets` — 预设列表（只读，静态数据）
- 无路宽、无 Y 偏移、无路段队列

### 2c. `road/client/RoadPlacementController.java`

输入处理器：
- 每 tick 射线检测 → `ghostPos`
- **右键按下** → 设 `startPos`（覆盖旧起点）
- **左键按下**（仅当 `startPos != null`）→ 设 `endPos`
- **Enter**（仅当 `startPos != null && endPos != null`）→ 发 `RoadPlacePacket`
- **Backspace** → 撤销（终点 → 起点 → 全部）
- **ESC** → 退出
- 不处理滚轮、PageUp/PageDown

### 2d. `road/client/RoadPlacementRenderer.java`

世界空间渲染（`AFTER_TRIPWIRE_BLOCKS` 阶段）：
- 起点标记（绿色 × 1 方块）
- 从起点到 `ghostPos`/终点的 L 形预览路径（铺设方块预览，用预设 `pickBlock` 渲染）
- 终点标记（红色 × 1 方块）
- 无路网边、无节点盒子

### 2e. `road/client/RoadPlacementOverlay.java`

预设选择 UI 覆层：
- 屏幕底部单行预设方块图标 + 名称
- C 键抬起鼠标后点击选择
- 选中高亮

### 2f. `road/network/RoadPlacePacket.java`

```java
public record RoadPlacePacket(String presetId, BlockPos startPos, BlockPos endPos) {}
```

**服务端 handler：**
```java
// 1. 查找预设
RoadPreset preset = RoadPlacementState.getPreset(packet.presetId());

// 2. L 形路径（忽略 Y，2D 平面路径）
PathPoint start = new PathPoint(startPos.getX(), 0, startPos.getZ());
PathPoint end   = new PathPoint(endPos.getX(),   0, endPos.getZ());
List<PathPoint> path = PathGenerator.lShape3D(start, end, 2);

// 3. 生成 tiles（替换地表方块）
JsonArray tiles = new JsonArray();
for (PathPoint pt : path) {
    int sy = level.getHeight(HeightmapTypes.WORLD_SURFACE, pt.x(), pt.z());
    JsonObject tile = new JsonObject();
    JsonArray posArr = new JsonArray();
    posArr.add(pt.x()); posArr.add(sy); posArr.add(pt.z());
    tile.add("pos", posArr);
    tile.addProperty("block", preset.pickBlock(pt.x(), pt.z()));
    tiles.add(tile);
}

// 4. 推入任务池 → NPC 执行
var source = WandscapeEngine.getPlayerManualSource();
if (source != null) {
    Map<String, JsonElement> params = new HashMap<>();
    params.put("tiles", tiles);
    source.publish(new TaskRequest("road:build_segment", params, 10));
}
```

**复用 `road:build_segment` 蓝图**——它在 `steps` 中 `for_each $tiles` → `place` 每个 tile，不做挖掘/净空。

---

## 三、修改清单（8 文件）

### 3a. `Wandscape.java`
- 删除 6 个旧道路数据包注册
- 添加 `RoadPlacePacket` 注册
- `onServerStarting`：保留 `RoadEventListener`、`RoadSavedData`、`RoadApiImpl`
- `onPlayerLoggedOut`：删 `RoadEditorNetwork.removeByUuid()`
- 命令树删 `RoadCommand`、`RoadTestCommand`

### 3b. `WandscapeClient.java`
- 删：`RoadEditorRenderer.register()`、`RoadProjectionRenderer.register()`、`RoadProjectionController.register()`
- 加：`RoadPlacementController.register()`、`RoadPlacementRenderer.register()`

### 3c. `shared/ui/panel/WandscapePanelState.java`
- 删 `RoadProjectionClientState`/`RoadEditorTogglePacket` import
- 保留 `SubMode.ROAD_PROJECTION`，修改行为：
  - `enterSubMode` → 不再发 `RoadEditorTogglePacket`，只设状态
  - `exitCurrentSubMode` → 不再发 `RoadEditorTogglePacket`

### 3d. `shared/ui/panel/WandscapePanelController.java`
- tab 1（Road）不再使用 `BuildingSelectionOverlay`
- 改用 `RoadPlacementOverlay`

### 3e. `shared/ui/panel/WandscapePanelOverlay.java`
- `SubMode.ROAD_PROJECTION` 时渲染 `RoadPlacementOverlay`
- 删旧 overview+road 提示

### 3f. `overview/client/OverviewFlightController.java`
- `RoadProjectionClientState.isProjecting()` → 新状态检查

### 3g. `road/engine/RoadTaskSource.java`
- 删 `PendingDecoration` + `publishDecorations()`
- `PendingSegment`、`enqueueSegment()`、`publishSegments()` 保留（自动路网使用）

### 3h. `engine/WandscapeEngine.java`
- 确保 `getPlayerManualSource()` 可用

---

## 四、保留不变

**road/core/**：`RoadNetwork`, `RoadNode`, `RoadEdge`, `PathPoint`, `RouteSegment`, `XZPoint`, `RoadBuildingData`, `RoadBlobCache`

**road/algorithm/**（除 DecorationPlanner）：`MstCalculator`, `MstEdge`, `NetworkDiff`, `RoadPlanner`, `PathGenerator`, `RoadRouter`

**road/engine/**（除 DecorationBuilder）：`RoadBuilder`, `RoadSavedData`, `RoadEventListener`, `RoadTaskSource`, `RoadApiImpl`, `RoadRoutingHelper`, `RoadBlobExplorer`, `RoadConfig`, `WandscapeTags`

**shared/api/**：`RoadApi`

---

## 五、完整链路

```
V 打开面板 → Road 标签
  → RoadPlacementOverlay 显示预设栏
  → C 键抬起鼠标 → 点击选择预设
  → C 键放下 → 进入世界

  → 右键指向地面 → startPos=ghostPos（绿色标记）
  → 左键指向地面 → endPos=ghostPos（红色标记，L 形路径预览）
  → Enter → RoadPlacePacket(presetId, startPos, endPos)

  → 服务端 handler:
      1. PathGenerator.lShape3D（2D → 路径点）
      2. 每点 getHeight(WORLD_SURFACE) → 生成 tiles
      3. PlayerManualSource.publish(TaskRequest("road:build_segment", tiles))

  → NPC 领取 → 沿路径 walk → 逐个 setBlock（直接替换）
```

**与旧系统关键区别：** 无 `RoadBuilder.buildTiles()`（无挖掘/净空/桥梁/桥墩），无 `RoadEdge`/`RoadNode`，无 `DecorationPlanner`，无 `RoadEditorHandler.removeEdge()`。

---

## 六、验证

1. `./gradlew build` — 编译通过
2. `./gradlew test` — 测试通过
3. 游戏内验证：
   - V → Road 标签 → 显示预设
   - 右键设起点 → 绿色标记
   - 左键设终点 → 红色标记 + L 形路径预览
   - Enter → NPC 走路替换方块（不先破坏）
   - 无绿色路网覆盖
   - 右键不删路
   - 游客仍在已有道路行走
