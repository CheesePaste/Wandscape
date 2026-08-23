# npc/ — NPC 实体与 ECS 桥接

## 核心设计

EntityComponentBridge（单例）：双向映射 ecsEntityId ↔ MC 实体。三个生命周期回调：
- `onNpcJoinWorld` — NPC 加入世界 → ECS 创建组件
- `onNpcLeaveWorld` — KILLED/DISCARDED → 移除 ECS 组件（chunk 卸载保留）
- `syncPositions` — 每 tick MC 位置 → ECS Position

PLACEHOLDER_COLONY = UUID(全0)，允许殖民地系统完成前引擎调度正常工作。

## 注册

- 实体：`wandscape:wandscape_npc` (MobCategory.CREATURE)
- 粒子：`wandscape:cast_bolt`
- 刷怪蛋：`wandscape_npc_spawn_egg` (深紫#4B0082+金色#FFD700)

## NPC 交互界面（原版容器化，B 阶段后）

右键 NPC / 装备屏"Strategy"按钮走 `openMenu` 打开真实容器菜单（与仓库同款 vanilla 槽机制）：

- `NpcMenu`（装备，MenuType `wandscape:npc`）：4 盔甲槽（`NpcArmorSlot`，canEquip 部位校验 + 原版空槽部位图标，直写 NPC `armorInventory`）+ 1 法杖槽（`WandSlot`，变更同步手持/默认法杖恢复）+ 36 玩家槽（`VanillaPlayerInventory`）；任意槽操作（`menu.clicked`）后同步 NPC 实体
- `NpcStrategyMenu`（策略，MenuType `wandscape:npc_strategy`）：12 卷轴槽（4 分类 × 3，`SpellSlot` mayPlace 校验分类/去重/每类 ≤3），槽内真实卷轴（取出即拿回），每次槽操作重建扁平装备态写回 `EquippedMagicComponent`；预设经 `NpcStrategyPacket`（只改 preset）
- 客户端 `NpcScreen` / `NpcStrategyScreen extends AbstractContainerScreen`：原版槽渲染 + 玩家背包原版底 + 悬停 tooltip 与仓库一致；实体 id 经 `NpcDataPacket` 下发（客户端菜单构造时未知）
- 打开链路：`WandscapeNpc.mobInteract` → `openMenu(NpcMenu)` + 下一 tick 补发 `NpcDataPacket`；装备屏按钮 → `NpcOpenStrategyPacket` → 服务端 `openMenu(NpcStrategyMenu)`
- 旧 `NpcEquipPacket` 已删除（装备改由菜单槽直接驱动）

## 依赖

- shared/api/NpcApi, shared/data/NpcData
- shared/event/NpcDiedEvent
- core/component/*（通过 EntityComponentBridge 创建）
- shared/ui/vanilla（玩家背包共享组件）
