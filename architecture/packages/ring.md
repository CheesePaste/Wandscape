# ring/ — 盟誓戒指

玩家侧物品：shift+右键本殖民地法师存入、右键地面放出。同玩家所有戒指共享固定槽存储。

## 类与职责

- `RingTier` — 档位数据：capacity(1/2/4)、requiredColonyLevel(1/10/20，未来配方解锁，现不校验)
- `OathRingItem` — 物品（stacksTo 1）；`useOn`/`use` → 释放；实现 `shared/api/NpcBindingItem.onShiftClickNpc` → 存入
- `internal/OathRingStorage` — 纯逻辑固定 4 槽 + `findStoreSlot`/`findReleaseSlot` + NBT 往返（可单测）
- `internal/OathRingSavedData` — overworld SavedData：player UUID → OathRingStorage
- `internal/OathRingService` — 服务端存取逻辑 + 校验 + action bar 反馈

## 注册

- 物品：`wandscape:oath_ring` / `oath_ring_mid` / `oath_ring_high`（创造标签发放，无配方）
- SavedData 在 `onServerStarting` 预载

## 依赖

- shared/api/NpcBindingItem（WandscapeNpc.mobInteract 潜行拦截转交）
- npc/entity/WandscapeNpc（法师实体，同 building→npc 例外）
- engine/colony via WandscapeApis.getColonyApiSilently（getColonyByFounder）
- Wandscape（WANDSCAPE_NPC / 注册表 / 创造标签）

详细设计见 [../../docs/modules/ring.md](../../docs/modules/ring.md)。