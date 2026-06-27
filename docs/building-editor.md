# building/editor/ — 建筑编辑器

游戏内可视化建筑编辑工具。玩家在飞行模式下框选世界中的建筑结构，编辑元数据，一键导出为标准 BuildingConfig JSON。

## 进入/退出

- 通过命令 `/wandscape editor` 进入编辑模式
- 进入时自动开启创造飞行（退出时恢复原有能力）
- 退出时向服务端发送 `BuildingEditorExitPacket`

## 架构概览

```
Client                                 Server
┌─────────────────────────┐            ┌──────────────────────┐
│ Controller (tick)       │            │ EditorNetwork        │
│  ├── InputHandler       │  packet →  │  ├── player tracking │
│  └── ImGui panel        │            │  └── validateEntry() │
│                         │            │                      │
│ Renderer (world)        │  export →  │ ExportService        │
│  ├── AABB wireframe     │            │  ├── parse → validate│
│  ├── pattern highlights │            │  └── write JSON file │
│  └── anchor marker      │            │                      │
│                         │            │ ConfigLoader         │
│ AxisRenderer            │            │  (reload on export)  │
│  ├── 3D arrow rendering │            └──────────────────────┘
│  └── hitTestAxis()      │
│                         │
│ ClientState (shared)    │
│  ├── editMin/editMax    │
│  ├── worldAnchor        │
│  ├── pattern/blockMap   │
│  └── all metadata       │
└─────────────────────────┘
```

## 关键类

### 控制器层

| 类 | 职责 |
|---|---|
| `BuildingEditorController` | 每 tick 生命周期：飞行、右击旋转、委托 InputHandler |
| `BuildingEditorInputHandler` | 鼠标交互：轴拖拽、中键增删 pattern、射线追踪 |
| `BuildingEditorClientState` | 客户端唯一状态：AABB/pattern/metadata/拖拽状态。全 volatile |

### 渲染层

| 类 | 职责 |
|---|---|
| `BuildingEditorRenderer` | AABB 半透明面+线框、pattern 方块高亮边框、anchor 十字标记 |
| `BuildingEditorAxisRenderer` | 6 个 3D 实体箭头（+X/-X/+Y/-Y/+Z/-Z）；X-ray 渲染无视遮挡；`hitTestAxis()` 射线命中检测 |

### UI 层

| 类 | 职责 |
|---|---|
| `BuildingEditorImGui` | ImGui 面板：输入字段、分类折叠区、AABB 状态、操作按钮、导出/预览 |
| `ImGuiManager` (imgui/) | ImGui 生命周期：初始化、帧渲染、输入拦截、字体缩放 1.6x |

### 网络层

| 类 | 职责 |
|---|---|
| `BuildingEditorNetwork` | 服务端追踪编辑中的玩家（UUID set）；校验入口条件 |
| `BuildingEditorExportService` | 解析/校验/格式化写入 JSON 到 `data/wandscape/buildings/` |

## 坐标系

```
worldAnchor (世界绝对坐标)
    │
    ├── editMin (相对偏移) → worldMin = worldAnchor + editMin
    └── editMax (相对偏移) → worldMax = worldAnchor + editMax

pattern[i] = 相对于 worldAnchor 的 BlockOffset
anchorOffset = worldMin 到 worldAnchor 的偏移（auto-anchor 使用）
```

- `worldAnchor` 是 pattern 坐标的原点
- `editMin` / `editMax` 定义 AABB，扫描时取该范围内的非空气方块
- **Auto Anchor**：勾选后，`worldAnchor` 自动移到 AABB 底部中心（Y 最低、XZ 中心），editMin/editMax/anchorOffset 同步重算

## 鼠标模型

| 操作 | 行为 |
|---|---|
| 左键点击轴箭头 | 开始拖拽该角落 |
| 左键拖拽 | 缩放 AABB（实时扫描方块） |
| 左键松开 | 结束拖拽，触发 auto-anchor 重算 |
| 中键点击方块 | 方块加入 pattern + block_mapping |
| Shift+中键 | 从 pattern 移除该方块 |
| 右键按住 | MC 原生视角旋转 |
| WASD/Space/Shift | 飞行移动（Ctrl 加速） |
| Escape | 退出编辑器 |
| Enter | 导出 JSON |

## 轴拖拽逻辑

### 箭头位置

| 箭头 | 渲染位置 | 拖拽修改 |
|---|---|---|
| +X/+Y/+Z (POS) | **max** 角落 | max |
| -X/-Y/-Z (NEG) | **min** 角落 | min |

