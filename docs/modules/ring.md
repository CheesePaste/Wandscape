# ring/ — 盟誓戒指模块

`src/main/java/com/wsteam/wandscape/ring/`

## 职责

玩家侧物品「盟誓戒指」：shift+右键本殖民地法师存入、右键地面/空气放出。同一玩家的所有戒指共享同一固定槽存储空间。

## 规则（用户拍板）

- **档位与槽位（固定槽）**：低级容量 1（只存取槽 0）、中级容量 2（槽 0~1）、高级容量 4（槽 0~3）。释放固定取最低已占槽；释放后槽位不塌缩，法师保持原索引。
- **归属限制**：只能存入「玩家自己创建殖民地」的法师（`ColonyApi.getColonyByFounder`）；**无殖民地的玩家禁止使用**（存取都拒绝）。
- **等级门槛**：`RingTier.requiredColonyLevel`（1/10/20）与合成站配方 `min_colony_level` 一致（1/10/20），三档由合成站配方产出；创造栏仍发放三档。
- **共享空间**：存储按存取玩家 UUID 键控，落盘于 overworld `OathRingSavedData`；与戒指物品所在槽位/是否在背包无关。
- **Curios 兼容**：已通过 `data/curios/tags/item/ring.json` 接入 `curios:ring` 物品标签（三档均可佩戴于 Curios 戒指槽）；存取数 tooltip 同步由 `OathRingClientData` 驱动。

## 代码结构

| 类 | 职责 |
|---|---|
| `RingTier` | 档位枚举：capacity（1/2/4）+ requiredColonyLevel（1/10/20） |
| `OathRingItem` | 物品：`useOn`/`use` 释放，`onShiftClickNpc` 存入（实现 `shared/api/NpcBindingItem`） |
| `internal/OathRingService` | 服务端业务：校验（是否有殖民地/是否本殖民地法师/槽位）、存入（`mage.save`→落槽→`discard`）、放出（安全落点→`create`+`load`+`addFreshEntity`）、action bar 反馈 |
| `internal/OathRingSavedData` | `SavedData`：player UUID → `OathRingStorage`；overworld 全局，启动时预载 |
| `internal/OathRingStorage` | **纯逻辑**（可单测）：固定 4 槽（0~3）+ 槽选择算法 + NBT 往返 |

## 交互流

**存入**：手持戒指 → shift+右键殖民地法师 → `WandscapeNpc.mobInteract`（潜行 + 手持 `NpcBindingItem`）转交 `OathRingItem.onShiftClickNpc` → `tryStore`：
1. 玩家有自己殖民地？无 → 提示禁用。
2. 法师 `colonyId` 属于该殖民地？否 → 提示只能存本殖民地法师。
3. `findStoreSlot(capacity)` 有空槽？无 → 提示档位已满。
4. `mage.save(nbt)` → `storage.put(slot, nbt)` → `setDirty()` → `mage.discard()`（**先落存储再移除**，失败则法师不动）。
5. `onRemovedFromLevel(DISCARDED)` 自动释放全局任务回池、取消运输、销毁 ECS。

**放出**：手持戒指 → 右键方块（`useOn`，点击面旁）/ 右键空气（`use`，玩家面向前 1 格）→ `tryRelease`：
1. 玩家有自己殖民地？无 → 提示禁用。
2. `findReleaseSlot(capacity)`；无已存 → 「空」；有存但都超档位 → 「超出可存取范围」。
3. `findSpawnPos` 找安全落点（下实心 + 两层空气 + chunk 已加载；垂直上 8 格、水平螺旋 6 格兜底）。
4. `Wandscape.WANDSCAPE_NPC.get().create(level)` → `npc.load(nbt)`（恢复 UUID/属性/装备/法术等）→ `moveTo` 落点 → `addFreshEntity` → 清槽 `setDirty()`。
5. `onAddedToLevel` → `EntityComponentBridge.onNpcJoinWorld` fresh 注册，colonyId 从 NBT 恢复，返回对应殖民地为空闲法师。

## 为什么安全

- **属性不丢**：`WandscapeNpc` 7 属性存于 vanilla `attributes` 标签（`LivingEntity.add/readAdditionalSaveData`），`Entity.save()/load()` 无损往返；当前属性权威是 vanilla AttributeMap（`getEffectiveAttribute`、`NpcDataImpl.from`、`NpcSpellPowerHandler` 均直读 vanilla，无 ECS→vanilla 覆盖）。
- **身份不丢**：`Entity.load` 读回 UUID（老 ECS id 走 bridge fresh 注册路径）。
- **状态清理**：`discard()` 触发时分释放全局任务/取消运输/销毁 ECS（见 `WandscapeNpc.onRemovedFromLevel`）。
- **交互确定**：潜行拦截放在 `mobInteract` 内（本就拦截全部右键），不依赖 NeoForge `PlayerInteractEvent` 的取消语义。

## 依赖

- `shared/api/NpcBindingItem`（潜行交互钩子，npc → shared）
- `npc/entity/WandscapeNpc`（法师实体，类似 building/→npc 的既有例外）
- `npc/internal/EntityComponentBridge`（释放时 fresh 注册）
- `engine/colony`（getColonyByFounder 经 `WandscapeApis.getColonyApiSilently`）
- `Wandscape`（`WANDSCAPE_NPC` 实体注册 + 物品注册 + 创造标签 + SavedData 启动预载）

## 注册

- 物品：`wandscape:oath_ring` / `oath_ring_mid` / `oath_ring_high`（`Item.Properties().stacksTo(1)`）。
- 创造标签 `WANDSCAPE_TAB` 发放三档。
- SavedData：`OathRingSavedData.get(server)`（overworld），`onServerStarting` 预载。
- 目前**无合成配方**（未来按 `RingTier.requiredColonyLevel` 解锁）。

## 消息（action bar）

`message.wandscape.ring.*`：no_colony / other_colony / slots_full / store_failed / store.success / empty / inaccessible / no_spot / release_failed / release.success。zh/en 双语。