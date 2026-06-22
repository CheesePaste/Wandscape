# 工作站任务队列 UI

## 目标

工作站/制作站 GUI 侧边显示当前建筑的本地任务队列，支持玩家查看、删除、上调/下调优先级。

## 状态：✅ 已实现 (2026-06-22)

## 实现总结

### 第 1 轮：基础功能 ✅

#### 1. BuildingApi 新增方法

`shared/api/BuildingApi.java` 新增 4 个接口方法：
- `List<WorkItem> getQueue(UUID buildingId)` — 获取 FIFO 队列快照
- `boolean removeFromQueue(UUID buildingId, int index)` — index 0 禁止删除
- `boolean moveUp(UUID buildingId, int index)` — index 0 禁止上移
- `boolean moveDown(UUID buildingId, int index)` — 末项禁止下移

#### 2. BuildingApiImpl 实现

`building/internal/BuildingApiImpl.java` 实现上述方法：
- Deque → ArrayList 转换后操作，回写 Deque
- 边界检查：index 越界、index 0 保护
- 所有修改调用 `savedData.setDirty()`
- **第 2 轮补充**：增加 INFO 级别日志（swap 目标名称），warn 补充拒绝原因

### 第 2 轮：Bug 修复 + 图标化 UI 优化 ✅ (2026-06-22)

#### Bug 修复：moveUp/moveDown 不响应

**根因**：`hitTestButton()` 内层 `for (col 0..2)` 中 col=0 不可用时直接 `return Optional.empty()` 退出，
导致 col=1/2 永远到不了。已重构为两段式：先按 mouseX 定位列，再对该列判断 active。

#### 视觉优化

- 面板高度向上提 4px（`PH - headerHeight - 8`）
- rowHeight 16→14，内容更紧凑
- 按钮替换为 SkinSprite：`drawUpArrow` / `drawDownArrow` / `drawCloseButton`（各 14×14）
- 面板底部不再贴底

#### 图标化信息展示（解决描述截断问题）

**数据结构扩展** — `TaskQueueDataPacket.QueueEntry` 从 3 字段扩充到 6 字段：
```
(index, category, itemOrRecipeId, quantity, blueprintId, summary)
```
服务端 `TaskQueueModifyPacket` 新增两个静态辅助方法：
- `categorize(blueprintId)` → "decompose"/"synthesize"/"craft"/"brew"/"build"/"gather"/"other"
- `extractItemId(blueprintId, params)` → 从 WorkItem params 取 item_id/recipe_id/name

**UI 渲染** — `TaskQueuePanel` 每行布局：
```
[12px icon] [Category短标签] × [数量N]   [↑] [↓] [×]
```
- icon: resolveIcon() 解析 ItemStack → g.renderItem()，解析失败留空（不崩溃）
- label: categoryLabel() 映射固定短标签（不超过 12 字符，如 "Craft Wand"）
- quantity: xN 右对齐，0 时不显示
- 标签最长 "Synthesize" 10 字符，不会截断

**图标降级**：若 itemOrRecipeId 解析不到 MC 物品（如 Recipe ID 无对应物品），icon 留空，文字标签仍正常显示。

### 3. BuildingApiImpl 实现 + 日志 ✅

`building/internal/BuildingApiImpl.java`：
- Deque → ArrayList 转换后操作，回写 Deque
- 边界检查：index 越界、index 0 保护
- 所有修改调用 `savedData.setDirty()`
- 第 2 轮补充：removeFromQueue/moveUp/moveDown 均增加 INFO 日志，warn 补充拒绝原因

### 4. 网络包 ✅

**TaskQueueModifyPacket** (C→S)：`building/network/TaskQueueModifyPacket.java`
- action: "refresh" / "delete" / "move_up" / "move_down"
- 服务端 handler 调对应 BuildingApi 方法，回发 TaskQueueDataPacket
- 新增静态辅助方法：`categorize()` / `extractItemId()` 供构造响应时填充结构化字段

**TaskQueueDataPacket** (S→C)：`building/network/TaskQueueDataPacket.java`
- QueueEntry 从 3 字段扩充为 6 字段：
  `index / category / itemOrRecipeId / quantity / blueprintId / summary`
- category 由服务端 `categorize()` 从 blueprintId 映射
- itemOrRecipeId 由服务端 `extractItemId()` 从 WorkItem params 提取
- quantity 由服务端 `paramInt("count")` 提取
- summary 保留为 fallback/调试字段

### 4. UI 组件：TaskQueuePanel ✅

`shared/ui/component/TaskQueuePanel.java`
- 每行布局：`[12px icon] [Category标签] × [数量]   [↑14px] [↓14px] [×14px]`
- icon: resolveIcon(itemOrRecipeId) → ItemStack → g.renderItem() 12px，解析失败留空不崩溃
- label: categoryLabel() 映射短标签（"Decompose"/"Synthesize"/"Craft Wand" 等）
- quantity: 灰色右对齐（x64），0 时不显示
- 三个 SkinSprite 按钮：drawUpArrow / drawDownArrow / drawCloseButton（各 14×14）
- 图标缓存：iconCache Map<String, ItemStack> 避免每帧重复解析
- index 0 全禁，index 1 ↑ 禁，末项 ↓ 禁

### 5. Screen 修改 ✅

**WorkstationScreen**：`production/client/WorkstationScreen.java`
- PW 280 → 400
- 左侧内容宽度 240，右侧 TaskQueuePanel 宽度 140
- updateQueueData() 处理 TaskQueueDataPacket
- 队列操作回调发送 TaskQueueModifyPacket

**CraftingStationScreen**：`production/client/CraftingStationScreen.java`
- 同上结构

### 6. 网络注册 ✅

**Wandscape.java**：注册 TaskQueueModifyPacket::handleServer (playToServer) + TaskQueueDataPacket (playToClient)

**WandscapeClient.java**：TaskQueueDataPacket.setClientHandler → 同时处理 WorkstationScreen 和 CraftingStationScreen

## 已确认的设计决策

1. **队首保护（index 0）**：禁止删除和移动 ✅
2. **面板始终显示**：不随标签切换隐藏，decompose/synthesize 共用同一队列 ✅
3. **仓库不需要**：仓库无本地队列 ✅
4. **刷新策略**：modify 操作后服务端回发，Screen 打开时发 REFRESH 请求，不主动轮询 ✅