### 拖拽状态机

```
hover → 每帧 hitTestAxis(射线) → setHoveredAxis
  │
  ├── 左键按下 (hovered != null) → startDrag
  │     ├── 记录 dragStartAxisOrigin (固定！拖拽全程不变)
  │     ├── 记录 dragStartAxisValue (射线在轴上的投影 u)
  │     └── 快照 dragSavedMin / dragSavedMax
  │
  ├── 拖拽中 (dragging != null && leftDown)
  │     ├── getClosestPointOnAxis(cam, camDir, dragStartAxisOrigin, axisDir)
  │     ├── delta = round(current - start)
  │     ├── delta = -delta  ← 原点交换后的符号修正
  │     └── 更新 editMin/Max (不允许 Min > Max)
  │
  └── 松开 → finishDrag → recalculateAnchor()
```

**注意：** 拖拽中不可重算 anchor（有 `isDragging()` 守卫），否则坐标系突变导致 delta 飞掉。

### 数学

`getClosestPointOnAxis(rayOrigin, rayDir, axisOrigin, axisDir)` — 求射线与无限长轴线的最近点，返回在轴上的参数 u。两次调用（start vs current）的 u 差值即为鼠标在轴上的移动量。

## Auto Anchor

- 默认开启，可在面板中切换
- `recalculateAnchor()` 被调用时机：
  - 拖拽松手后（`finishAxisDrag`）
  - Snap Max 后
  - 面板中重新勾选时
- 计算：`newAnchor = (worldMin.xz 中心, worldMin.y)` → 更新 anchor + 重算 editMin/editMax/anchorOffset
- 拖拽期间不触发（防止坐标系突变）

## ImGui 面板布局

```
┌─────────────────────────┐
│ ID / Name / Category    │  ← 文本框+下拉
│ Comfort / Magic / Wonder│  ← 三字段横排 (60px)
│ Unlock C / U M / U W    │
│ QueueCap / Radius / ... │
│ Blueprint               │
│ [分类折叠区]            │  ← 按 category 显示对应字段
├─────────────────────────┤
│ AABB SELECTION          │
│ [min] -> [max]          │
│ N blocks                │
│ ☑ Auto Anchor (bottom-center)│
│ [Set Anchor]            │  ← 按钮
│ [Snap Max]              │
│ [Scan Blocks]           │
├─────────────────────────┤
│ [Export JSON]           │
│ [Preview JSON]          │
│ [Exit Editor]           │
└─────────────────────────┘
宽度: 300px, 字体缩放: 1.6x
```

## 数据流：导出

```
ImGui "Export JSON" → Controller.doExport()
  → ClientState.buildExportJson()  ← 手写 JSON 构建
  → PacketDistributor.sendToServer(ExportPacket)
    → Server: ExportService.export()
      ├── Gson 反序列化为 BuildingConfig
      ├── validate() — id/name/category/pattern 必填校验
      ├── validateBlockMapping() — pattern ↔ mapping 交叉校验
      ├── validateCategoryConfig() — 分类特有字段警告
      └── 写入 data/wandscape/buildings/{id}.json (pretty print)
```

### JSON 构建注意事项

- **blueprint bind 默认值**：当 `blueprintBind` 为空时，自动填入：
  ```json
  "bind": {
    "offsets": "$pattern",
    "blocks": "$block_mapping",
    "name": "$display_name"
  }
  ```
  否则蓝图 `build:clear_and_build` 缺少必要参数会报错
- 尾部逗号需清理
- `block_mapping` key 按字母序输出（可重复性）

## 注册

所有类通过 `Wandscape.initEditor()` 注册：

| 类 | 注册到 | 时机 |
|---|---|---|
| Controller | NeoForge EVENT_BUS (ClientTick.Post + MouseScroll) | 模组初始化 |
| InputHandler | NeoForge EVENT_BUS (ClientTick.Pre) | 模组初始化 |
| Renderer | NeoForge EVENT_BUS (RenderLevelStage) | 模组初始化 |
| AxisRenderer | NeoForge EVENT_BUS (RenderLevelStage) | 模组初始化 |
| Network | 无（被动调用） | 数据包到达时 |

## 调试

- Controller 每 40 tick 输出心跳日志：`[BuildEditor] Controller heartbeat: tick=...`
- InputHandler 每 20 tick 输出 hover 状态变更日志
- 拖拽状态变更每 10 tick 记录一次
- 所有日志前缀 `[BuildEditor]`，`grep` 即可过滤
- `/wandscape editor` 命令进入编辑模式开始测试
