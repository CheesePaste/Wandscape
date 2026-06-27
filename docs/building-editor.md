# 建筑编辑器 — 设计文档

玩家灵魂出窍，通过 AABB 包围盒选区和 GUI 面板编辑建筑 JSON，一键导出到 `data/wandscape/buildings/`。

**灵感**: Axiom Mod 的建筑编辑模式 + 本 mod 已有的 Soul Projection 系统。

## 已确认设计决策

| # | 决策 | 结论 |
|---|------|------|
| A | 相机模式 | 自由飞行（复用 ProjectionFlightController） |
| B | 扫描时机 | 实时扫描（AABB 变化即时更新 pattern/block_mapping） |
| C | block_mapping | 相同方块自动合并去重 |
| D | 导出行为 | 新建 + 覆盖警告 |
| E | 加载已有 | 支持（命令带 building_id） |
| F | 撤销/重做 | v1 不做 |
| G | Anchor | 默认=min，用户可拖拽调整 |
| H | GUI | MedievalScreen 右侧面板 |
| I | 方块操作 | 编辑器内不放置/破坏方块 |
| J | Pattern 编辑 | 支持手动 Add/Remove 偏移（热键） |
| K | 并发 | 单人单建筑 |
| L | 验证失败 | 警告但允许导出 |
| M | JSON 格式 | Pretty-print |
| N | 世界时间 | 继续，不暂停 |
| O | 扫描已有结构 | 支持（从世界中一键生成配置） |

## 入口命令

```bash
/wandscape build edit                 # 新建模式：空 AABB，无预填数据
/wandscape build edit <building_id>   # 编辑模式：加载已有建筑 JSON
/wandscape build done                 # 退出编辑器
/wandscape build scan                 # 从当前 AABB 扫描方块生成配置
```

权限: OP level 2。

## 架构

```
building/editor/
  ├── BuildingEditorClientState.java       — 客户端静态状态
  ├── BuildingEditorScreen.java           — MedievalScreen 右侧面板
  ├── BuildingEditorRenderer.java         — AABB 线框 + 方块高亮 + 手柄
  ├── BuildingEditorInputHandler.java     — 鼠标拦截 + 拖拽 + 热键
  ├── BuildingEditorController.java       — 生命周期管理 + tick
  └── BuildingEditorExportService.java    — JSON 序列化 + 写入（服务端）

building/network/
  ├── BuildingEditorEnterPacket.java      (C→S: 请求进入编辑)
  ├── BuildingEditorEnterResponsePacket.java (S→C: 进入确认 + 加载数据)
  ├── BuildingEditorExitPacket.java       (C→S: 退出编辑)
  ├── BuildingEditorExportPacket.java     (C→S: 携带 BuildingConfig JSON)
  ├── BuildingEditorExportResultPacket.java (S→C: 导出结果)
  ├── BuildingEditorSyncPacket.java       (S→C: 同步编辑状态给其他玩家)
  └── BuildingEditorNetwork.java          — 服务端玩家追踪
```

### 数据流

```
玩家输入 /wandscape build edit [id]
  → BuildingEditorEnterPacket (C→S)
  → BuildingEditorNetwork.addEditing(player)
  → 如有 building_id: 从 BuildingConfigLoader 加载 → BuildingEditorEnterResponsePacket (S→C)
  → 如无: 空状态 BuildingEditorEnterResponsePacket (S→C)
  → 客户端: BuildingEditorClientState.enterEditMode(data)
  → 客户端: 注册 Renderer + InputHandler + Screen

玩家在编辑器中操作:
  → InputHandler 处理点击/拖拽 → 更新 ClientState (AABB/pattern/anchor)
  → Renderer 每帧读取 ClientState → 渲染线框/高亮/手柄
  → Screen 读取 ClientState → 刷新 GUI 字段
  → AABB 变化 → 触发世界扫描 → 更新 pattern + block_mapping

玩家点击"导出":
  → BuildingEditorExportPacket (C→S: 完整 BuildingConfig JSON string)
  → BuildingEditorExportService:
      1. 反序列化验证
      2. 必填字段检查
      3. 重叠检测 (可选)
      4. 写入 data/wandscape/buildings/{id}.json
      5. 返回 BuildingEditorExportResultPacket (success|failure + messages)

玩家 ESC:
  → BuildingEditorExitPacket (C→S)
  → 恢复游戏模式/位置
  → 卸载 Renderer/Screen/InputHandler
```

## 客户端状态 (BuildingEditorClientState)

```java
// 编辑模式
boolean editMode
// AABB (相对于 anchor)
BlockOffset min, max  // 拖拽可变
BlockOffset anchor    // 默认 = min，可拖拽
// 世界坐标 (anchor 在世界中的位置)
BlockPos worldAnchor
// 扫描结果
List<BlockOffset> pattern
Map<String, String> blockMapping  // "x,y,z" → "mod:block_id"
// 建筑元数据
String buildingId, displayName, category
int comfort, magic, wonder
int interactionRadius
// 队列
int queueCapacity
List<String> taskTypes
// 解锁
int unlockMinComfort, unlockMinMagic, unlockMinWonder
// 维护费
int maintenanceIntervalTicks
Map<ElementType, Integer> maintenanceCosts
// 蓝图引用
String blueprintId
Map<String, String> blueprintBind
// 分类特定
// shop: List<ShopGoodDef>, profitRate
// service: ...
// decoration: radius
// wonder: List<WonderEffect>
// node: NodeConfig
```

## 渲染 (BuildingEditorRenderer)

注册到 `RenderLevelStageEvent.AFTER_TRIPWIRE_BLOCKS` (与 RoadEditorRenderer 一致)。

