# warehouse/ — 仓库系统

## 关键设计

元素存储（`Map<UUID, Map<ElementType, Long>>`）与物品存储（`Map<UUID, Map<ItemKey, Long>>`）在同一个 SavedData 中，保证事务原子性。独立于方块——方块破坏不丢失数据。

## GUI 架构（原版容器机制）

仓库 GUI 走真实容器流程（对齐 AE2/RS 等仓储模组）：

- `WarehouseMenu extends AbstractContainerMenu`（注册 `MenuType`，见 Wandscape.MENUS）
  - 槽布局：54 个 `WarehouseSlot`（0-53）+ 36 个 `TabAwareSlot` 玩家槽（54-89），坐标常量在菜单内，两端一致
  - 玩家槽 = vanilla 语义 → 原版快捷键（数字键/Q/Shift/拖拽）与一键整理模组全部兼容
  - `WarehouseSlot` = AE2 `ClientReadOnlySlot` 式只读槽（mayPickup/mayPlace=false、set() 空实现）→ 免疫 vanilla 点击与整理模组误操作；客户端由 Screen 绑定 supplier 显示当前页条目
  - `quickMoveStack`：玩家槽 Shift 点击 → 存入银行；仓库槽 → 拒绝
- `WarehouseScreen extends AbstractContainerScreen`（继承 Medieval 皮肤绘制）
  - Overview 页：元素面板 + 可搜索物品列表（只读）
  - Exchange 页：大箱子式 9×6 格子（每格物品 + 右下角数量，>999 显示 K/M/B 缩写）+ 玩家栏 + 本地搜索/分页
  - 仓库格点击 → 自定义 `WarehouseActionPacket`（光标取整叠/半叠、Shift 快速取出、光标存入）；服务端经 `menu.setCarried()` 同步光标
- 打开链路：右键仓库建筑 / 市政厅"仓库存取"按钮 → `BuildingInteractHandler.openWarehouseMenu` → `player.openMenu` → 客户端 vanilla 流程打开 Screen；`WarehouseDataPacket` 仅负责元素/物品数据刷新

## 交互流

右键原版方块 → BuildingInteractHandler → posIndex O(1) → category=storage → `openWarehouseMenu`（openMenu + 初始数据包）→ 客户端 WarehouseMenu/WarehouseScreen（双页签：总览 + 交换）→ 动作走 WarehouseActionPacket → 银行变更 → sendRefresh

## 依赖

- shared/api/WarehouseApi / shared/event/ResourceInsufficientEvent
- shared/registry/WandscapeApis
- core/boundary/ColonyResourceAccess
- shared/ui/ReplayProtectedScreen（回放保护标记接口）