### AABB 线框
- 渲染半透明面 + 线框
- 绿色: 合法（无重叠，非空）
- 红色: 与已有建筑重叠
- Y 轴偏移: +0.02 防 Z-fighting

### 方块高亮
- Pattern 内方块: 青色线框
- 非 pattern 方块 (在 AABB 内但不在 pattern 中): 灰色半透明
- 空气方块: 不渲染

### 可拖拽手柄
- 8 个角 + 6 个面中心 = 14 个手柄
- 小方框渲染 (0.2×0.2)
- 颜色: 金色 (角) / 银色 (面)
- 悬停高亮: 白色

### Anchor 标记
- 金色星形/菱形标记在 anchor 位置
- 可拖拽

## 输入处理 (BuildingEditorInputHandler)

注册到 `ClientTickEvent.Post`。

```
鼠标:
  左键点击  → 设置 min（对着地面/方块）
  右键点击  → 设置 max（对着地面/方块）
  Shift+左键 → 设置 anchor
  左键拖拽  → 拖拽手柄（调整 min/max/anchor）
  中键点击  → 对准的方块: 从 pattern 中移除
  中键+Shift → 对准的方块: 添加到 pattern
  滚轮      → 调整 GUI 焦点字段值

键盘:
  WASD/Space/Shift → 飞行（复用 ProjectionFlightController）
  E               → 开关 GUI 面板
  Enter           → 导出（等同点击导出按钮）
  Escape          → 退出编辑模式
  Ctrl+Z          → (v2) 撤销
```

## GUI 面板 (BuildingEditorScreen)

屏幕右侧，宽 180px，高自适应（最大到屏幕高度）。MedievalScreen 子类。

### 布局 (从上到下)

```
┌─────────────────────────────┐
│ 🏛 建筑编辑器               │  ← 标题栏
│─────────────────────────────│
│ ID:        [_____________]  │
│ 名称:      [_____________]  │
│ Category:  [basic      ▼]  │  ← 下拉
│─────────────────────────────│
│ 📐 三值                     │
│   Comfort: [-] [5] [+]     │
│   Magic:   [-] [3] [+]     │
│   Wonder:  [-] [1] [+]     │
│─────────────────────────────│
│ 🔧 维护费                   │
│   间隔: [12000] ticks      │
│   [+添加元素]               │
│─────────────────────────────│
│ 📋 [Category 特定配置]      │  ← 动态显示
│─────────────────────────────│
│ 🔓 解锁条件                 │
│   [0] [0] [0]              │
│─────────────────────────────│
│ 📦 队列                     │
│   cap: [5]                 │
│ 🔄 交互半径: [0]            │
│─────────────────────────────│
│ [  导出  ] [  验证  ]      │
│ [  预览  ] [  退出  ]      │
└─────────────────────────────┘
```

### Category 特定子面板

| Category | 显示字段 |
|----------|---------|
| shop | goods 列表 (item_id/restock_cost/comfort/magic/wonder), profit_rate |
| service | energy_per_use, satisfaction_per_use, element_output map, max_occupancy |
| decoration | radius |
| wonder | effects 列表编辑器 (+type/+target/+value) |
| node | element, amount_per_harvest, channel_ticks, mana_cost |

## 网络包详情

### BuildingEditorEnterPacket (C→S)
```
空 payload。服务端: 验证权限 → BuildingEditorNetwork.addEditing() → 加载已有数据 (如提供id) → 响应。
```

### BuildingEditorEnterResponsePacket (S→C)
```
boolean success
String errorMessage (if !success)
String buildingId (空=新建)
String buildingJson (已有建筑的完整 JSON string，新建为空)
BlockPos bodyAnchor
List<BuildingSlot> existingBuildings (用于重叠检测)
```

### BuildingEditorExportPacket (C→S)
```
String buildingJson  — 完整 BuildingConfig JSON string
boolean overwrite    — 是否确认覆盖
```

### BuildingEditorExportResultPacket (S→C)
```
boolean success
String message       — 成功: "Exported to data/wandscape/buildings/xxx.json"
                     — 失败: 错误列表 (每行一个)
List<String> warnings — 警告 (可恢复问题，如"shop has no goods")
```

### BuildingEditorSyncPacket (S→C) [v2]
```
UUID editingPlayer
BlockOffset min, max
BlockPos worldAnchor
String buildingId
// 广播给其他玩家以渲染包围盒
```

## 导出服务 (BuildingEditorExportService)

服务端执行：

```java
public ExportResult export(String buildingJson, boolean overwrite) {
    // 1. 解析 JSON → BuildingConfig
    // 2. 验证必填字段: id, display_name, category, pattern (非空), block_mapping
    // 3. 验证 block_mapping 覆盖所有 pattern 偏移
    // 4. 去重 block_mapping (相同 block ID 不重复)
    // 5. 检查 category 合法值
    // 6. 如已有文件且非 overwrite → 返回警告
    // 7. 写入 data/wandscape/buildings/{id}.json (pretty-print)
    // 8. 返回成功 + 路径
}
```

## 实现顺序

1. **BuildingEditorClientState** — 纯状态 holder
2. **BuildingEditorNetwork** — 服务端玩家追踪 + 数据源
3. **网络包** — Enter/EnterResponse/Exit/Export/ExportResult
4. **BuildingEditorController** — 生命周期: 进入/退出/视角切换
5. **BuildingEditorRenderer** — 世界渲染: AABB + 高亮 + 手柄
6. **BuildingEditorInputHandler** — 输入: 点击/拖拽/热键/飞行
7. **BuildingEditorScreen** — GUI 面板
8. **BuildingEditorExportService** — 导出 + 验证
9. **Wandscape.java 注册** — 包注册 + 命令 + 事件
10. **命令** — `/wandscape build edit/done/scan`
